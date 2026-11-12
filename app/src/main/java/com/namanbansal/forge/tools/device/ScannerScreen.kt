package com.namanbansal.forge.tools.device

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ExperimentalGetImage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.device.CameraSession
import com.namanbansal.forge.core.device.FrameAnalyzer
import com.namanbansal.forge.core.device.LocalBarcodeDecoder
import com.namanbansal.forge.core.device.LocalCameraBackend
import com.namanbansal.forge.core.device.MlKitBarcodeDecoder
import com.namanbansal.forge.core.device.ScanActions
import com.namanbansal.forge.core.device.ScanContent
import com.namanbansal.forge.core.device.ScanEntry
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.components.Tag
import com.namanbansal.forge.ui.components.TextAction
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.RoundIconButton
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.Perms
import com.namanbansal.forge.ui.toolkit.PermissionGate
import com.namanbansal.forge.ui.toolkit.ToolFrame
import com.namanbansal.forge.ui.toolkit.rememberHaptics
import com.namanbansal.forge.ui.toolkit.rememberSnack
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** D8: QR / barcode scanner (ML Kit, bundled model) with typed results, history and scan-from-gallery. */
@OptIn(ExperimentalGetImage::class)
@Composable
fun ScannerScreen(host: ToolHost, vm: ScannerViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = rememberSnack()
    val haptics = rememberHaptics()
    val result by vm.result.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val provided = LocalBarcodeDecoder.current
    val decoder = provided ?: remember { MlKitBarcodeDecoder() }
    DisposableEffect(decoder) { onDispose { if (provided == null) decoder.close() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val code = decoder.decodeUri(context, uri)
            if (code == null) snack("No QR code or barcode found in that picture.") else { vm.onCode(code); haptics.confirm() }
        }
    }
    val pickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    ToolFrame(host, scroll = false, padding = false, actions = {
        RoundIconButton(ForgeIcons.Clock, if (showHistory) "Back to scanner" else "Scan history", { showHistory = !showHistory }, active = showHistory)
        androidx.compose.foundation.layout.Spacer(Modifier.padding(end = 8.dp))
    }) {
        if (showHistory) {
            HistoryList(history, onOpen = { vm.showEntry(it); showHistory = false }, onRemove = vm::remove, onClear = vm::clearHistory, modifier = Modifier.weight(1f))
        } else {
            PermissionGate(
                Perms.CAMERA, ForgeIcons.Qr, "Camera access",
                "The scanner reads codes live through the camera. Frames are analysed on your phone and never stored or sent anywhere.",
                extra = { Column(Modifier.padding(top = 12.dp)) { ForgeButton("Scan a picture instead", onClick = pickImage, secondary = true, icon = ForgeIcons.Image, modifier = Modifier.fillMaxWidth()) } },
            ) {
                CameraArea(
                    scanning = result == null,
                    analyzer = remember(decoder, vm) {
                        FrameAnalyzer { image ->
                            decoder.decodeFrame(image) { code -> if (code != null && vm.onCode(code)) haptics.confirm() }
                        }
                    },
                    onGallery = pickImage,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        result?.let { content ->
            ResultCard(content, onClose = vm::dismiss, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun CameraArea(scanning: Boolean, analyzer: FrameAnalyzer, onGallery: () -> Unit, modifier: Modifier) {
    val backend = LocalCameraBackend.current
    var session by remember { mutableStateOf<CameraSession?>(null) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val c = ForgeTheme.colors
    Box(modifier.fillMaxSize().padding(horizontal = 16.dp).background(Color.Black, RoundedCornerShape(20.dp)).graphicsLayer { clip = true; shape = RoundedCornerShape(20.dp) }) {
        backend.Preview(Modifier.fillMaxSize(), if (scanning) analyzer else null, null) { s -> session = s; failed = s == null; s?.setTorch(torch) }
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val side = minOf(size.width, size.height) * 0.68f
            val tl = Offset((size.width - side) / 2, (size.height - side) / 2.3f)
            drawRect(Color.Black.copy(alpha = 0.45f))
            drawRoundRect(Color.Black, tl, Size(side, side), CornerRadius(28f), blendMode = BlendMode.Clear)
            drawRoundRect(c.accent, tl, Size(side, side), CornerRadius(28f), style = Stroke(width = 6f))
        }
        Text(
            if (failed) "Camera could not start. Close other camera apps and try again." else "Point at a QR code or barcode",
            style = ForgeType.Label, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
        )
        Row(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (session?.hasTorch == true) RoundIconButton(ForgeIcons.Torch, if (torch) "Torch off" else "Torch on", { torch = !torch; session?.setTorch(torch) }, active = torch)
            RoundIconButton(ForgeIcons.Image, "Scan from a picture", onGallery)
        }
    }
}

@Composable
private fun HistoryList(history: List<ScanEntry>, onOpen: (ScanEntry) -> Unit, onRemove: (ScanEntry) -> Unit, onClear: () -> Unit, modifier: Modifier) {
    val c = ForgeTheme.colors
    if (history.isEmpty()) {
        Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            IconBox(ForgeIcons.Clock, size = 56.dp, iconSize = 26.dp)
            Text("No scans yet", style = ForgeType.Item, color = c.text, modifier = Modifier.padding(top = 12.dp))
            Text("Codes you scan will be kept here on this phone.", style = ForgeType.Small, color = c.text2)
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextAction("Clear history", onClear, danger = true) } }
        items(history, key = { it.raw + it.at }) { e ->
            val content = com.namanbansal.forge.core.device.BarcodeClassifier.classify(e.raw, e.format)
            ForgeCard(Modifier.fillMaxWidth(), onClick = { onOpen(e) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBox(iconFor(content), size = 40.dp, iconSize = 20.dp)
                    Column(Modifier.weight(1f)) {
                        Text(e.raw.replace('\n', ' '), style = ForgeType.Item, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(content.label + " · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.at)), style = ForgeType.Small, color = c.text2)
                    }
                    RoundIconButton(ForgeIcons.Trash, "Delete this scan", { onRemove(e) })
                }
            }
        }
    }
}

internal fun iconFor(c: ScanContent): ImageVector = when (c) {
    is ScanContent.Url -> ForgeIcons.Link
    is ScanContent.Wifi -> ForgeIcons.Globe
    is ScanContent.Contact -> ForgeIcons.Bookmark
    is ScanContent.Phone, is ScanContent.Sms -> ForgeIcons.Phone
    is ScanContent.Email -> ForgeIcons.Note
    is ScanContent.Geo -> ForgeIcons.Compass
    is ScanContent.Product -> ForgeIcons.Hash
    is ScanContent.Text -> ForgeIcons.FileText
}

private class Act(val label: String, val icon: ImageVector, val primary: Boolean = false, val run: () -> Unit)

/** Result of a scan: what it is, its details and the actions that make sense for that type. */
@Composable
fun ResultCard(content: ScanContent, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    val context = LocalContext.current
    val snack = rememberSnack()
    var showPassword by rememberSaveable(content.raw) { mutableStateOf(false) }
    fun go(i: android.content.Intent, failure: String) { if (!ScanActions.launch(context, i)) snack(failure) }
    fun copy(text: String, what: String) { ScanActions.copy(context, what, text); snack("$what copied") }

    val (title, detail) = when (content) {
        is ScanContent.Url -> content.url to null
        is ScanContent.Wifi -> content.ssid to buildString {
            append(if (content.security.equals("nopass", true)) "Open network" else "Security: ${content.security}")
            if (content.hidden) append(" · hidden")
        }
        is ScanContent.Contact -> content.name.ifBlank { "Unnamed contact" } to listOf(content.org, content.phones.joinToString(", "), content.emails.joinToString(", ")).filter { it.isNotBlank() }.joinToString("\n")
        is ScanContent.Phone -> content.number to null
        is ScanContent.Email -> content.address to listOf(content.subject, content.body).filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
        is ScanContent.Sms -> content.number to content.body.ifBlank { null }
        is ScanContent.Geo -> "%.5f, %.5f".format(java.util.Locale.US, content.lat, content.lon) to content.query.ifBlank { null }
        is ScanContent.Product -> content.raw to content.format.replace('_', '-')
        is ScanContent.Text -> content.raw to null
    }
    val actions: List<Act> = when (content) {
        is ScanContent.Url -> listOf(
            Act("Open", ForgeIcons.Open, true) { go(ScanActions.view(content.url), "No app can open this link") },
            Act("Copy", ForgeIcons.Copy) { copy(content.url, "Link") },
        )
        is ScanContent.Wifi -> buildList {
            if (ScanActions.canSuggest(content)) add(Act("Add network", ForgeIcons.Globe, true) { go(ScanActions.wifiSuggestion(content), "Could not open the Wi-Fi settings") })
            if (content.password.isNotEmpty()) {
                add(Act(if (showPassword) "Hide" else "Show", ForgeIcons.Eye) { showPassword = !showPassword })
                add(Act("Copy", ForgeIcons.Copy) { copy(content.password, "Password") })
            }
        }
        is ScanContent.Contact -> listOf(
            Act("Add contact", ForgeIcons.Plus, true) { go(ScanActions.addContact(content), "No contacts app found") },
            Act("Copy", ForgeIcons.Copy) { copy(content.raw, "Contact") },
        )
        is ScanContent.Phone -> listOf(
            Act("Call", ForgeIcons.Phone, true) { go(ScanActions.dial(content.number), "No phone app found") },
            Act("Copy", ForgeIcons.Copy) { copy(content.number, "Number") },
        )
        is ScanContent.Email -> listOf(
            Act("Write email", ForgeIcons.Note, true) { go(ScanActions.email(content), "No email app found") },
            Act("Copy", ForgeIcons.Copy) { copy(content.address, "Address") },
        )
        is ScanContent.Sms -> listOf(
            Act("Send message", ForgeIcons.Note, true) { go(ScanActions.sms(content), "No messaging app found") },
            Act("Copy", ForgeIcons.Copy) { copy(content.number, "Number") },
        )
        is ScanContent.Geo -> listOf(
            Act("Open in maps", ForgeIcons.Compass, true) { go(ScanActions.map(content), "No maps app found") },
            Act("Copy", ForgeIcons.Copy) { copy("${content.lat}, ${content.lon}", "Coordinates") },
        )
        is ScanContent.Product -> listOf(
            Act("Search web", ForgeIcons.Search, true) { go(ScanActions.webSearch(content.raw), "No browser found") },
            Act("Copy", ForgeIcons.Copy) { copy(content.raw, "Code") },
        )
        is ScanContent.Text -> listOf(
            Act("Copy", ForgeIcons.Copy, true) { copy(content.raw, "Text") },
            Act("Share", ForgeIcons.Share) { go(ScanActions.share(content.raw), "Could not share") },
        )
    }
    ForgeCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBox(iconFor(content), size = 40.dp, iconSize = 20.dp)
                Tag(content.label)
                Box(Modifier.weight(1f))
                RoundIconButton(ForgeIcons.Close, "Close result and scan again", onClose)
            }
            Text(title, style = ForgeType.Section, color = c.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = ForgeType.Small, color = c.text2)
            if (content is ScanContent.Wifi && content.password.isNotEmpty()) Text("Password: " + if (showPassword) content.password else "••••••••", style = ForgeType.Item, color = c.text)
            actions.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { a -> ForgeButton(a.label, onClick = a.run, secondary = !a.primary, icon = a.icon, modifier = Modifier.weight(1f)) }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}
