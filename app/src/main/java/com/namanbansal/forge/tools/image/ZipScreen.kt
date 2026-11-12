package com.namanbansal.forge.tools.image

import android.app.Application
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.OutputNaming
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.image.ZipItem
import com.namanbansal.forge.core.image.ZipTools
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** F1 Zip / unzip. One picked .zip is listed and extracted (to Downloads/Forge/<name>/); anything else is zipped. */
class ZipViewModel(application: Application, saved: SavedStateHandle) : ToolViewModel(application, saved) {
    override val toolCode = "F1"

    private val modeS = persistedString("zp.mode", "create")
    private val nameS = persistedString("zp.name", "")
    private val selectedS = persistedIntSet("zp.sel")
    private val allSelected = persistedBool("zp.all", true)
    val mode: StateFlow<String> = modeS.flow
    val name: StateFlow<String> = nameS.flow
    val selected: StateFlow<Set<Int>> = selectedS.flow

    private val _entries = MutableStateFlow<List<ZipItem>>(emptyList())
    val entries: StateFlow<List<ZipItem>> = _entries.asStateFlow()
    private var working: File? = null

    val canExtract: Boolean get() = _entries.value.isNotEmpty()
    fun setMode(m: String) { modeS.value = m }
    fun setName(n: String) { nameS.value = n }
    fun toggle(i: Int) { allSelected.value = false; selectedS.update { if (i in it) it - i else it + i } }
    fun selectAll() { allSelected.value = true; selectedS.value = _entries.value.filter { !it.isDir && !it.unsafe }.map { it.index }.toSet() }
    fun selectNone() { allSelected.value = false; selectedS.value = emptySet() }

    override fun onInputsReset() { working?.delete(); working = null; _entries.value = emptyList() }

    override suspend fun onInputsReady(files: List<PickedFile>) {
        val single = files.singleOrNull()
        if (single != null && single.kind == FileKind.ARCHIVE) {
            io {
                // ZipFile needs a seekable file: take a private working copy (also keeps us from reading the picked uri twice).
                val copy = File.createTempFile("forge-zip", ".zip", app.cacheDir)
                try {
                    (app.contentResolver.openInputStream(single.uri) ?: throw ToolException("Couldn't open that zip.")).use { i -> copy.outputStream().use { i.copyTo(it) } }
                    _entries.value = ZipTools.list(copy)
                    working = copy
                } catch (t: Throwable) { copy.delete(); throw t }
            }
            if (!stateRestored) { modeS.value = "extract"; selectAll() }
            else if (allSelected.value) selectAll()
        } else {
            modeS.value = "create"
            if (!stateRestored || nameS.value.isBlank()) nameS.value = if (files.size == 1) OutputNaming.baseName(files.first().name) else "Forge files"
        }
    }

    val isExtract: Boolean get() = modeS.value == "extract" && canExtract

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val ctx = kotlinx.coroutines.currentCoroutineContext()
        val files = inputs.value
        if (!isExtract) {
            val zipName = OutputNaming.baseName(nameS.value.ifBlank { "Forge files" }) + ".zip"
            val saved = scope.saveAs(zipName, "zip", "zip", "application/zip", rawName = zipName) { out ->
                ZipTools.create(
                    out, files.map { f -> f.name to { app.contentResolver.openInputStream(f.uri) } },
                    checkCancelled = { ctx.ensureActive() }, onProgress = { scope.progress(it) },
                )
            }
            return ToolOutcome(saved, "Zip created", "${files.size} file${if (files.size == 1) "" else "s"}")
        }
        val file = working ?: throw ToolException("Open a zip first.")
        val folder = OutputNaming.baseName(files.first().name)
        val items = _entries.value.filter { !it.isDir && it.index in selectedS.value }
        if (items.isEmpty()) throw ToolException("Select at least one file to extract.")
        val outs = ArrayList<com.namanbansal.forge.core.SavedOutput>()
        var skipped = 0
        var budget = 8L shl 30
        items.forEachIndexed { n, item ->
            scope.checkCancelled(); scope.progress(n / items.size.toFloat())
            if (item.unsafe) { skipped++; return@forEachIndexed }
            val parts = item.path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            val leaf = parts.last()
            val dir = (listOf(folder) + parts.dropLast(1).map { OutputNaming.baseName(it + ".x") }).joinToString("/") + "/"
            val ext = leaf.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            outs += scope.saveAs(files.first().name, "x", ext, mime, subDir = dir, rawName = leaf) { out -> budget -= ZipTools.copyEntry(file, item.path, out, budget) }
        }
        if (outs.isEmpty()) throw ToolException("Nothing could be extracted: every entry had an unsafe path.")
        return ToolOutcome(outs.first(), "Extracted ${outs.size} file${if (outs.size == 1) "" else "s"}", "to Downloads/Forge/$folder" + if (skipped > 0) " · $skipped unsafe skipped" else "", outs)
    }
}

@Composable
fun ZipScreen(host: ToolHost, vm: ZipViewModel = viewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val modeName by vm.mode.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val sel by vm.selected.collectAsStateWithLifecycle()
    val extract = modeName == "extract" && entries.isNotEmpty()
    val c = ForgeTheme.colors
    val picked = entries.count { !it.isDir && it.index in sel }
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.OTHER), multiple = true, title = "Choose files to zip, or a .zip to open", hint = "Pick several files to bundle, or one zip to extract."),
        actionLabel = if (extract) "Extract $picked file${if (picked == 1) "" else "s"}" else "Create zip",
        actionEnabled = if (extract) picked > 0 else name.isNotBlank(),
        actionHint = if (extract) "Saved to Downloads/Forge/${com.namanbansal.forge.core.OutputNaming.baseName(inputs.firstOrNull()?.name ?: "archive")}/" else null,
        inputSubtitle = { f -> if (extract) "${entries.count { !it.isDir }} files · ${formatBytes(entries.sumOf { it.size.coerceAtLeast(0) })} unpacked" else "${f.size} file${if (f.size == 1) "" else "s"} · ${formatBytes(f.sumOf { it.size.coerceAtLeast(0) })}" },
    ) {
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entries.isNotEmpty()) Segmented(listOf("Extract", "Zip it again"), if (extract) 0 else 1, { vm.setMode(if (it == 0) "extract" else "create") })
            if (!extract) {
                ForgeTextField(name, { vm.setName(it) }, label = "Zip name", placeholder = "Forge files", helper = "Saved as ${com.namanbansal.forge.core.OutputNaming.baseName(name.ifBlank { "Forge files" })}.zip in Downloads/Forge")
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(inputs) { _, f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(f.name, style = ForgeType.Body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(formatBytes(f.size), style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$picked of ${entries.count { !it.isDir }} selected", style = ForgeType.Label, color = c.text2)
                    Row { TextAction("All", { vm.selectAll() }); TextAction("None", { vm.selectNone() }) }
                }
                if (entries.any { it.unsafe }) InfoNote("Some entries use unsafe paths (like ../) and will be skipped.")
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(entries.filter { !it.isDir }) { _, e ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !e.unsafe) { vm.toggle(e.index) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(e.index in sel && !e.unsafe, { vm.toggle(e.index) }, enabled = !e.unsafe, colors = CheckboxDefaults.colors(checkedColor = c.accent))
                            Column(Modifier.weight(1f)) {
                                Text(e.path, style = ForgeType.Body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (e.unsafe) "Unsafe path, skipped" else formatBytes(e.size), style = ForgeType.Small, color = c.text2)
                            }
                        }
                    }
                }
            }
        }
    }
}
