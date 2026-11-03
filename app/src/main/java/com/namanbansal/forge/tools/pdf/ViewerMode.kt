package com.namanbansal.forge.tools.pdf

import androidx.compose.runtime.Composable
import com.namanbansal.forge.core.pdf.annot.UndoRedo
import kotlinx.coroutines.flow.StateFlow

/** The modes on the viewer's mode bar. Only [available] ones switch the viewer; the rest show "Coming soon" or open another tool. */
enum class ViewerModeId(val label: String, val available: Boolean) {
    READ("Read", true), ANNOTATE("Annotate", true), EDIT("Edit", true), FILL_SIGN("Fill & Sign", true), PAGES("Pages", true), MORE("More", true);

    /** True for modes that stay open in the viewer (the others are one-shot actions). */
    val staysInViewer: Boolean get() = this == READ || this == ANNOTATE || this == EDIT || this == FILL_SIGN
}

/** A touch landing on a page: [page] (0-based), the point as fractions of the displayed page, and the page's width / height. */
data class PageHit(val page: Int, val x: Float, val y: Float, val aspect: Float)

/**
 * How a mode takes over touch input on the pages. The viewer's gesture layer calls this for one-finger gestures; two fingers
 * always scroll / zoom. Positions are fractions of the page the gesture started on (clamped to it), so a mode never deals with
 * zoom, scroll offsets or page layout.
 */
interface PageInput {
    /** True when a one-finger drag belongs to the mode (drawing, selecting text, erasing) instead of scrolling. */
    val claimsDrag: Boolean
    /** True when a short touch should become a stroke start/end (a dot) instead of a tap. */
    val tapIsStroke: Boolean get() = false
    /** Whether a pointer of this kind may start a gesture (stylus-only drawing once a stylus has been seen). */
    fun accepts(stylus: Boolean): Boolean = true
    /** Whether a drag that starts at [hit] belongs to the mode (default: the same as [claimsDrag]); lets a mode drag one object and leave the rest to scrolling. */
    fun claimsAt(hit: PageHit): Boolean = claimsDrag
    fun onStart(hit: PageHit)
    fun onMove(x: Float, y: Float)
    fun onEnd()
    /** A second finger arrived or the system cancelled: drop the gesture. */
    fun onCancel()
    /** A tap. Returns true when the mode used it (otherwise the viewer toggles its bars). */
    fun onTap(hit: PageHit): Boolean = false
}

/**
 * What every viewer mode provides, so Edit and Fill & Sign plug in next without touching the viewer: a per-page overlay in page
 * coordinates, input handling, shared undo / redo and a shared "unsaved changes" flag that drives Save, back and close.
 */
interface ViewerMode {
    val id: ViewerModeId
    val undoRedo: UndoRedo? get() = null
    /** Unsaved changes made in this mode. */
    val dirty: StateFlow<Boolean>
    val input: PageInput? get() = null
    /** Drawn above the page bitmap, below the selection handles; the page is [widthPx] x [heightPx] pixels at the current zoom. */
    @Composable fun PageOverlay(page: Int, widthPx: Int, heightPx: Int, invert: Boolean)
    /** The tool strip shown above the mode bar. */
    @Composable fun Toolbar()
}
