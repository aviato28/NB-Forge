package com.namanbansal.forge.tools.image

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.core.FileActions
import com.namanbansal.forge.core.FileResolver
import com.namanbansal.forge.core.SavedOutput
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.core.image.Base64Image
import com.namanbansal.forge.core.image.ImageIO
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.TopBar
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.rememberSnack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** I11 Image <-> Base64. The (possibly huge) pasted text is kept in a cache file so it survives process death without overflowing the saved-state bundle. */
class Base64ViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val app get() = getApplication<ForgeApp>()
    private val draft = File(app.cacheDir, "base64-draft.txt")

    val tab = MutableStateFlow(saved.get<Int>("b64.tab") ?: 0)
    fun setTab(i: Int) { tab.value = i; saved["b64.tab"] = i }

    // ---- encode
    data class Encoded(val name: String, val mime: String, val bytes: Long, val dataUri: String, val thumb: Bitmap?)
    private val _encoded = MutableStateFlow<Encoded?>(null)
    val encoded: StateFlow<Encoded?> = _encoded.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        val handed = app.pendingFiles.take("I11")?.firstOrNull()?.uri
        (handed ?: saved.get<String>("b64.uri")?.let { Uri.parse(it) })?.let { encode(it) }
    }

    fun encode(uri: Uri) {
        saved["b64.uri"] = uri.toString()
        viewModelScope.launch {
            _busy.value = true; _error.value = null
            try {
                _encoded.value = withContext(Dispatchers.IO) {
                    val f = FileResolver.resolveBlocking(app, uri)
                    val bytes = ImageIO.readBytes(app, uri)
                    val mime = Base64Image.sniffMime(bytes) ?: f.mime?.takeIf { it.startsWith("image/") } ?: throw ToolException("That file isn't a picture.")
                    val thumb = runCatching { ImageIO.decode(app, uri, 400) }.getOrNull()
                    Encoded(f.name, mime, bytes.size.toLong(), Base64Image.encode(bytes, mime), thumb)
                }
            } catch (e: ToolException) { _error.value = e.message } catch (e: OutOfMemoryError) { _error.value = "That picture is too large to encode." } catch (e: Exception) { _error.value = "Couldn't read that picture." }
            finally { _busy.value = false }
        }
    }

    // ---- decode
    private val _text = MutableStateFlow(runCatching { draft.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty())
    val text: StateFlow<String> = _text.asStateFlow()
    private val _decoded = MutableStateFlow<Pair<Base64Image.Decoded, Bitmap?>?>(null)
    val decoded: StateFlow<Pair<Base64Image.Decoded, Bitmap?>?> = _decoded.asStateFlow()
    private val _decodeError = MutableStateFlow<String?>(null)
    val decodeError: StateFlow<String?> = _decodeError.asStateFlow()
    private val _savedOut = MutableStateFlow<SavedOutput?>(null)
    val savedOut: StateFlow<SavedOutput?> = _savedOut.asStateFlow()
    private var decodeJob: Job? = null

    init { if (_text.value.isNotBlank()) decodeNow() }

    fun setText(t: String) {
        _text.value = t; _savedOut.value = null
        viewModelScope.launch(Dispatchers.IO) { runCatching { if (t.isEmpty()) draft.delete() else draft.writeText(t) } }
        decodeJob?.cancel()
        decodeJob = viewModelScope.launch(Dispatchers.Default) { delay(250); decodeNow() }
    }

    private fun decodeNow() {
        decodeJob?.cancel()
        decodeJob = viewModelScope.launch {
            val t = _text.value
            if (t.isBlank()) { _decoded.value = null; _decodeError.value = null; return@launch }
            withContext(Dispatchers.Default) {
                try {
                    val d = Base64Image.decode(t)
                    val bmp = if (d.mime == "image/svg+xml") ImageIO.renderSvgBytes(d.bytes, 512)
                    else ImageIO.decode(d.mime, { java.io.ByteArrayInputStream(d.bytes) }, 1200)
                    _decoded.value = d to bmp; _decodeError.value = null
                } catch (e: ToolException) { _decoded.value = null; _decodeError.value = e.message }
                catch (e: Exception) { _decoded.value = null; _decodeError.value = "The data decoded, but the picture inside is damaged." }
            }
        }
    }

    fun saveImage(onDone: (String) -> Unit) {
        val d = _decoded.value?.first ?: return
        viewModelScope.launch {
            try {
                _savedOut.value = app.outputSaver.save("decoded", "image", Base64Image.extFor(d.mime), d.mime, images = true) { it.write(d.bytes) }
                onDone("Saved to Pictures/Forge")
            } catch (e: Exception) { onDone("Couldn't save the picture") }
        }
    }

    fun saveText(onDone: (String) -> Unit) {
        val e = _encoded.value ?: return
        viewModelScope.launch {
            try {
                val o = app.outputSaver.save(e.name, "base64", "txt", "text/plain") { it.write(e.dataUri.toByteArray()) }
                onDone("Saved ${o.displayName} to ${o.folder}")
            } catch (ex: Exception) { onDone("Couldn't save the text file") }
        }
    }
}

private const val COPY_LIMIT = 400_000

@Composable
fun Base64Screen(host: ToolHost, vm: Base64ViewModel = viewModel()) {
    val c = ForgeTheme.colors
    val tab by vm.tab.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snack = rememberSnack()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.encode(uri) }
    Column(Modifier.fillMaxSize()) {
        TopBar(host.tool.name, onBack = host.onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Segmented(listOf("Image to Base64", "Base64 to image"), tab, { vm.setTab(it) })
            if (tab == 0) {
                val enc by vm.encoded.collectAsStateWithLifecycle()
                val busy by vm.busy.collectAsStateWithLifecycle()
                val err by vm.error.collectAsStateWithLifecycle()
                ForgeButton(if (busy) "Encoding…" else if (enc == null) "Choose a picture" else "Choose another picture", { picker.launch(arrayOf("image/*")) }, icon = ForgeIcons.Image, enabled = !busy, modifier = Modifier.fillMaxWidth())
                err?.let { InfoNote(it) }
                enc?.let { e ->
                    ImagePreview(e.thumb, Modifier.fillMaxWidth().height(160.dp))
                    Text("${e.name} · ${e.mime} · ${formatBytes(e.bytes)} → ${formatBytes(e.dataUri.length.toLong())} as text", style = ForgeType.Small, color = c.text2)
                    val tooLong = e.dataUri.length > COPY_LIMIT
                    OutlinedTextField(
                        value = if (tooLong) e.dataUri.take(600) + "…" else e.dataUri, onValueChange = {}, readOnly = true, minLines = 4, maxLines = 6,
                        modifier = Modifier.fillMaxWidth(), shape = ForgeShapes.Button, textStyle = ForgeType.Small,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.border, focusedTextColor = c.text, unfocusedTextColor = c.text),
                    )
                    if (tooLong) InfoNote("This is too long to copy or share as plain text. Save it as a .txt file instead.")
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ForgeButton("Copy", {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("data URI", e.dataUri)); snack("Copied")
                        }, icon = ForgeIcons.Copy, modifier = Modifier.weight(1f))
                        ForgeButton("Share text", {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, e.dataUri) }
                            runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share Base64").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { snack("No app available to share") }
                        }, icon = ForgeIcons.Share, secondary = true, modifier = Modifier.weight(1f))
                    }
                    ForgeButton("Save as .txt file", { vm.saveText(snack) }, icon = ForgeIcons.Download, secondary = true, modifier = Modifier.fillMaxWidth())
                }
                if (enc == null && !busy) InfoNote("Turns a picture into a data URI (data:image/png;base64,...) you can paste into HTML, CSS or JSON.")
            } else {
                val text by vm.text.collectAsStateWithLifecycle()
                val dec by vm.decoded.collectAsStateWithLifecycle()
                val derr by vm.decodeError.collectAsStateWithLifecycle()
                val out by vm.savedOut.collectAsStateWithLifecycle()
                OutlinedTextField(
                    value = text, onValueChange = { vm.setText(it) }, minLines = 5, maxLines = 8, label = { Text("Paste Base64 or a data URI") },
                    modifier = Modifier.fillMaxWidth(), shape = ForgeShapes.Button, textStyle = ForgeType.Small,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.border, focusedTextColor = c.text, unfocusedTextColor = c.text, focusedLabelColor = c.accent),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeButton("Paste", {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (clip.isNullOrBlank()) snack("Nothing to paste") else vm.setText(clip)
                    }, secondary = true, icon = ForgeIcons.Copy, modifier = Modifier.weight(1f))
                    ForgeButton("Clear", { vm.setText("") }, secondary = true, enabled = text.isNotEmpty(), modifier = Modifier.weight(1f))
                }
                derr?.let { InfoNote(it) }
                dec?.let { (d, bmp) ->
                    ImagePreview(bmp, Modifier.fillMaxWidth().height(200.dp))
                    Text("${d.mime} · ${formatBytes(d.bytes.size.toLong())}" + (bmp?.let { " · ${it.width} × ${it.height} px" } ?: ""), style = ForgeType.Small, color = c.text2)
                    if (out == null) ForgeButton("Save image", { vm.saveImage(snack) }, icon = ForgeIcons.Save, modifier = Modifier.fillMaxWidth())
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ForgeButton("Share", { FileActions.share(context, out!!.uri, out!!.mime, out!!.displayName) }, icon = ForgeIcons.Share, modifier = Modifier.weight(1f))
                        ForgeButton("Open", { if (!FileActions.open(context, out!!.uri, out!!.mime)) snack("No app found to open this") }, icon = ForgeIcons.Open, secondary = true, modifier = Modifier.weight(1f))
                    }
                    out?.let { Text("Saved as ${it.displayName} in ${it.folder}", style = ForgeType.Small, color = c.text2) }
                }
            }
        }
    }
}
