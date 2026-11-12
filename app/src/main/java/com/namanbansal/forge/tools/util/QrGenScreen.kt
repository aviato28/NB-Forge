package com.namanbansal.forge.tools.util

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.namanbansal.forge.core.FileActions
import com.namanbansal.forge.core.device.PictureSaver
import com.namanbansal.forge.core.util.QrPayloads
import com.namanbansal.forge.core.util.QrRender
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.OptionChip
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.ToolFrame
import com.namanbansal.forge.ui.toolkit.rememberHaptics
import com.namanbansal.forge.ui.toolkit.rememberSnack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val TYPES = listOf("URL", "Text", "Wi-Fi", "Contact", "Email", "Phone")
private val FGS = listOf("Black" to 0xFF000000.toInt(), "Charcoal" to 0xFF18181B.toInt(), "Ember" to 0xFFC2410C.toInt(), "Blue" to 0xFF1D4ED8.toInt(), "Green" to 0xFF15803D.toInt())
private val BGS = listOf("White" to 0xFFFFFFFF.toInt(), "Cream" to 0xFFFFF1E8.toInt(), "Grey" to 0xFFF2F2F3.toInt(), "Yellow" to 0xFFFEF9C3.toInt())
private val SIZES = listOf(512, 1024, 2048)

private fun render(text: String, size: Int, fg: Int, bg: Int): Bitmap {
    val (px, side) = QrRender.pixels(QrRender.matrix(text), size, fg, bg)
    return Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888)
}

/** U12: QR generator for links, text, Wi-Fi, contacts, email and phone numbers, saved or shared as PNG. */
@Composable
fun QrGenScreen(host: ToolHost) {
    val c = ForgeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = rememberSnack()
    val haptics = rememberHaptics()
    var type by rememberSaveable { mutableIntStateOf(0) }
    var url by rememberSaveable { mutableStateOf("") }; var text by rememberSaveable { mutableStateOf("") }
    var ssid by rememberSaveable { mutableStateOf("") }; var pass by rememberSaveable { mutableStateOf("") }; var sec by rememberSaveable { mutableIntStateOf(0) }; var hidden by rememberSaveable { mutableStateOf(false) }
    var first by rememberSaveable { mutableStateOf("") }; var last by rememberSaveable { mutableStateOf("") }; var tel by rememberSaveable { mutableStateOf("") }
    var mail by rememberSaveable { mutableStateOf("") }; var org by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }; var subject by rememberSaveable { mutableStateOf("") }; var body by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var fgI by rememberSaveable { mutableIntStateOf(0) }; var bgI by rememberSaveable { mutableIntStateOf(0) }; var sizeI by rememberSaveable { mutableIntStateOf(1) }
    var busy by rememberSaveable { mutableStateOf(false) }
    val fg = FGS[fgI].second; val bg = BGS[bgI].second

    val payload = when (type) {
        0 -> url.trim().takeIf { it.isNotEmpty() }?.let { QrPayloads.url(it) }
        1 -> text.takeIf { it.isNotEmpty() }
        2 -> ssid.takeIf { it.isNotEmpty() }?.let { QrPayloads.wifi(it, pass, listOf("WPA", "WEP", "nopass")[sec], hidden) }
        3 -> if (first.isBlank() && last.isBlank()) null else QrPayloads.vcard(first.trim(), last.trim(), tel.trim(), mail.trim(), org.trim())
        4 -> to.trim().takeIf { it.isNotEmpty() }?.let { QrPayloads.email(it, subject, body) }
        else -> phone.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let { QrPayloads.phone(phone) }
    }
    val preview by produceState<Result<Bitmap>?>(null, payload, fg, bg) {
        value = if (payload == null) null else withContext(Dispatchers.Default) { runCatching { render(payload, 512, fg, bg) } }
    }
    suspend fun fullBitmap(): Bitmap = withContext(Dispatchers.Default) { render(payload!!, SIZES[sizeI], fg, bg) }

    ToolFrame(host) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { TYPES.forEachIndexed { i, t -> OptionChip(t, type == i, { type = i }) } }
        when (type) {
            0 -> TextField1(url, { url = it }, "Web address", keyboard = KeyboardType.Uri, placeholder = "example.com")
            1 -> MultiLineField(text, { text = it }, "Text")
            2 -> {
                TextField1(ssid, { ssid = it }, "Network name (SSID)")
                Segmented(listOf("WPA / WPA2", "WEP", "Open"), sec, { sec = it })
                if (sec != 2) TextField1(pass, { pass = it }, "Password")
                ToggleRow("Hidden network", hidden, { hidden = it })
            }
            3 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextField1(first, { first = it }, "First name", Modifier.weight(1f)); TextField1(last, { last = it }, "Last name", Modifier.weight(1f)) }
                TextField1(tel, { tel = it }, "Phone", keyboard = KeyboardType.Phone); TextField1(mail, { mail = it }, "Email", keyboard = KeyboardType.Email); TextField1(org, { org = it }, "Company")
            }
            4 -> { TextField1(to, { to = it }, "To", keyboard = KeyboardType.Email); TextField1(subject, { subject = it }, "Subject"); MultiLineField(body, { body = it }, "Message", minLines = 2) }
            else -> TextField1(phone, { phone = it }, "Phone number", keyboard = KeyboardType.Phone)
        }
        Text("Code colour", style = ForgeType.Label, color = c.text2)
        Swatches(FGS, fgI) { fgI = it }
        Text("Background", style = ForgeType.Label, color = c.text2)
        Swatches(BGS, bgI) { bgI = it }
        Segmented(SIZES.map { "$it px" }, sizeI, { sizeI = it })
        QrRender.warning(fg, bg)?.let { InfoNote(it) }
        val shown = preview
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(bg), androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).border(1.dp, c.border, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
            when {
                payload == null -> Text("Fill in the details above and your code appears here.", style = ForgeType.Body, color = Color(0xFF6B6B73))
                shown == null -> Text("Making your code…", style = ForgeType.Body, color = Color(0xFF6B6B73))
                shown.isFailure -> Text(shown.exceptionOrNull()?.message ?: "Could not make this code.", style = ForgeType.Body, color = Color(0xFFE5484D))
                else -> Image(shown.getOrThrow().asImageBitmap(), "QR code preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
            }
        }
        val ready = payload != null && preview?.isSuccess == true && !busy
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeButton("Save PNG", enabled = ready, onClick = {
                busy = true
                scope.launch {
                    try { PictureSaver.save(context, fullBitmap(), "Forge-QR"); haptics.confirm(); snack("Saved to Pictures/Forge") } catch (e: Exception) { snack("Could not save: ${e.message ?: "unknown error"}") }
                    busy = false
                }
            }, icon = ForgeIcons.Download, modifier = Modifier.weight(1f))
            ForgeButton("Share", enabled = ready, secondary = true, onClick = {
                busy = true
                scope.launch {
                    try {
                        val uri = withContext(Dispatchers.IO) {
                            val bmp = fullBitmap()
                            val f = File(File(context.cacheDir, "shared").also { it.mkdirs() }, "forge-qr.png")
                            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                        }
                        if (!FileActions.share(context, uri, "image/png", "forge-qr.png")) snack("No app can receive the picture")
                    } catch (e: Exception) { snack("Could not share: ${e.message ?: "unknown error"}") }
                    busy = false
                }
            }, icon = ForgeIcons.Share, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Swatches(items: List<Pair<String, Int>>, selected: Int, onPick: (Int) -> Unit) {
    val c = ForgeTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEachIndexed { i, (name, col) ->
            Box(
                Modifier.size(44.dp).background(Color(col), CircleShape).border(if (i == selected) 3.dp else 1.dp, if (i == selected) c.accent else c.border, CircleShape)
                    .clickable { onPick(i) }.semantics { contentDescription = name + if (i == selected) ", selected" else "" },
            )
        }
    }
}
