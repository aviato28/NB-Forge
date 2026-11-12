package com.namanbansal.forge.tools.image

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.RunScope
import com.namanbansal.forge.core.ToolOutcome
import com.namanbansal.forge.core.image.ImageInfo
import com.namanbansal.forge.core.image.MetaItem
import com.namanbansal.forge.core.image.MetadataStripper
import com.namanbansal.forge.core.image.StripMode
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.Segmented
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PickSpec
import com.namanbansal.forge.ui.toolkit.ToolScaffold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** I5 Strip EXIF / location: lists what the picture carries, then removes it all or only the location, without re-encoding. */
class StripViewModel(application: Application, saved: SavedStateHandle) : ImageToolViewModel(application, saved) {
    override val toolCode = "I5"
    private val modeS = persistedString("st.mode", StripMode.ALL.name)
    val mode: StateFlow<String> = modeS.flow
    fun setMode(m: StripMode) { modeS.value = m.name }

    private val _meta = MutableStateFlow<List<MetaItem>?>(null)
    /** Metadata of the first picture (null while reading). */
    val meta: StateFlow<List<MetaItem>?> = _meta.asStateFlow()
    private val _withLocation = MutableStateFlow(0)
    /** How many of the picked pictures carry GPS data. */
    val withLocation: StateFlow<Int> = _withLocation.asStateFlow()

    override suspend fun onImageReady(files: List<PickedFile>, info: ImageInfo, preview: Bitmap) {
        io {
            val all = files.map { MetadataStripper.read(readBytes(it)) }
            _meta.value = all.first()
            _withLocation.value = all.count { MetadataStripper.hasLocation(it) }
        }
    }

    override fun onInputsReset() { super.onInputsReset(); _meta.value = null; _withLocation.value = 0 }

    override suspend fun execute(scope: RunScope): ToolOutcome {
        val files = inputs.value
        val m = StripMode.entries.firstOrNull { it.name == modeS.value } ?: StripMode.ALL
        val r = with(scope) {
            batch(files) { _, f ->
                val bytes = readBytes(f)
                val kind = MetadataStripper.kindOf(bytes)
                val clean = MetadataStripper.strip(bytes, m, app.cacheDir)
                val (ext, mime) = when (kind) { MetadataStripper.Kind.PNG -> "png" to "image/png"; MetadataStripper.Kind.WEBP -> "webp" to "image/webp"; else -> "jpg" to "image/jpeg" }
                saveAs(f.name, if (m == StripMode.ALL) "clean" else "nolocation", ext, mime, images = true) { it.write(clean) }
            }
        }
        return outcome(r, if (m == StripMode.ALL) "Metadata removed" else "Location removed", "no re-encode, same quality")
    }
}

@Composable
fun StripScreen(host: ToolHost, vm: StripViewModel = viewModel()) {
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val meta by vm.meta.collectAsStateWithLifecycle()
    val withLoc by vm.withLocation.collectAsStateWithLifecycle()
    val modeName by vm.mode.collectAsStateWithLifecycle()
    val mode = StripMode.entries.firstOrNull { it.name == modeName } ?: StripMode.ALL
    val c = ForgeTheme.colors
    ToolScaffold(
        tool = host.tool, vm = vm, onBack = host.onBack, onOpenTool = host.onOpenTool,
        pick = PickSpec(listOf(FileKind.IMAGE), multiple = true, title = "Choose pictures to clean", hint = "Removes location, camera and date details."),
        actionLabel = if (inputs.size > 1) "Clean ${inputs.size} pictures" else "Clean picture",
        inputSubtitle = { it.subtitle(info) }, optionsReady = meta != null && preview != null,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ImagePreview(preview, Modifier.size(96.dp))
                Column(Modifier.weight(1f)) {
                    Text("What this picture carries", style = ForgeType.Item, color = c.text)
                    Text(
                        if (meta.isNullOrEmpty()) "Nothing found" else "${meta!!.size} details" + if (inputs.size > 1) " · $withLoc of ${inputs.size} pictures have a location" else "",
                        style = ForgeType.Small, color = c.text2,
                    )
                }
            }
            val items = meta.orEmpty()
            if (items.isEmpty()) InfoNote("No camera, date or location details were found. Cleaning still removes any hidden comments or profiles.")
            else ForgeCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items.groupBy { it.group }.forEach { (group, rows) ->
                        Text(group, style = ForgeType.Label, color = if (group == "Location") c.accent else c.text2, modifier = Modifier.padding(top = 4.dp))
                        rows.forEach { r ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(r.label, style = ForgeType.Small, color = c.text2)
                                Text(r.value, style = ForgeType.Small, color = c.text, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            }
            Segmented(listOf("Remove everything", "Location only"), mode.ordinal, { vm.setMode(StripMode.entries[it]) })
            InfoNote(
                if (mode == StripMode.ALL) "The picture itself is not re-encoded, so its quality is exactly the same. Only the hidden data is removed (the rotation is kept so it stays upright)."
                else "Removes GPS data and keeps camera and date details. For PNG and WebP, all embedded EXIF data is removed because it can't be edited safely.",
            )
        }
    }
}
