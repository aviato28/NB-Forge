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

/**
 * One page tile: the thumbnail of [sourcePage] (rotated live by [rotation] degrees), a selection ring + check, and a
 * [label] underneath. Shared by Rotate, Page manager and Extract. Thumbnails render lazily off the main thread.
 */
@Composable
fun PageThumb(
    sourcePage: Int,
    label: String,
    thumbnails: PdfThumbnails,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    rotation: Int = 0,
    onClick: (() -> Unit)? = null,
    description: String = "Page $label",
) {
    val c = ForgeTheme.colors
    val bitmap by produceState(initialValue = thumbnails.peek(sourcePage), sourcePage, thumbnails) {
        if (value == null) value = thumbnails.get(sourcePage)
    }
    val angle by animateFloatAsState(rotation.toFloat(), tween(220), label = "pageRotation")
    val turns = ((rotation % 360) + 360) % 360
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it }.semantics {
            contentDescription = description + if (turns != 0) ", rotated $turns degrees" else ""
            stateDescription = if (selected) "Selected" else "Not selected"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(8.dp)).background(c.surface2)
                .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.border, RoundedCornerShape(8.dp)),
        ) {
            val bmp = bitmap
            if (bmp != null) {
                val image = remember(bmp) { bmp.asImageBitmap() }
                Canvas(Modifier.fillMaxSize().padding(3.dp)) {
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
