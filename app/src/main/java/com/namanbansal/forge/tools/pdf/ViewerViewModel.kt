package com.namanbansal.forge.tools.pdf

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.namanbansal.forge.BuildConfig
import com.namanbansal.forge.core.FileResolver
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.friendlyMessage
import com.namanbansal.forge.core.pdf.PageRasterizers
import com.namanbansal.forge.core.pdf.PdfIntake
import com.namanbansal.forge.core.pdf.viewer.DocumentKey
import com.namanbansal.forge.core.pdf.viewer.OutlineItem
import com.namanbansal.forge.core.pdf.viewer.PageLink
import com.namanbansal.forge.core.pdf.viewer.PageText
import com.namanbansal.forge.core.pdf.viewer.PdfDocIndex
import com.namanbansal.forge.core.pdf.viewer.TextSelection
import com.namanbansal.forge.core.pdf.viewer.TileRenderer
import com.namanbansal.forge.core.pdf.viewer.ViewerPosition
import com.namanbansal.forge.data.ViewerPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
import kotlinx.coroutines.sync.withLock
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
    fun rememberPage(p: Int) { if (pageState.value != p) { pageState.value = p; scheduleSave() } }
    fun rememberZoom(z: Float) { if (zoomState.value != z) { zoomState.value = z; scheduleSave() } }
    fun toggleInvert() { darkState.value = !darkState.value }

    // ---- layout settings (global) ----------------------------------------------------------------------------------

    val prefs: StateFlow<ViewerPrefs> = app.settings.settings.map { it.viewer }.stateIn(viewModelScope, SharingStarted.Eagerly, ViewerPrefs())
    fun updatePrefs(change: (ViewerPrefs) -> ViewerPrefs) { viewModelScope.launch { app.settings.setViewerPrefs(change(prefs.value)) } }

    // ---- recents, remembered position -------------------------------------------------------------------------------

    val recents: StateFlow<List<ViewerPosition>> get() = app.viewerPositions.entries
    private val _restored = MutableStateFlow(false)
    /** True once the saved position (if any) has been looked up, so the screen can open at the right page. */
    val restored: StateFlow<Boolean> = _restored.asStateFlow()
    private var docKey: String? = null
    private var saveJob: Job? = null

    /** Pick result: remembers read access (so the file can be reopened from Recent later) and opens it. */
    fun open(uris: List<Uri>) {
        uris.forEach { runCatching { app.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        onUrisPicked(uris)
    }

    /** Reopens a document from the home list; a file that is gone is dropped from the list with a message. */
    fun openRecent(p: ViewerPosition) {
        viewModelScope.launch {
            val uri = Uri.parse(p.uri)
            val ok = withContext(Dispatchers.IO) { runCatching { app.contentResolver.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false) }
            if (!ok) {
                app.viewerPositions.remove(p.key)
                notify("\"${p.name}\" isn't available any more, so it was removed from your recent list.")
                return@launch
            }
            onFilesPicked(listOf(FileResolver.resolve(app, uri)))
        }
    }

    fun removeRecent(p: ViewerPosition) { viewModelScope.launch { app.viewerPositions.remove(p.key) } }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(700); persistPosition() }
    }

    /** Writes the position now (leaving the screen). */
    fun flushPosition() { saveJob?.cancel(); viewModelScope.launch { persistPosition() } }

    private suspend fun persistPosition() {
        val key = docKey ?: return
        val f = inputs.value.firstOrNull() ?: return
        val total = source.value?.pageCount ?: return
        app.viewerPositions.put(ViewerPosition(key, f.uri.toString(), f.name, f.size, total, pageState.value, zoomState.value, System.currentTimeMillis()))
    }

    private suspend fun afterOpen(src: PdfSource) {
        val key = withContext(Dispatchers.IO) { runCatching { DocumentKey.of(src.file) }.getOrNull() }
        docKey = key
        if (key != null && !stateRestored) {
            app.viewerPositions.get(key)?.let {
                pageState.value = it.page.coerceIn(0, (src.pageCount - 1).coerceAtLeast(0))
                zoomState.value = it.zoom.coerceIn(1f, 8f)
            }
        }
        _restored.value = true
        if (key != null) {
            persistPosition()
            val cover = app.viewerPositions.coverFile(key)
            if (!cover.exists()) withContext(Dispatchers.IO) {
                val bmp = PdfIntake.cover(src.file) ?: return@withContext
                runCatching { cover.parentFile?.mkdirs(); cover.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) } }
            }
        }
    }

    // ---- tiles, text, links, outline ---------------------------------------------------------------------------------

    private val _tiles = MutableStateFlow<TileRenderer?>(null)
    /** Renders the sharp tiles of the visible region; null until a document is open. */
    val tiles: StateFlow<TileRenderer?> = _tiles.asStateFlow()

    private var docIndex: PdfDocIndex? = null
    private val indexLock = kotlinx.coroutines.sync.Mutex()
    private suspend fun index(): PdfDocIndex? = indexLock.withLock {
        docIndex ?: source.value?.file?.let { f -> withContext(Dispatchers.IO) { runCatching { PdfDocIndex(f) }.getOrNull() } }?.also { docIndex = it }
    }

    suspend fun pageText(page: Int): PageText? = index()?.let { withContext(Dispatchers.IO) { it.text(page) } }
    suspend fun pageLinks(page: Int): List<PageLink> = index()?.let { withContext(Dispatchers.IO) { it.links(page) } }.orEmpty()

    private val _outline = MutableStateFlow<List<OutlineItem>?>(null)
    /** Bookmarks of the document; null while still reading them. */
    val outline: StateFlow<List<OutlineItem>?> = _outline.asStateFlow()
    fun loadOutline() {
        if (_outline.value != null) return
        viewModelScope.launch { _outline.value = index()?.let { withContext(Dispatchers.IO) { it.outline() } }.orEmpty() }
    }

    private val _flash = MutableStateFlow<LinkFlash?>(null)
    /** The link that was just tapped (drives the ripple). */
    val flash: StateFlow<LinkFlash?> = _flash.asStateFlow()
    private var flashId = 0
    fun flashLink(page: Int, link: PageLink) { _flash.value = LinkFlash(page, link.rect, ++flashId) }

    private val _selection = MutableStateFlow<TextSelection?>(null)
    val selection: StateFlow<TextSelection?> = _selection.asStateFlow()
    fun setSelection(s: TextSelection?) { _selection.value = s?.takeIf { !it.isEmpty } }
    fun clearSelection() { _selection.value = null }

    /** Long-press: selects the word at ([nx], [ny]) of [page]. Returns false when there is no text there. */
    suspend fun selectWordAt(page: Int, nx: Float, ny: Float): Boolean {
        val t = pageText(page) ?: return false
        val w = t.wordAt(t.charAt(nx, ny)) ?: return false
        _selection.value = TextSelection(page, w.first, w.last + 1)
        return true
    }

    suspend fun selectAll(page: Int) {
        val t = pageText(page) ?: return
        val b = t.bounds() ?: return
        _selection.value = TextSelection(page, b.first, b.last + 1)
    }

    suspend fun selectedText(): String {
        val s = _selection.value ?: return ""
        return pageText(s.page)?.substring(s.start, s.end).orEmpty()
    }

    // ---- opening problems ---------------------------------------------------------------------------------------------

    /** Why the last open failed, plus text for the "Report" button. */
    data class OpenError(val message: String, val report: String)
    private val _openError = MutableStateFlow<OpenError?>(null)
    val openError: StateFlow<OpenError?> = _openError.asStateFlow()
    fun dismissError() { _openError.value = null }

    override suspend fun onInputsReady(files: List<PickedFile>) {
        _openError.value = null
        try {
            super.onInputsReady(files)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _openError.value = OpenError(t.friendlyMessage(), report(files.firstOrNull(), t))
            throw t
        }
    }

    private fun report(f: PickedFile?, t: Throwable): String = buildString {
        appendLine("Forge ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) viewer problem")
        appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        if (f != null) {
            appendLine("File: ${f.name}, ${if (f.size >= 0) "${f.size} bytes" else "unknown size"}, type ${f.mime ?: "unknown"}")
            appendLine("Source: ${f.uri.scheme}://${f.uri.authority.orEmpty()}")
        }
        append("Error: ${t.javaClass.name}: ${t.message}")
    }

    // ---- page sizes (so scrolling a long document does not jump) ---------------------------------------------------

    private val _aspects = MutableStateFlow<FloatArray?>(null)
    /** width / height of every page once known (starts as the first page's ratio for all pages). */
    val aspects: StateFlow<FloatArray?> = _aspects.asStateFlow()
    private var aspectJob: Job? = null

    override fun onPdfReady(source: PdfSource) {
        val thumbs = thumbnails.value ?: return
        _restored.value = false
        _tiles.value?.close()
        _tiles.value = runCatching { TileRenderer(PageRasterizers.factory(source.file), focusPage = { pageState.value }) }.getOrNull()
        viewModelScope.launch { afterOpen(source) }
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
        searchJob?.cancel(); aspectJob?.cancel(); saveJob?.cancel()
        _tiles.value?.close(); _tiles.value = null
        runCatching { docIndex?.close() }; docIndex = null
        _outline.value = null; _selection.value = null; _restored.value = false; docKey = null
        runCatching { search?.close() }; search = null
        _hits.value = emptyList(); _current.value = -1; _query.value = ""; _searching.value = false; _aspects.value = null
        super.onInputsReset()
    }

    /** The viewer reads files; it never produces one. */
    override suspend fun execute(scope: RunScope): ToolOutcome = throw ToolException("The viewer doesn't save files.")
}
