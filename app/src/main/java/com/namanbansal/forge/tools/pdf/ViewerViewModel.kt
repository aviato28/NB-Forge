package com.namanbansal.forge.tools.pdf

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfTextSearch
import com.namanbansal.forge.core.pdf.PdfToolViewModel
import com.namanbansal.forge.core.pdf.TextHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P40 Viewer: shows any PDF, keeps where you were (page, dark mode) across process death, and runs text search
 * ([PdfTextSearch]) in the background with progressive results.
 */
class ViewerViewModel(application: Application, saved: SavedStateHandle) : PdfToolViewModel(application, saved) {

    override val toolCode = "P40"

    // Cache big enough for a couple of full-resolution pages while zoomed.
    override val thumbnailCacheBytes: Int get() = (Runtime.getRuntime().maxMemory() / 6).coerceIn(16L shl 20, 96L shl 20).toInt()

    private val pageState = persistedInt("viewer.page", 0)
    private val darkState = persistedBool("viewer.dark", false)
    private val zoomState = persisted("viewer.zoom", 1f, decode = { it as? Float })
    /** Page to show first (0-based); restored after process death. */
    val lastPage: StateFlow<Int> = pageState.flow
    val invert: StateFlow<Boolean> = darkState.flow
    val zoom: StateFlow<Float> = zoomState.flow
    /** One-off reads of the saved position when the screen opens. */
    fun startPage(): Int = pageState.value
    fun startZoom(): Float = zoomState.value
    fun rememberPage(p: Int) { pageState.value = p }
    fun rememberZoom(z: Float) { zoomState.value = z }
    fun toggleInvert() { darkState.value = !darkState.value }

    // ---- page sizes (so scrolling a long document does not jump) ---------------------------------------------------

    private val _aspects = MutableStateFlow<FloatArray?>(null)
    /** width / height of every page once known (starts as the first page's ratio for all pages). */
    val aspects: StateFlow<FloatArray?> = _aspects.asStateFlow()
    private var aspectJob: Job? = null

    override fun onPdfReady(source: PdfSource) {
        val thumbs = thumbnails.value ?: return
        _aspects.value = null
        aspectJob?.cancel()
        aspectJob = viewModelScope.launch {
            val n = thumbs.pageCount
            val out = FloatArray(n) { 0.707f }
            val first = thumbs.pageSize(0)
            if (first != null) java.util.Arrays.fill(out, first.first.toFloat() / first.second.coerceAtLeast(1))
            _aspects.value = out.copyOf()
            var i = 0
            while (i < n) {
                ensureActive()
                thumbs.pageSize(i)?.let { out[i] = it.first.toFloat() / it.second.coerceAtLeast(1) }
                i++
                if (i % 24 == 0 || i == n) _aspects.value = out.copyOf()
            }
        }
    }

    // ---- search -------------------------------------------------------------------------------------------------------

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _hits = MutableStateFlow<List<TextHit>>(emptyList())
    val hits: StateFlow<List<TextHit>> = _hits.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _current = MutableStateFlow(-1)
    /** Index into [hits] of the highlighted hit, -1 for none. */
    val current: StateFlow<Int> = _current.asStateFlow()
    /** Bumped whenever the screen should scroll to [current]'s page (next/previous/tap). */
    private val _jump = MutableStateFlow(0)
    val jump: StateFlow<Int> = _jump.asStateFlow()

    private var search: PdfTextSearch? = null
    private var searchJob: Job? = null

    /** Runs a search; an empty query clears the results. Results arrive page by page. */
    fun search(text: String) {
        _query.value = text
        searchJob?.cancel()
        _hits.value = emptyList(); _current.value = -1
        if (text.isBlank()) { _searching.value = false; return }
        val file = source.value?.file ?: return
        _searching.value = true
        searchJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val engine = search ?: PdfTextSearch(file).also { search = it }
                    val ctx = currentCoroutineContext()
                    engine.search(text, checkCancelled = { ctx.ensureActive() }) { found ->
                        _hits.update { it + found }
                        if (_current.value < 0) { _current.value = 0; _jump.update { it + 1 } }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notify("Search stopped: this PDF's text couldn't be read.")
            } finally {
                _searching.value = false
            }
        }
    }

    fun clearSearch() = search("")

    fun nextHit() = step(1)
    fun previousHit() = step(-1)
    private fun step(d: Int) {
        val n = _hits.value.size
        if (n == 0) return
        _current.value = ((_current.value + d) % n + n) % n
        _jump.update { it + 1 }
    }

    fun openHit(index: Int) {
        if (index !in _hits.value.indices) return
        _current.value = index
        _jump.update { it + 1 }
    }

    override fun onInputsReset() {
        searchJob?.cancel(); aspectJob?.cancel()
        runCatching { search?.close() }; search = null
        _hits.value = emptyList(); _current.value = -1; _query.value = ""; _searching.value = false; _aspects.value = null
        super.onInputsReset()
    }

    /** The viewer reads files; it never produces one. */
    override suspend fun execute(scope: RunScope): ToolOutcome = throw ToolException("The viewer doesn't save files.")
}
