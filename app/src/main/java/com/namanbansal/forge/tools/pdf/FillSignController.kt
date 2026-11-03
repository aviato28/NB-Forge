package com.namanbansal.forge.tools.pdf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.namanbansal.forge.core.pdf.NormRect
import com.namanbansal.forge.core.pdf.fill.FillItem
import com.namanbansal.forge.core.pdf.fill.FillPalette
import com.namanbansal.forge.core.pdf.fill.FillSession
import com.namanbansal.forge.core.pdf.fill.ItemKind
import com.namanbansal.forge.core.pdf.fill.Placement
import com.namanbansal.forge.core.pdf.fill.TextBoxMath
import com.namanbansal.forge.core.pdf.form.FieldKind
import com.namanbansal.forge.core.pdf.form.FormField
import com.namanbansal.forge.core.pdf.form.FormInfo
import com.namanbansal.forge.core.pdf.form.FormValues
import com.namanbansal.forge.core.pdf.sign.SigEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two things a tap on empty page can do in Fill & Sign. */
enum class FillTool { FIELDS, TEXT }

/** A sheet the UI should show for a form field. */
data class FieldEdit(val fieldId: String)

/**
 * All the logic of Fill & Sign without any Compose UI: form hotspots, "Next field", placing and dragging text boxes and
 * signatures. Edits go to [session] (undo / redo, dirty). Positions are page fractions, so zoom and rotation never matter here.
 */
class FillSignController(
    private val pageAspect: (Int) -> Float,
    private val currentPage: () -> Int,
    private val sigAspect: (String) -> Float?,
    private val message: (String) -> Unit,
) : PageInput {
    val session = FillSession()

    private val _form = MutableStateFlow(FormInfo())
    val form: StateFlow<FormInfo> = _form.asStateFlow()
    fun setForm(info: FormInfo) { _form.value = info; _focusId.value = null; _fieldEdit.value = null }

    private val _tool = MutableStateFlow(FillTool.FIELDS)
    val tool: StateFlow<FillTool> = _tool.asStateFlow()
    fun setTool(t: FillTool) { _tool.value = t; if (t == FillTool.TEXT) _selectedId.value = null }

    private val _textSize = MutableStateFlow(1)
    val textSize: StateFlow<Int> = _textSize.asStateFlow()
    private val _textColor = MutableStateFlow(FillPalette.BLACK)
    val textColor: StateFlow<Int> = _textColor.asStateFlow()
    fun setTextSize(i: Int) { _textSize.value = i; selected()?.takeIf { it.kind == ItemKind.TEXT }?.let { session.replaceItem(refit(it.copy(size = i))) } }
    fun setTextColor(c: Int) { _textColor.value = c; selected()?.takeIf { it.kind == ItemKind.TEXT || it.kind == ItemKind.CHECK || it.kind == ItemKind.CROSS }?.let { session.replaceItem(it.copy(color = c)) } }

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()
    fun select(id: String?) { _selectedId.value = id }
    fun selected(): FillItem? = _selectedId.value?.let { live?.takeIf { l -> l.id == it } ?: session.find(it) }

    private val _focusId = MutableStateFlow<String?>(null)
    /** The form field "Next / Previous" stopped on. */
    val focusId: StateFlow<String?> = _focusId.asStateFlow()
    private val _jump = MutableStateFlow(0 to 0)
    /** (page, nonce): the screen scrolls to this page when the nonce changes. */
    val jump: StateFlow<Pair<Int, Int>> = _jump.asStateFlow()

    private val _fieldEdit = MutableStateFlow<FieldEdit?>(null)
    val fieldEdit: StateFlow<FieldEdit?> = _fieldEdit.asStateFlow()
    fun closeFieldEdit() { _fieldEdit.value = null }

    private val _editingItem = MutableStateFlow<String?>(null)
    /** The text box whose text is being typed (a sheet is open). */
    val editingItem: StateFlow<String?> = _editingItem.asStateFlow()
    private var freshItem: String? = null
    fun closeItemEdit(save: String?) {
        val id = _editingItem.value ?: return
        _editingItem.value = null
        val it = session.find(id)
        if (save != null && it != null) {
            if (save.isBlank()) { if (freshItem == id) discardFresh(id) else session.removeItem(id); _selectedId.value = null }
            else session.replaceItem(refit(it.copy(text = save)))
        } else if (freshItem == id) discardFresh(id)
        freshItem = null
    }
    private fun discardFresh(id: String) { if (session.find(id) != null) session.undoLast() }

    private val _signTarget = MutableStateFlow<String?>(null)
    /** Non-null while the signature library is open for placing; the value is a signature field id to fill, or "" for the page centre. */
    val signTarget: StateFlow<String?> = _signTarget.asStateFlow()
    fun openSign(fieldId: String? = null) { _signTarget.value = fieldId ?: "" }
    fun closeSign() { _signTarget.value = null }

    val fields: List<FormField> get() = _form.value.fields
    private fun fieldById(id: String?) = fields.firstOrNull { it.id == id }

    // ---- form fields -----------------------------------------------------------------------------------------------------

    fun valueOf(f: FormField): String = FormValues.valueOf(f, session.current.overrides)

    fun setValue(f: FormField, value: String) {
        val v = if (f.maxLen > 0) value.take(f.maxLen) else value
        session.setOverrides(FormValues.set(f.name, v, siblingOf(f), session.current.overrides))
    }

    private fun siblingOf(f: FormField) = fields.firstOrNull { it.name == f.name } ?: f

    /** Tap on a field: toggles boxes, opens the sheet for text and choices, starts signing for a signature field. */
    fun activate(f: FormField) {
        _focusId.value = f.id
        when {
            f.readOnly -> message("\"${f.title}\" is read-only in this PDF.")
            f.kind == FieldKind.SIGNATURE -> if (f.signed) message("This field already holds a digital signature.") else signInto(f)
            f.isToggle -> session.setOverrides(FormValues.toggled(f, session.current.overrides, fields))
            else -> _fieldEdit.value = FieldEdit(f.id)
        }
    }

    private fun signInto(f: FormField) {
        val s = selected()
        if (s != null && s.kind == ItemKind.SIGNATURE) {
            val aspect = sigAspect(s.sigId) ?: return
            val shape = if (s.turns % 2 == 0) aspect else 1f / aspect
            session.replaceItem(s.copy(page = f.page, rect = Placement.fitInto(f.rect, shape, pageAspect(f.page))))
        } else openSign(f.id)
    }

    /** Moves the focus to the next / previous fillable field (wrapping) and asks the screen to scroll there. */
    fun step(d: Int) {
        val list = _form.value.fillable
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == _focusId.value }
        val n = if (i < 0) (if (d > 0) 0 else list.lastIndex) else ((i + d) % list.size + list.size) % list.size
        focus(list[n])
    }

    fun focus(f: FormField) { _focusId.value = f.id; _jump.value = f.page to (_jump.value.second + 1) }

    /** From the field sheet's "Next": opens the next field's own editor when it needs one. */
    fun editNext(from: FormField) {
        val list = _form.value.fillable
        val i = list.indexOfFirst { it.id == from.id }
        val next = list.getOrNull(i + 1) ?: run { _fieldEdit.value = null; return }
        focus(next)
        _fieldEdit.value = if (next.isToggle) null else FieldEdit(next.id)
        if (next.isToggle) message("Next: ${next.title}. Tap it to tick or untick.")
    }

    // ---- placed items --------------------------------------------------------------------------------------------------------

    fun addTextAt(page: Int, x: Float, y: Float) {
        val id = session.newId()
        val rect = TextBoxMath.boxFor("", FillPalette.sizes[_textSize.value], pageAspect(page), x, y - 0.01f, 0.3f)
        session.addItem(FillItem(id, page, ItemKind.TEXT, rect, "", _textSize.value, _textColor.value))
        freshItem = id
        _selectedId.value = id
        _editingItem.value = id
    }

    fun addQuick(kind: ItemKind, text: String = "") {
        val page = currentPage()
        val aspect = pageAspect(page)
        val size = FillPalette.sizes[_textSize.value]
        val rect = if (kind == ItemKind.TEXT) TextBoxMath.boxFor(text, size, aspect, 0.35f, 0.4f, TextBoxMath.widthForLine(text, size))
        else NormRect(0.45f, 0.45f, 0.45f + size * 2.2f, 0.45f + size * 2.2f * aspect)
        val id = session.newId()
        session.addItem(FillItem(id, page, kind, rect, text, _textSize.value, _textColor.value))
        _selectedId.value = id
    }

    /** Puts [sig] on the page: centred on the current page, or fitted into the signature field named by the sign target. */
    fun placeSignature(sig: SigEntry) {
        val target = _signTarget.value
        _signTarget.value = null
        val field = fieldById(target)
        val page = field?.page ?: currentPage()
        val aspect = pageAspect(page)
        val rect = if (field != null) Placement.fitInto(field.rect, sig.aspect, aspect) else Placement.centered(sig.aspect, aspect)
        val id = session.newId()
        session.addItem(FillItem(id, page, ItemKind.SIGNATURE, rect, sigId = sig.id))
        _selectedId.value = id
        _tool.value = FillTool.FIELDS
    }

    fun deleteSelected() { selected()?.let { session.removeItem(it.id); _selectedId.value = null } }

    fun rotateSelected() {
        val s = selected() ?: return
        session.replaceItem(s.copy(rect = Placement.rotated(s.rect, pageAspect(s.page)), turns = (s.turns + 1) % 4))
    }

    fun scaleSelected(k: Float) {
        val s = selected() ?: return
        session.replaceItem(if (s.kind == ItemKind.TEXT) s.copy(rect = Placement.scaled(s.rect, k)) else s.copy(rect = Placement.scaled(s.rect, k)))
    }

    fun editSelectedText() { selected()?.takeIf { it.kind == ItemKind.TEXT }?.let { _editingItem.value = it.id } }

    private fun refit(i: FillItem): FillItem {
        if (i.kind != ItemKind.TEXT) return i
        val size = FillPalette.sizes[i.size.coerceIn(0, 2)]
        val r = TextBoxMath.boxFor(i.text, size, pageAspect(i.page), i.rect.left, i.rect.top, i.rect.right - i.rect.left)
        return i.copy(rect = r)
    }

    /** Called when a signature leaves the library: placed copies would have no picture. */
    fun usesSignature(id: String) = session.current.items.any { it.sigId == id }

    // ---- touch: tap, drag (move) and the resize handle ----------------------------------------------------------------

    /** The item being dragged; shown instead of the saved one until the gesture ends (one undo step). */
    var live by mutableStateOf<FillItem?>(null)
        private set
    private var mode = 0 // 1 move, 2 resize
    private var lastX = 0f; private var lastY = 0f

    override val claimsDrag: Boolean get() = _selectedId.value != null

    override fun claimsAt(hit: PageHit): Boolean {
        val s = selected() ?: return false
        return s.page == hit.page && (nearHandle(s, hit) || Placement.contains(s.rect, hit.x, hit.y, 0.01f))
    }

    private fun nearHandle(s: FillItem, hit: PageHit): Boolean {
        val dx = (hit.x - s.rect.right) * hit.aspect; val dy = hit.y - s.rect.bottom
        return kotlin.math.hypot(dx, dy) < 0.045f
    }

    override fun onStart(hit: PageHit) {
        val s = selected() ?: return
        mode = if (nearHandle(s, hit)) 2 else 1
        lastX = hit.x; lastY = hit.y
        live = s
    }

    override fun onMove(x: Float, y: Float) {
        val s = live ?: return
        live = if (mode == 2) s.copy(rect = Placement.resizeTo(s.rect, x, y, pageAspect(s.page)).let { if (s.kind == ItemKind.TEXT) widenOnly(s, it) else it })
        else s.copy(rect = Placement.moved(s.rect, x - lastX, y - lastY))
        lastX = x; lastY = y
    }

    private fun widenOnly(s: FillItem, r: NormRect) = r

    override fun onEnd() {
        val l = live ?: return
        live = null
        if (session.find(l.id) != l) session.replaceItem(l)
    }

    override fun onCancel() { live = null }

    override fun onTap(hit: PageHit): Boolean {
        val items = session.current.items
        val tapped = items.lastOrNull { it.page == hit.page && Placement.contains(it.rect, hit.x, hit.y, 0.004f) }
        if (tapped != null) {
            if (tapped.id == _selectedId.value && tapped.kind == ItemKind.TEXT) _editingItem.value = tapped.id
            _selectedId.value = tapped.id
            return true
        }
        val field = fields.lastOrNull { it.page == hit.page && Placement.contains(it.rect, hit.x, hit.y) }
        if (field != null && _tool.value == FillTool.FIELDS) { _selectedId.value = if (field.kind == FieldKind.SIGNATURE) _selectedId.value else null; activate(field); return true }
        if (_tool.value == FillTool.TEXT) { addTextAt(hit.page, hit.x, hit.y); return true }
        if (_selectedId.value != null) { _selectedId.value = null; return true }
        return false
    }

    /** Back to a clean slate (a document opened, saved or changes discarded). */
    fun reset(state: com.namanbansal.forge.core.pdf.fill.FillState = com.namanbansal.forge.core.pdf.fill.FillState()) {
        session.reset(state)
        _selectedId.value = null; _fieldEdit.value = null; _editingItem.value = null; _signTarget.value = null; live = null; freshItem = null
    }
}
