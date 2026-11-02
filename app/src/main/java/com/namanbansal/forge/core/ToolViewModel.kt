package com.namanbansal.forge.core

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.namanbansal.forge.ForgeApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Where a tool is in the shared Pick -> Options -> Run -> Result flow. */
enum class ToolPhase { Pick, Options, Running, Result, Failed }

/** What a tool's [ToolViewModel.execute] returns; the base class turns it into a [ToolResult]. */
class ToolOutcome(
    val output: SavedOutput,
    val headline: String,
    val detail: String? = null,
    /** Every file produced (Split saves many); [output] is the primary one. */
    val outputs: List<SavedOutput> = listOf(output),
)

/** Everything the result screen shows. */
data class ToolResult(
    val output: SavedOutput,
    val inputName: String,
    val toolCode: String,
    val beforeSize: Long,
    val afterSize: Long,
    val headline: String,
    val detail: String?,
    val outputs: List<SavedOutput> = listOf(output),
) {
    val kind: FileKind get() = FileKind.fromMime(output.mime)
    /** Bytes saved (positive) or added (negative); null if either size is unknown. */
    val savedBytes: Long? get() = if (beforeSize >= 0 && afterSize >= 0) beforeSize - afterSize else null
}

/** Handed to [ToolViewModel.execute]: progress, cooperative cancellation and output saving. */
class RunScope internal constructor(private val vm: ToolViewModel) {
    /** Report progress 0..1, or null for indeterminate. */
    fun progress(fraction: Float?) = vm.setProgress(fraction)

    /** Throws CancellationException if the user pressed Cancel. Call between units of work. */
    suspend fun checkCancelled() = currentCoroutineContext().ensureActive()

    /** Stores a new file in Downloads/Forge named `<primary input>_<action>.<ext>`. */
    suspend fun save(action: String, ext: String, mime: String, write: suspend (OutputStream) -> Unit): SavedOutput =
        vm.saver.save(vm.primaryInputName(), action, ext, mime, write).also { vm.noteSaved(it) }
}

/**
 * Base for every tool screen's state. It owns the shared flow (picked inputs that survive rotation,
 * running with progress + cancel, error with retry, result) and records finished outputs to Recent.
 * A tool subclass only supplies [toolCode], reacts to new inputs in [onInputsReady] and does its work in [execute].
 */
abstract class ToolViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {

    /** Registry code of the tool, e.g. "P4". */
    abstract val toolCode: String

    internal val app: ForgeApp get() = getApplication<Application>() as ForgeApp
    internal val saver: OutputSaver get() = app.outputSaver

    private val _inputs = MutableStateFlow<List<PickedFile>>(emptyList())
    val inputs: StateFlow<List<PickedFile>> = _inputs.asStateFlow()

    private val _phase = MutableStateFlow(ToolPhase.Pick)
    val phase: StateFlow<ToolPhase> = _phase.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)
    /** null while running = indeterminate. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _loading = MutableStateFlow(false)
    /** True while the picked files are being read/opened (before Options is ready). */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _result = MutableStateFlow<ToolResult?>(null)
    val result: StateFlow<ToolResult?> = _result.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** One-shot messages for the snackbar ("Cancelled", "Couldn't read that file"). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var runJob: Job? = null
    private var inputJob: Job? = null
    private val savedThisRun = java.util.concurrent.CopyOnWriteArrayList<SavedOutput>()

    private val persistedStates = mutableListOf<PersistedState<*>>()
    private var restoringInputs = false
    /** True when the current inputs were restored after process death (so option state saved with them should be kept). */
    protected var stateRestored = false
        private set

    /** Option state that survives process death; reset whenever the user picks new files. */
    protected fun <T : Any> persisted(key: String, default: T, encode: (T) -> Any = { it }, decode: (Any) -> T? = { null }): PersistedState<T> =
        PersistedState(saved, key, default, encode, decode).also { persistedStates += it }
    protected fun persistedInt(key: String, default: Int) = persisted(key, default, decode = { (it as? Int) })
    protected fun persistedBool(key: String, default: Boolean) = persisted(key, default, decode = { (it as? Boolean) })
    protected fun persistedString(key: String, default: String) = persisted(key, default, decode = { (it as? String) })
    /** A set of ints (selected pages...), stored as an IntArray. */
    protected fun persistedIntSet(key: String, default: Set<Int> = emptySet()) =
        persisted(key, default, encode = { it.toIntArray() }, decode = { (it as? IntArray)?.toSet() })

    init {
        // Deferred (non-immediate Main) so the subclass constructor has finished: toolCode and the
        // subclass's own state must exist before onInputsReady runs.
        // Order: files handed over by another screen/app > files restored after process death.
        inputJob = viewModelScope.launch(Dispatchers.Main) {
            val handed = app.pendingFiles.take(toolCode)
            if (handed != null) {
                applyFiles(handed)
            } else {
                val restored = saved.get<ArrayList<String>>(KEY_INPUTS)?.takeIf { it.isNotEmpty() }
                if (restored != null) {
                    restoringInputs = true
                    try { applyFiles(restored.map { FileResolver.resolve(app, Uri.parse(it)) }) } finally { restoringInputs = false }
                }
            }
        }
    }

    protected fun notify(message: String) { _messages.tryEmit(message) }

    internal fun setProgress(f: Float?) { _progress.value = f?.coerceIn(0f, 1f) }

    internal fun primaryInputName(): String = _inputs.value.firstOrNull()?.name ?: "file"

    // ---- Pick ----------------------------------------------------------------------------------------------

    /** Picker callback. Empty list (user cancelled the picker) is ignored. */
    fun onUrisPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        inputJob?.cancel()
        inputJob = viewModelScope.launch {
            val files = uris.map { FileResolver.resolve(app, it) }
            applyFiles(files)
        }
    }

    fun onFilesPicked(files: List<PickedFile>) {
        if (files.isEmpty()) return
        inputJob?.cancel()
        inputJob = viewModelScope.launch { applyFiles(files) }
    }

    private suspend fun applyFiles(files: List<PickedFile>) {
        _loading.value = true
        try {
            onInputsReset()
            stateRestored = restoringInputs
            if (!restoringInputs) persistedStates.forEach { it.reset() }
            _inputs.value = files
            saved[KEY_INPUTS] = ArrayList(files.map { it.uri.toString() })
            _error.value = null
            _phase.value = ToolPhase.Options
            onInputsReady(files)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // The tool could not use these files (corrupt, unsupported...): go back to Pick with the reason.
            _inputs.value = emptyList()
            saved.remove<ArrayList<String>>(KEY_INPUTS)
            _phase.value = ToolPhase.Pick
            notify(t.friendlyMessage())
        } finally {
            _loading.value = false
        }
    }

    /** Called before new inputs replace the old ones: release per-file resources here. */
    protected open fun onInputsReset() {}

    /** Open/inspect the new inputs. Throw [ToolException] to reject them with a friendly message. */
    protected open suspend fun onInputsReady(files: List<PickedFile>) {}

    /**
     * For tools that edit their own list of inputs after picking (Merge: add, remove, reorder): replaces the shown inputs
     * and remembers them across process death. Does not touch option state or re-run [onInputsReady].
     */
    protected fun replaceInputs(files: List<PickedFile>) {
        _inputs.value = files
        if (files.isEmpty()) saved.remove<ArrayList<String>>(KEY_INPUTS) else saved[KEY_INPUTS] = ArrayList(files.map { it.uri.toString() })
    }

    /** Back to the picker (the "Change" action). */
    fun changeFiles() {
        inputJob?.cancel()
        onInputsReset()
        persistedStates.forEach { it.reset() }
        _inputs.value = emptyList()
        saved.remove<ArrayList<String>>(KEY_INPUTS)
        _phase.value = ToolPhase.Pick
    }

    // ---- Run -----------------------------------------------------------------------------------------------

    /** Do the work. Runs on [Dispatchers.Default]; blocking IO inside should use withContext(Dispatchers.IO). */
    protected abstract suspend fun execute(scope: RunScope): ToolOutcome

    fun start() {
        if (runJob?.isActive == true || _phase.value == ToolPhase.Running) return
        val files = _inputs.value
        if (files.isEmpty()) return
        _error.value = null
        _progress.value = null
        savedThisRun.clear()
        _phase.value = ToolPhase.Running
        runJob = viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            try {
                val outcome = execute(RunScope(this@ToolViewModel))
                val first = files.first()
                val result = ToolResult(
                    output = outcome.output,
                    inputName = first.name,
                    toolCode = toolCode,
                    beforeSize = files.sumOf { it.size.coerceAtLeast(0) }.takeIf { files.all { f -> f.size >= 0 } } ?: -1L,
                    afterSize = outcome.outputs.sumOf { it.size },
                    headline = outcome.headline,
                    detail = outcome.detail,
                    outputs = outcome.outputs,
                )
                runCatching {
                    // A many-file result (Split) adds only its first few files so Recent is not flooded.
                    outcome.outputs.take(MAX_RECENTS_PER_RUN).forEachIndexed { i, o ->
                        app.recents.add(
                            RecentEntry(
                                name = o.displayName, toolCode = toolCode, beforeSize = if (i == 0) result.beforeSize else -1L,
                                afterSize = o.size, timestamp = System.currentTimeMillis(),
                                uri = o.uri.toString(), mime = o.mime,
                            ),
                        )
                    }
                }
                _result.value = result
                _phase.value = ToolPhase.Result
            } catch (e: CancellationException) {
                discardSavedThisRun()
                _phase.value = ToolPhase.Options
                notify("Cancelled")
                throw e
            } catch (t: Throwable) {
                discardSavedThisRun()
                _error.value = t.friendlyMessage()
                _phase.value = ToolPhase.Failed
            }
        }
    }

    internal fun noteSaved(output: SavedOutput) { savedThisRun += output }

    /** A run that fails or is cancelled after saving some files (Split) must not leave half a result behind. */
    private suspend fun discardSavedThisRun() {
        val files = savedThisRun.toList()
        savedThisRun.clear()
        if (files.isEmpty()) return
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { files.forEach { runCatching { saver.delete(it.uri) } } }
    }

    /** Cooperative cancel: the running coroutine stops at its next [RunScope.checkCancelled]. */
    fun cancel() { runJob?.cancel() }

    /** Failed -> run again with the same inputs and options. */
    fun retry() = start()

    /** Failed/Result -> back to Options with inputs kept. */
    fun backToOptions() {
        _error.value = null
        _result.value = null
        if (_inputs.value.isNotEmpty()) _phase.value = ToolPhase.Options else _phase.value = ToolPhase.Pick
    }

    override fun onCleared() {
        runJob?.cancel()
        inputJob?.cancel()
        onInputsReset()
    }

    /** Shared helper so subclasses can switch context for blocking work. */
    protected suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private const val KEY_INPUTS = "forge.inputs"
        private const val MAX_RECENTS_PER_RUN = 3
    }
}
