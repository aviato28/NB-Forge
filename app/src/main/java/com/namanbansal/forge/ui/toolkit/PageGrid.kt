package com.namanbansal.forge.ui.toolkit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.PdfThumbnails
import com.namanbansal.forge.ui.icons.ForgeIcons
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Hues for colour-coded groups (split files, inserted pages). Deliberately not the orange accent, which means "selected". */
private val GroupHues = listOf(0xFF3B82F6, 0xFF22A06B, 0xFF8B5CF6, 0xFFE5489B, 0xFF0EA5A5, 0xFFC98A00)

/** Stable colour for group [index]; slightly brighter in dark mode so it keeps its contrast. */
fun groupColor(index: Int, dark: Boolean): Color {
    val base = Color(GroupHues[((index % GroupHues.size) + GroupHues.size) % GroupHues.size])
    return if (dark) androidx.compose.ui.graphics.lerp(base, Color.White, 0.25f) else base
}

/** Draws [bitmap] rotated by [angle] degrees, scaled to fit its box (a quarter turn swaps width and height). */
@Composable
fun PageBitmap(bitmap: android.graphics.Bitmap, angle: Float, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier) {
        val rad = Math.toRadians(angle.toDouble())
        val ac = abs(cos(rad)).toFloat()
        val asn = abs(sin(rad)).toFloat()
        val bw = image.width.toFloat()
        val bh = image.height.toFloat()
        val fit = min(size.width / (bw * ac + bh * asn), size.height / (bw * asn + bh * ac))
        withTransform({
            translate(size.width / 2f, size.height / 2f)
            rotate(angle, Offset.Zero)
            scale(fit, fit, Offset.Zero)
        }) {
            drawImage(image, topLeft = Offset(-bw / 2f, -bh / 2f))
        }
    }
}

/**
 * One page tile: the thumbnail of [sourcePage] (rotated live by [rotation] degrees), a selection ring + check, and a
 * [label] underneath. Shared by Rotate, Page manager, Extract, Split, Insert. Thumbnails render lazily off the main thread.
 * [thumbnails] null draws a blank white page (Insert's placeholder). [tint] colours the page as part of a group, [badge]
 * is a small caption on the page, [dimmed] fades pages that are not part of the result.
 */
@Composable
fun PageThumb(
    sourcePage: Int,
    label: String,
    thumbnails: PdfThumbnails?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    rotation: Int = 0,
    onClick: (() -> Unit)? = null,
    description: String = "Page $label",
    tint: Color? = null,
    badge: String? = null,
    dimmed: Boolean = false,
) {
    val c = ForgeTheme.colors
    val bitmap by produceState(initialValue = thumbnails?.peek(sourcePage), sourcePage, thumbnails) {
        if (value == null && thumbnails != null) value = thumbnails.get(sourcePage)
    }
    val angle by animateFloatAsState(rotation.toFloat(), tween(220), label = "pageRotation")
    val turns = ((rotation % 360) + 360) % 360
    val ring = when {
        selected -> c.accent
        tint != null -> tint
        else -> c.border
    }
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it }.semantics {
            contentDescription = description + if (turns != 0) ", rotated $turns degrees" else ""
            stateDescription = if (selected) "Selected" else "Not selected"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.75f).alpha(if (dimmed) 0.4f else 1f).clip(RoundedCornerShape(8.dp))
                .background(if (thumbnails == null) Color.White else c.surface2)
                .border(if (selected || tint != null) 2.dp else 1.dp, ring, RoundedCornerShape(8.dp)),
        ) {
            val bmp = bitmap
            if (bmp != null) PageBitmap(bmp, angle, Modifier.fillMaxSize().padding(3.dp))
            if (tint != null) Box(Modifier.fillMaxSize().background(tint.copy(alpha = if (c.isDark) 0.22f else 0.14f)))
            if (badge != null) {
                Text(
                    badge, style = ForgeType.Tiny.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Color.White, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(6.dp))
                        .background(tint ?: c.accent).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape).background(c.accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(ForgeIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
        Text(label, style = ForgeType.Tiny, color = c.text2)
    }
}

/** A plain (non-reorderable) 3-column grid of [PageThumb]s that fills the remaining space; used by Rotate and Extract. */
@Composable
fun PageSelectGrid(
    pageCount: Int,
    thumbnails: PdfThumbnails,
    selected: Set<Int>,
    onToggle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rotations: Map<Int, Int> = emptyMap(),
    columns: Int = 3,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        items((0 until pageCount).toList(), key = { it }) { index ->
            PageThumb(index, "${index + 1}", thumbnails, selected = index in selected, rotation = rotations[index] ?: 0, onClick = { onToggle(index) })
        }
    }
}
