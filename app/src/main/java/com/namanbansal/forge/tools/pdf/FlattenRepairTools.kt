package com.namanbansal.forge.tools.pdf

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.pagesLabel
import com.namanbansal.forge.core.pdf.PdfFlattener
import com.namanbansal.forge.core.pdf.PdfLoader
import com.namanbansal.forge.core.pdf.PdfOps
import com.namanbansal.forge.core.pdf.PdfRepair
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfTransformViewModel
import com.namanbansal.forge.core.pdf.TransformCtx
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.toolkit.PdfToolScreen
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** P11 Flatten: forms, annotations or both go into the page content. */
class FlattenViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P11"
    override val action = "flattened"
    override val headline get() = "Flattened"

    private val modeState = persistedInt("flatten.mode", 2) // 0 forms, 1 annotations, 2 both
    val mode: StateFlow<Int> = modeState.flow
    private val _counts = MutableStateFlow<PdfFlattener.Counts?>(null)
    val counts: StateFlow<PdfFlattener.Counts?> = _counts.asStateFlow()

    fun setMode(m: Int) { modeState.value = m }

    override fun onPdfReady(source: PdfSource) {
        viewModelScope.launch {
            _counts.value = withContext(Dispatchers.IO) { runCatching { PdfOps.load(source.file).use { PdfFlattener.count(it) } }.getOrNull() }
        }
    }

    override fun validate(source: PdfSource) {
        val c = _counts.value ?: return
        val m = modeState.value
        val n = (if (m != 1) c.fields else 0) + (if (m != 0) c.annotations else 0)
        if (n == 0) throw ToolException("There is nothing to flatten with this choice.")
    }

    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String {
        val m = modeState.value
        val r = PdfFlattener.flatten(doc, forms = m != 1, annots = m != 0, ctx = ctx)
        return buildString {
            append("${r.fields} form ${if (r.fields == 1) "field" else "fields"} and ${r.annotations} ${if (r.annotations == 1) "annotation" else "annotations"} baked in")
            if (r.dropped > 0) append("; ${r.dropped} had no appearance and were removed")
        }
    }
}

@Composable
fun FlattenScreen(host: ToolHost, vm: FlattenViewModel = viewModel()) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val n = counts?.let { (if (mode != 1) it.fields else 0) + (if (mode != 0) it.annotations else 0) }
    PdfToolScreen(
        host, vm, "Choose a PDF to flatten", "Flatten", actionEnabled = n == null || n > 0,
        actionHint = if (n == 0) "Nothing to flatten with this choice" else null,
    ) { _, _ ->
        Segmented(listOf("Forms", "Annotations", "Both"), mode, vm::setMode)
        val c = counts
        InfoNote(
            if (c == null) "Looking for form fields and annotations…"
            else "Found ${c.fields} form ${if (c.fields == 1) "field" else "fields"} and ${c.annotations} ${if (c.annotations == 1) "annotation" else "annotations"} (links stay clickable).",
            Modifier.padding(top = 12.dp),
        )
        InfoNote("Flattened content looks the same but can no longer be edited, filled or removed.", Modifier.padding(top = 8.dp))
    }
}

/** P12 Repair: works on files that normal tools reject, so it has its own loader instead of [PdfTransformViewModel]. */
class RepairViewModel(application: Application, saved: SavedStateHandle) : ToolViewModel(application, saved) {
    override val toolCode = "P12"
    private var work: File? = null
    private val _diagnosis = MutableStateFlow<String?>(null)
    val diagnosis: StateFlow<String?> = _diagnosis.asStateFlow()

    override suspend fun onInputsReady(files: List<PickedFile>) {
        val copy = PdfLoader.copyToWork(app, files.first().uri)
        work = copy
        val d = io { PdfRepair.diagnose(copy) }
        _diagnosis.value = if (d.missingEof || d.missingStartXref) "This file looks cut short or its index is damaged. Repair will try to rebuild it." else "The file ends normally. Repair rewrites it cleanly and drops any pages that can't be read."
    }

    override fun onInputsReset() { work?.delete(); work = null; _diagnosis.value = null; super.onInputsReset() }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val f = work ?: throw ToolException("Choose a PDF first.")
        var detail = ""
        val saved = scope.save("repaired", "pdf", "application/pdf") { out ->
            val ctx = currentCoroutineContext()
            withContext(Dispatchers.IO) {
                PdfRepair.open(f).use { doc ->
                    detail = PdfRepair.fix(doc, f, TransformCtx({ ctx.ensureActive() }, { scope.progress(it) }))
                    doc.save(out)
                }
            }
        }
        return ToolOutcome(saved, "Repaired", detail)
    }
}

@Composable
fun RepairScreen(host: ToolHost, vm: RepairViewModel = viewModel()) {
    val diag by vm.diagnosis.collectAsStateWithLifecycle()
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.PDF), title = "Choose a damaged PDF to repair"),
        actionLabel = "Repair", optionsReady = diag != null,
    ) {
        InfoNote(diag.orEmpty())
        InfoNote("If nothing can be recovered you'll be told, and your original is never touched. Password-protected files must be unlocked first.", Modifier.padding(top = 8.dp))
    }
}
