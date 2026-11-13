package com.namanbansal.forge.tools.pdf

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.PdfOps
import com.namanbansal.forge.core.pdf.PdfSanitizer
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfTransformViewModel
import com.namanbansal.forge.core.pdf.SanitizeKind
import com.namanbansal.forge.core.pdf.TransformCtx
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PdfToolScreen
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** P13 Sanitise: scan first, show what was found as a checklist, remove what is ticked. */
class SanitizeViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P13"
    override val action = "sanitised"
    override val headline get() = "Sanitised"

    private val chosenS = persistedIntSet("san.kinds")
    val chosen: StateFlow<Set<Int>> = chosenS.flow
    private val _found = MutableStateFlow<Map<SanitizeKind, Int>?>(null)
    val found: StateFlow<Map<SanitizeKind, Int>?> = _found.asStateFlow()

    fun toggle(k: SanitizeKind) { chosenS.update { if (k.ordinal in it) it - k.ordinal else it + k.ordinal } }

    override fun onPdfReady(source: PdfSource) {
        super.onPdfReady(source)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { PdfOps.load(source.file).use { PdfSanitizer.scan(it) } }.getOrNull() }
            _found.value = r
            if (r != null && !stateRestored) chosenS.value = r.filter { it.value > 0 }.keys.map { it.ordinal }.toSet()
        }
    }

    override fun onInputsReset() { _found.value = null; super.onInputsReset() }

    private fun kinds() = chosenS.value.mapNotNull { SanitizeKind.entries.getOrNull(it) }.toSet()

    override fun validate(source: PdfSource) {
        val f = _found.value
        if (kinds().none { (f?.get(it) ?: 1) > 0 }) throw ToolException("Tick at least one item that was found.")
    }

    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String {
        val removed = PdfSanitizer.clean(doc, kinds(), ctx)
        return removed.filter { it.value > 0 }.entries.joinToString(" · ") { "${it.value} ${it.key.label.lowercase()}" }.ifBlank { "Nothing needed removing" }
    }
}

@Composable
fun SanitizeScreen(host: ToolHost, vm: SanitizeViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val found by vm.found.collectAsStateWithLifecycle(); val chosen by vm.chosen.collectAsStateWithLifecycle()
    val total = found?.values?.sum() ?: 0
    val any = found?.any { (k, n) -> n > 0 && k.ordinal in chosen } == true
    PdfToolScreen(host, vm, "Choose a PDF to clean", "Remove selected", actionEnabled = any, actionHint = if (found != null && total == 0) "Nothing to remove" else if (found != null && !any) "Tick at least one item" else null) { _, _ ->
        val f = found
        if (f == null) Text("Looking inside the file…", style = ForgeType.Body, color = c.text2)
        else {
            Text(
                if (total == 0) "Nothing risky found. This PDF is already clean." else "Found in this PDF. Tick what to remove:",
                style = ForgeType.Section, color = c.text, modifier = Modifier.padding(bottom = 8.dp),
            )
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                for (k in SanitizeKind.entries) {
                    val n = f[k] ?: 0
                    ToggleRow(
                        title = k.label + if (n > 0) " ($n)" else "", checked = n > 0 && k.ordinal in chosen,
                        onChange = { if (n > 0) vm.toggle(k) },
                        subtitle = if (n > 0) k.explain else "None found",
                        modifier = Modifier.padding(0.dp),
                    )
                }
            }
            InfoNote("Visible text, pictures and form fields are kept. Removing hidden layers deletes their content for good, and removing metadata can't be undone on the saved copy (your original is untouched).", Modifier.padding(top = 12.dp))
        }
    }
}
