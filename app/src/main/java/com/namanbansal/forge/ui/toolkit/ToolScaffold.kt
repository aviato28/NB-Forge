package com.namanbansal.forge.ui.toolkit

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namanbansal.forge.ForgeApp
import com.namanbansal.forge.core.FileActions
import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.core.NextSuggestions
import com.namanbansal.forge.core.OutputSaver
import com.namanbansal.forge.core.PickedFile
import com.namanbansal.forge.core.ToolPhase
import com.namanbansal.forge.core.ToolResult
import com.namanbansal.forge.core.ToolViewModel
import com.namanbansal.forge.core.formatBytes
import com.namanbansal.forge.tools.ToolDef
import com.namanbansal.forge.ui.components.ForgeButton
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.components.IconBox
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.screens.TopBar
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import kotlinx.coroutines.launch

/** What the Pick step asks for. */
data class PickSpec(
    val kinds: List<FileKind>,
    val multiple: Boolean = false,
    /** Headline on the empty state, e.g. "Choose a PDF". */
    val title: String,
    val hint: String = "Files stay on your device.",
) {
    val mimes: Array<String> get() = FileKind.pickerMimes(*kinds.toTypedArray())
}

/**
 * The shared tool flow: Pick -> Options -> Run (progress + Cancel) -> Result (Share / Save as / Open / Next).
 * A tool screen supplies [options]; everything else (pickers, loading, errors, retry, result) is handled here.
 * See docs/ADDING_A_TOOL.md and tools/pdf/RotateScreen.kt.
 *
 * @param actionLabel text of the primary button on the Options step ("Save", "Compress"...)
 * @param actionEnabled whether the primary button can be pressed yet
 * @param actionHint small line above the button explaining why it is disabled (or what will happen)
 * @param inputSubtitle second line of the input file card (e.g. "12 pages · 4.2 MB")
 * @param dialogs tool-specific dialogs (e.g. [PasswordDialog]); composed at the top level
 * @param showInputCard false when the tool draws its own list of picked files
 * @param optionsReady false while the tool is still preparing options (e.g. waiting for a password); shows a spinner
 */
@Composable
fun ToolScaffold(
    tool: ToolDef,
    vm: ToolViewModel,
    pick: PickSpec,
    actionLabel: String,
    onBack: () -> Unit,
    onOpenTool: (code: String, files: List<PickedFile>) -> Unit,
    modifier: Modifier = Modifier,
    title: String = tool.name,
    actionEnabled: Boolean = true,
    actionHint: String? = null,
    inputSubtitle: (List<PickedFile>) -> String = { files -> files.firstOrNull()?.let { formatBytes(it.size) }.orEmpty() },
    optionsReady: Boolean = true,
    /** False for tools that show their own list of inputs (Merge). */
    showInputCard: Boolean = true,
    dialogs: @Composable () -> Unit = {},
    options: @Composable ColumnScope.() -> Unit,
) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val snack = rememberSnack()
    val launchPicker = rememberFilePicker(pick.mimes, pick.multiple) { vm.onUrisPicked(it) }

    LaunchedEffect(vm) { vm.messages.collect { snack(it) } }
    BackHandler(enabled = phase == ToolPhase.Running) { vm.cancel() }

    Column(modifier.fillMaxSize()) {
        TopBar(title, onBack = onBack)
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "toolPhase",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { p ->
            when (p) {
                ToolPhase.Pick -> PickView(pick, loading, onChoose = launchPicker)
                ToolPhase.Options -> OptionsView(
                    inputs = inputs, subtitle = inputSubtitle(inputs), ready = optionsReady && !loading,
                    actionLabel = actionLabel, actionEnabled = actionEnabled, actionHint = actionHint, showInputCard = showInputCard,
                    onChange = { launchPicker() }, onAction = vm::start, content = options,
                )
                ToolPhase.Running -> RunningView(progress, onCancel = vm::cancel)
                ToolPhase.Result -> result?.let { r ->
                    ResultView(r, tool, onOpenTool, onEditAgain = vm::backToOptions, onNewFile = vm::changeFiles, onDone = onBack)
                }
                ToolPhase.Failed -> ErrorView(error.orEmpty(), onRetry = vm::retry, onBack = vm::backToOptions)
            }
        }
    }
    dialogs()
}

// ---- Pick ------------------------------------------------------------------------------------------------------

@Composable
fun PickView(spec: PickSpec, loading: Boolean, onChoose: () -> Unit) {
    val c = ForgeTheme.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = c.accent, trackColor = c.surface2, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(16.dp))
            Text("Opening…", style = ForgeType.Item, color = c.text)
        } else {
            IconBox(ForgeIcons.Plus, size = 72.dp, iconSize = 32.dp, shape = RoundedCornerShape(22.dp))
            Spacer(Modifier.height(16.dp))
            Text(spec.title, style = ForgeType.Title, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(spec.hint, style = ForgeType.Small, color = c.text2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            ForgeButton(if (spec.multiple) "Choose files" else "Choose file", onClick = onChoose, icon = ForgeIcons.Folder, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---- Options ---------------------------------------------------------------------------------------------------

@Composable
private fun OptionsView(
    inputs: List<PickedFile>,
    subtitle: String,
    ready: Boolean,
    actionLabel: String,
    actionEnabled: Boolean,
    actionHint: String?,
    showInputCard: Boolean,
    onChange: () -> Unit,
    onAction: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = ForgeTheme.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (showInputCard) {
            InputCard(inputs, subtitle, onChange)
            Spacer(Modifier.height(12.dp))
        }
        if (ready) {
            Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent, trackColor = c.surface2, modifier = Modifier.size(32.dp))
            }
        }
        if (actionHint != null) {
            Text(actionHint, style = ForgeType.Small, color = c.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        ForgeButton(actionLabel, onClick = onAction, enabled = actionEnabled && ready, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
    }
}

/** The picked file ("invoice_oct.pdf / 12 pages · 4.2 MB") with a Change action. */
@Composable
fun InputCard(inputs: List<PickedFile>, subtitle: String, onChange: () -> Unit) {
    val c = ForgeTheme.colors
    val first = inputs.firstOrNull() ?: return
    ForgeCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBox(ForgeIcons.FileText, size = 44.dp, iconSize = 22.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    if (inputs.size > 1) "${first.name} +${inputs.size - 1} more" else first.name,
                    style = ForgeType.Item, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) Text(subtitle, style = ForgeType.Small, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(onClick = onChange).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text("Change", style = ForgeType.Label, color = c.accent)
            }
        }
    }
}

// ---- Running ---------------------------------------------------------------------------------------------------

@Composable
fun RunningView(progress: Float?, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(if (progress == null) "Working…" else "${(progress * 100).toInt()}%", style = ForgeType.Display, color = c.text)
        Spacer(Modifier.height(20.dp))
        val bar = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).semantics { contentDescription = "Progress" }
        if (progress == null) {
            LinearProgressIndicator(color = c.accent, trackColor = c.surface2, modifier = bar)
        } else {
            LinearProgressIndicator(progress = { progress }, color = c.accent, trackColor = c.surface2, modifier = bar)
        }
        Spacer(Modifier.height(12.dp))
        Text("Your file stays on this device.", style = ForgeType.Small, color = c.text2)
        Spacer(Modifier.height(32.dp))
        ForgeButton("Cancel", onClick = onCancel, secondary = true, modifier = Modifier.fillMaxWidth())
    }
}

// ---- Error -----------------------------------------------------------------------------------------------------

@Composable
fun ErrorView(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val c = ForgeTheme.colors
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBox(ForgeIcons.Info, size = 72.dp, iconSize = 32.dp, shape = RoundedCornerShape(22.dp))
        Spacer(Modifier.height(16.dp))
        Text("That didn't work", style = ForgeType.Title, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(message, style = ForgeType.Body, color = c.text2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        ForgeButton("Try again", onClick = onRetry, icon = ForgeIcons.Refresh, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        ForgeButton("Back to options", onClick = onBack, secondary = true, modifier = Modifier.fillMaxWidth())
    }
}

// ---- Result (mockup 05c) ---------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultView(
    result: ToolResult,
    tool: ToolDef,
    onOpenTool: (String, List<PickedFile>) -> Unit,
    onEditAgain: () -> Unit,
    onNewFile: () -> Unit,
    onDone: () -> Unit,
) {
    val c = ForgeTheme.colors
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snack = rememberSnack()
    val scope = rememberCoroutineScope()
    val out = result.output

    LaunchedEffect(result) { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(out.mime)) { dest ->
        if (dest != null) scope.launch {
            try {
                ForgeApp.from(context).outputSaver.copyTo(out.uri, dest)
                snack("Saved a copy")
            } catch (_: Exception) {
                snack("Couldn't save a copy there")
            }
        }
    }
    val next = NextSuggestions.forOutput(result.kind, result.toolCode)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.size(96.dp).clip(CircleShape).background(c.tint), contentAlignment = Alignment.Center) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(ForgeIcons.Check, null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(result.headline, style = ForgeType.BodyMedium.copy(fontWeight = FontWeight.SemiBold), color = c.text2)
        Spacer(Modifier.height(6.dp))
        val changed = result.outputs.size == 1 && result.beforeSize >= 0 && result.afterSize >= 0 && result.beforeSize != result.afterSize
        if (changed) {
            Text(
                "${formatBytes(result.beforeSize)} → ${formatBytes(result.afterSize)}",
                style = ForgeType.Display.copy(fontSize = 28.sp, lineHeight = 34.sp), color = c.accent, textAlign = TextAlign.Center,
            )
        } else {
            Text(formatBytes(result.afterSize), style = ForgeType.Display.copy(fontSize = 28.sp, lineHeight = 34.sp), color = c.text)
        }
        val chip = savingsChip(result)
        if (chip != null || result.detail != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                listOfNotNull(result.detail, chip).joinToString(" · "),
                style = ForgeType.Label, color = c.accent, textAlign = TextAlign.Center,
                modifier = Modifier.clip(ForgeShapes.Chip).background(c.tint).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        val many = result.outputs.size > 1
        ForgeCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBox(if (many) ForgeIcons.Copy else ForgeIcons.FileText)
                Column(Modifier.weight(1f)) {
                    Text(if (many) "${result.outputs.size} files" else out.displayName, style = ForgeType.Item, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${formatBytes(result.afterSize)} · ${out.folder}", style = ForgeType.Small, color = c.text2)
                }
            }
            if (many) {
                val c2 = ForgeTheme.colors
                result.outputs.take(4).forEach { o ->
                    Text(o.displayName, style = ForgeType.Small, color = c2.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 68.dp, end = 12.dp, bottom = 4.dp))
                }
                if (result.outputs.size > 4) Text("+ ${result.outputs.size - 4} more", style = ForgeType.Small, color = c2.text2, modifier = Modifier.padding(start = 68.dp, bottom = 10.dp))
                else Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (many) {
            ForgeButton(
                "Share all", icon = ForgeIcons.Share, modifier = Modifier.fillMaxWidth(),
                onClick = { if (!FileActions.shareMultiple(context, result.outputs.map { it.uri }, out.mime, "${result.outputs.size} files from Forge")) snack("No app available to share these files") },
            )
            result.archive?.let { zip ->
                Spacer(Modifier.height(8.dp))
                ForgeButton(
                    "Share ZIP (${zip.displayName})", icon = ForgeIcons.Archive, secondary = true, modifier = Modifier.fillMaxWidth(),
                    onClick = { if (!FileActions.share(context, zip.uri, zip.mime, zip.displayName)) snack("No app available to share this file") },
                )
            }
        } else {
            ForgeButton(
                "Share", icon = ForgeIcons.Share, modifier = Modifier.fillMaxWidth(),
                onClick = { if (!FileActions.share(context, out.uri, out.mime, out.displayName)) snack("No app available to share this file") },
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForgeButton("Save as", icon = ForgeIcons.Download, secondary = true, modifier = Modifier.weight(1f), onClick = { saveAs.launch(out.displayName) })
                ForgeButton(
                    "Open", icon = ForgeIcons.Open, secondary = true, modifier = Modifier.weight(1f),
                    onClick = { if (!FileActions.open(context, out.uri, out.mime)) snack("No app found to open this file") },
                )
            }
        }
        if (next.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                GroupLabel("Next", Modifier.padding(top = 24.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    next.forEach { t ->
                        NextChip(t) {
                            // Tools that take several files get the whole result; the rest get the first file.
                            val files = (if (t.code in NextSuggestions.MULTI_INPUT) result.outputs else listOf(out)).map { PickedFile(it.uri, it.displayName, it.size, it.mime) }
                            onOpenTool(t.code, files)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            LinkButton("Edit again", onEditAgain)
            LinkButton("Another file", onNewFile)
            LinkButton("Done", onDone)
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun savingsChip(r: ToolResult): String? {
    if (r.outputs.size > 1) return null // parts are not a smaller version of one file
    val saved = r.savedBytes ?: return null
    if (saved <= 0 || r.beforeSize <= 0) return null
    return "Saved ${formatBytes(saved)} (${(saved * 100 / r.beforeSize)}% smaller)"
}

@Composable
private fun NextChip(tool: ToolDef, onClick: () -> Unit) {
    val c = ForgeTheme.colors
    Row(
        Modifier.height(40.dp).clip(ForgeShapes.Chip).background(c.surface2).border(1.dp, c.border, ForgeShapes.Chip)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(tool.icon, null, tint = c.text, modifier = Modifier.size(16.dp))
        Text(tool.shortLabel, style = ForgeType.Label, color = c.text)
    }
}

@Composable
private fun LinkButton(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = ForgeType.Label, color = ForgeTheme.colors.accent)
    }
}
