package com.namanbansal.forge.tools.pdf

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.pdf.PdfMeta
import com.namanbansal.forge.core.pdf.PdfMetadataEditor
import com.namanbansal.forge.core.pdf.PdfOps
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfTransformViewModel
import com.namanbansal.forge.core.pdf.TransformCtx
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.toolkit.PdfToolScreen
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** P23 Metadata: Info dictionary and XMP are written together; "Remove all" clears both. */
class MetadataViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P23"
    override val action = "metadata"
    override val headline get() = if (removeAllState.value) "Metadata removed" else "Metadata updated"

    private val titleS = persistedString("meta.title", ""); private val authorS = persistedString("meta.author", "")
    private val subjectS = persistedString("meta.subject", ""); private val keywordsS = persistedString("meta.keywords", "")
    private val creatorS = persistedString("meta.creator", ""); private val producerS = persistedString("meta.producer", "")
    private val createdS = persistedString("meta.created", ""); private val modifiedS = persistedString("meta.modified", "")
    private val removeAllState = persistedBool("meta.removeAll", false)
    private val loadedState = persistedBool("meta.loaded", false)
    val title: StateFlow<String> = titleS.flow; val author: StateFlow<String> = authorS.flow
    val subject: StateFlow<String> = subjectS.flow; val keywords: StateFlow<String> = keywordsS.flow
    val creator: StateFlow<String> = creatorS.flow; val producer: StateFlow<String> = producerS.flow
    val created: StateFlow<String> = createdS.flow; val modified: StateFlow<String> = modifiedS.flow
    val removeAll: StateFlow<Boolean> = removeAllState.flow
    val loaded: StateFlow<Boolean> = loadedState.flow

    fun set(field: String, v: String) {
        when (field) {
            "title" -> titleS.value = v; "author" -> authorS.value = v; "subject" -> subjectS.value = v
            "keywords" -> keywordsS.value = v; "creator" -> creatorS.value = v; "producer" -> producerS.value = v
            "created" -> createdS.value = v; "modified" -> modifiedS.value = v
        }
    }
    fun setRemoveAll(v: Boolean) { removeAllState.value = v }

    fun datesValid() = PdfMetadataEditor.isValidDate(createdS.value) && PdfMetadataEditor.isValidDate(modifiedS.value)

    private fun current() = PdfMeta(titleS.value, authorS.value, subjectS.value, keywordsS.value, creatorS.value, producerS.value, createdS.value, modifiedS.value)

    override fun onPdfReady(source: PdfSource) {
        if (stateRestored && loadedState.value) return
        viewModelScope.launch {
            val m = withContext(Dispatchers.IO) { runCatching { PdfOps.load(source.file).use { PdfMetadataEditor.read(it) } }.getOrDefault(PdfMeta()) }
            titleS.value = m.title; authorS.value = m.author; subjectS.value = m.subject; keywordsS.value = m.keywords
            creatorS.value = m.creator; producerS.value = m.producer; createdS.value = m.created; modifiedS.value = m.modified
            loadedState.value = true
        }
    }

    override fun validate(source: PdfSource) {
        if (!removeAllState.value && !datesValid()) throw ToolException("Dates must look like 2026-11-13 or 2026-11-13 09:30.")
    }

    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String? {
        return if (removeAllState.value) { PdfMetadataEditor.removeAll(doc); "Info and XMP cleared" }
        else { PdfMetadataEditor.write(doc, current()); "Info and XMP updated together" }
    }
}

@Composable
fun MetadataScreen(host: ToolHost, vm: MetadataViewModel = viewModel()) {
    val title by vm.title.collectAsStateWithLifecycle(); val author by vm.author.collectAsStateWithLifecycle()
    val subject by vm.subject.collectAsStateWithLifecycle(); val keywords by vm.keywords.collectAsStateWithLifecycle()
    val creator by vm.creator.collectAsStateWithLifecycle(); val producer by vm.producer.collectAsStateWithLifecycle()
    val created by vm.created.collectAsStateWithLifecycle(); val modified by vm.modified.collectAsStateWithLifecycle()
    val removeAll by vm.removeAll.collectAsStateWithLifecycle(); val loaded by vm.loaded.collectAsStateWithLifecycle()
    val datesOk = PdfMetadataEditor.isValidDate(created) && PdfMetadataEditor.isValidDate(modified)
    PdfToolScreen(
        host, vm, "Choose a PDF to edit", if (removeAll) "Remove all metadata" else "Save",
        actionEnabled = loaded && (removeAll || datesOk), actionHint = if (!removeAll && !datesOk) "Fix the dates to save" else null,
    ) { _, _ ->
        ToggleRow("Remove all metadata", removeAll, vm::setRemoveAll, subtitle = "Clears title, author, dates and the hidden XMP packet.")
        if (!removeAll) {
            Column(Modifier.padding(top = 12.dp)) {
                @Composable fun f(label: String, v: String, key: String, err: String? = null, kt: KeyboardType = KeyboardType.Text) =
                    ForgeTextField(v, { vm.set(key, it) }, Modifier.padding(bottom = 4.dp), label = label, error = err, keyboardType = kt)
                f("Title", title, "title"); f("Author", author, "author"); f("Subject", subject, "subject")
                f("Keywords", keywords, "keywords"); f("Creator", creator, "creator"); f("Producer", producer, "producer")
                f("Created", created, "created", if (PdfMetadataEditor.isValidDate(created)) null else "Use yyyy-MM-dd HH:mm")
                f("Modified", modified, "modified", if (PdfMetadataEditor.isValidDate(modified)) null else "Use yyyy-MM-dd HH:mm")
                InfoNote("Title, author, subject, keywords and dates are written to both the document info and the XMP packet, so every viewer shows the same values.", Modifier.padding(top = 4.dp))
            }
        } else InfoNote("The saved copy will carry no title, author, dates or XMP packet.", Modifier.padding(top = 12.dp))
    }
}
