package com.namanbansal.forge.tools.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.core.pdf.annot.AnnotHit
import com.namanbansal.forge.core.pdf.annot.AnnotType
import com.namanbansal.forge.core.pdf.annot.UndoRedo
import com.namanbansal.forge.tools.pdf.AnnotDraw.ink
import com.namanbansal.forge.tools.pdf.AnnotDraw.markup
import com.namanbansal.forge.tools.pdf.AnnotDraw.note
import com.namanbansal.forge.tools.pdf.AnnotDraw.outline
import com.namanbansal.forge.ui.theme.ForgeTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * The Annotate [ViewerMode]. Its overlay draws every annotation of the document (the ones read from the PDF too: the viewer
 * renders pages without them), so edits, undo and the unsaved state are exact; the live stroke is a separate canvas that only
 * redraws, never recomposes. Input is only handed to the controller while Annotate is the active mode ([interactive]).
 */
class AnnotateMode(val controller: AnnotateController, private val interactive: Boolean) : ViewerMode {
    override val id = ViewerModeId.ANNOTATE
    override val undoRedo: UndoRedo get() = controller.session
    override val dirty: StateFlow<Boolean> get() = controller.session.dirty
    override val input: PageInput? get() = if (interactive) controller else null

    @Composable
    override fun PageOverlay(page: Int, widthPx: Int, heightPx: Int, invert: Boolean) {
        val c = ForgeTheme.colors
        val all by controller.session.annots.collectAsState()
        val selectedId by controller.selectedId.collectAsState()
        val mine = remember(all, page) { all.filter { it.page == page } }
        val live = controller.live
        val aspect = widthPx.toFloat() / heightPx.coerceAtLeast(1)
        // Saved annotations: drawn again only when the list changes.
        val paths = remember(mine, widthPx, heightPx) { mine.map { a -> if (a.type == AnnotType.INK) a.strokes.map { AnnotDraw.inkPath(it, widthPx.toFloat(), heightPx.toFloat()) } else emptyList() } }
        Canvas(Modifier.fillMaxSize()) {
            live.revision // the eraser dims what it has touched
            mine.forEachIndexed { i, a ->
                val dim = live.page == a.page && a.id in live.erasing
                val k = if (dim) 0.3f else 1f
                when (a.type) {
                    AnnotType.HIGHLIGHT, AnnotType.UNDERLINE, AnnotType.STRIKEOUT -> markup(a.type, a.quads, a.color, k * a.opacity, invert)
                    AnnotType.INK -> paths[i].forEach { ink(it, a.color, a.opacity * k, a.width * size.width) }
                    AnnotType.NOTE -> note(a, dim)
                }
            }
            val sel = mine.firstOrNull { it.id == selectedId }
            if (sel != null) AnnotHit.bounds(sel, aspect)?.let { outline(it, c.accent, 5.dp.toPx()) }
        }
        // The stroke being drawn.
        Canvas(Modifier.fillMaxSize()) {
            live.revision
            if (live.page != page) return@Canvas
            when (live.type) {
                AnnotType.INK -> if (live.points.isNotEmpty()) ink(AnnotDraw.inkPath(live.points, size.width, size.height), live.color, live.opacity, live.width * size.width)
                AnnotType.HIGHLIGHT, AnnotType.UNDERLINE, AnnotType.STRIKEOUT -> markup(live.type, live.quads, live.color, live.opacity, invert)
                else -> Unit
            }
        }
    }

    @Composable
    override fun Toolbar() {
        val tool by controller.tool.collectAsState()
        val colors by controller.colors.collectAsState()
        val thickness by controller.thickness.collectAsState()
        AnnotateToolbar(tool, colors, thickness, controller::selectTool, controller::setColor, controller::setThickness)
    }
}
