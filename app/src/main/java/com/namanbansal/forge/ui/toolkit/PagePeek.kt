package com.namanbansal.forge.ui.toolkit

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeType

private val Scrim = Color(0xF2101012)

/**
 * Full-screen page peek: the page rendered sharp at screen width, swipe left/right to move between pages, close with
 * Back, the X, or a swipe down. [pages] are the pages in order; [startIndex] is the one to show first.
 */
@Composable
fun PagePeek(pages: List<GridPage>, startIndex: Int, onIndexChange: (Int) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PagePeekContent(pages, startIndex, onIndexChange, onClose)
    }
}

/** The peek without the dialog window (screenshots and tests render this directly). */
@Composable
fun PagePeekContent(pages: List<GridPage>, startIndex: Int, onIndexChange: (Int) -> Unit, onClose: () -> Unit) {
    if (pages.isEmpty()) return
    val pager: PagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, pages.lastIndex)) { pages.size }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { onIndexChange(it) } }
    var drag by remember { mutableFloatStateOf(0f) }
    val closeAt = with(LocalDensity.current) { 120.dp.toPx() }
    Box(
        Modifier.fillMaxSize().background(Scrim).pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = { if (drag > closeAt) onClose() else drag = 0f },
                onDragCancel = { drag = 0f },
            ) { change, amount -> change.consume(); drag = (drag + amount).coerceAtLeast(0f) }
        },
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize().graphicsLayer { translationY = drag; alpha = 1f - (drag / (closeAt * 4)).coerceIn(0f, 0.5f) }, beyondViewportPageCount = 1) { i ->
            PeekPage(pages[i])
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${pages[pager.currentPage.coerceIn(0, pages.lastIndex)].description} · ${pager.currentPage + 1} of ${pages.size}",
                style = ForgeType.Label, color = Color.White, modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose).semantics { contentDescription = "Close preview" },
                contentAlignment = Alignment.Center,
            ) { Icon(ForgeIcons.Close, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        }
    }
}

@Composable
private fun PeekPage(page: GridPage) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 56.dp), contentAlignment = Alignment.Center) {
        val widthPx = constraints.maxWidth.coerceIn(64, 1600)
        val thumbs = page.thumbs
        if (thumbs == null) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.707f).clip(RoundedCornerShape(4.dp)).background(Color.White))
        } else {
            val sharp by produceState<Bitmap?>(thumbs.peek(page.page, widthPx), thumbs, page.page, widthPx) {
                if (value == null) value = thumbs.render(page.page, widthPx)
            }
            val shown = sharp ?: thumbs.peek(page.page)
            if (shown != null) PageBitmap(shown, page.rotation.toFloat(), Modifier.fillMaxSize())
            else CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }
}
