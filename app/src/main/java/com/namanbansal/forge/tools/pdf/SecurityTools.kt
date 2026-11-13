package com.namanbansal.forge.tools.pdf

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.namanbansal.forge.core.ToolException
import com.namanbansal.forge.core.pdf.PasswordStrength
import com.namanbansal.forge.core.pdf.PdfPermissions
import com.namanbansal.forge.core.pdf.PdfProtection
import com.namanbansal.forge.core.pdf.PdfProtector
import com.namanbansal.forge.core.pdf.PdfSource
import com.namanbansal.forge.core.pdf.PdfTransformViewModel
import com.namanbansal.forge.core.pdf.TransformCtx
import com.namanbansal.forge.tools.ToolHost
import com.namanbansal.forge.ui.components.ForgeTextField
import com.namanbansal.forge.ui.components.GroupLabel
import com.namanbansal.forge.ui.components.InfoNote
import com.namanbansal.forge.ui.components.ToggleRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType
import com.namanbansal.forge.ui.toolkit.PdfToolScreen
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** P29 Protect: AES-256. Passwords are deliberately kept in memory only (never in saved state). */
class ProtectViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P29"
    override val action = "protected"
    override val headline get() = "Protected"

    private val _user = MutableStateFlow(""); val user: StateFlow<String> = _user.asStateFlow()
    private val _confirm = MutableStateFlow(""); val confirm: StateFlow<String> = _confirm.asStateFlow()
    private val _owner = MutableStateFlow(""); val owner: StateFlow<String> = _owner.asStateFlow()
    private val permsState = persisted("protect.perms", 31, decode = { it as? Int }) // bits: print copy modify annotate forms
    val permBits: StateFlow<Int> = permsState.flow

    fun setUser(v: String) { _user.value = v }
    fun setConfirm(v: String) { _confirm.value = v }
    fun setOwner(v: String) { _owner.value = v }
    fun setPerm(bit: Int, on: Boolean) { permsState.value = if (on) permsState.value or (1 shl bit) else permsState.value and (1 shl bit).inv() }

    fun permissions(bits: Int = permsState.value) = PdfPermissions(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0, bits and 8 != 0, bits and 16 != 0)
    val mismatch: Boolean get() = _user.value != _confirm.value
    fun problem(): String? = when {
        mismatch -> "The passwords don't match."
        _user.value.isEmpty() && permissions() == PdfPermissions() -> "Set a password, or restrict at least one permission."
        _user.value.isEmpty() && _owner.value.isEmpty() -> "Restrictions need an owner password, otherwise anyone could undo them."
        else -> null
    }

    override fun validate(source: PdfSource) { problem()?.let { throw ToolException(it) } }
    override fun protectionFor(source: PdfSource): PdfProtection = PdfProtector.protection(_user.value, _owner.value, permissions())
    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String =
        (if (_user.value.isNotEmpty()) "Password to open set" else "Restrictions only (opens without a password)") + ", AES-256"
}

@Composable
fun StrengthMeter(password: String, modifier: Modifier = Modifier) {
    val c = ForgeTheme.colors
    val score = PasswordStrength.score(password)
    val tint = when (score) { 1 -> Color(0xFFD64545); 2 -> Color(0xFFE0A030); 3 -> c.accent; else -> Color(0xFF3FA66B) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..4) Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i <= score) tint else c.border))
        }
        Text(if (score == 0) "Strength" else "Strength: ${PasswordStrength.label(score)}", style = ForgeType.Small, color = c.text2, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun ProtectScreen(host: ToolHost, vm: ProtectViewModel = viewModel()) {
    val user by vm.user.collectAsStateWithLifecycle(); val confirm by vm.confirm.collectAsStateWithLifecycle()
    val owner by vm.owner.collectAsStateWithLifecycle(); val bits by vm.permBits.collectAsStateWithLifecycle()
    val problem = run { vm.permissions(bits); vm.problem() }
    PdfToolScreen(
        host, vm, "Choose a PDF to protect", "Protect",
        actionEnabled = problem == null, actionHint = problem, showProtectionNote = false,
    ) { s, _ ->
        if (s.wasEncrypted) InfoNote("This file already has a password. The new settings replace it.", Modifier.padding(bottom = 8.dp))
        GroupLabel("Password to open")
        PasswordField(user, vm::setUser, "Password (leave empty for none)")
        StrengthMeter(user, Modifier.padding(bottom = 8.dp))
        PasswordField(confirm, vm::setConfirm, "Confirm password", if (confirm.isNotEmpty() && confirm != user) "Doesn't match" else null)
        GroupLabel("Permissions")
        val rows = listOf("Printing", "Copying text and images", "Editing the document", "Adding comments", "Filling in forms")
        rows.forEachIndexed { i, t -> ToggleRow(t, bits and (1 shl i) != 0, { vm.setPerm(i, it) }, Modifier.padding(bottom = 6.dp)) }
        GroupLabel("Owner password")
        PasswordField(owner, vm::setOwner, "Owner password (to change these later)")
        InfoNote("Encrypted with AES-256. If you forget the password the file can't be recovered. Some viewers ignore permission settings; the open password is the only strong lock.", Modifier.padding(top = 8.dp))
    }
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String? = null) {
    androidx.compose.material3.OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) }, isError = error != null,
        supportingText = error?.let { { Text(it, style = ForgeType.Small) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
    )
}

/** P30 Unlock: the password was asked for when the file opened; the saved copy has none. */
class UnlockViewModel(application: Application, saved: SavedStateHandle) : PdfTransformViewModel(application, saved) {
    override val toolCode = "P30"
    override val action = "unlocked"
    override val headline get() = "Unlocked"
    override fun validate(source: PdfSource) { if (!source.wasEncrypted) throw ToolException("This PDF has no password or restrictions to remove.") }
    override fun protectionFor(source: PdfSource): PdfProtection? = null
    override fun transform(doc: PDDocument, source: PdfSource, ctx: TransformCtx): String =
        if (source.protection?.userPassword.isNullOrEmpty()) "Restrictions removed" else "Password removed"
}

@Composable
fun UnlockScreen(host: ToolHost, vm: UnlockViewModel = viewModel()) {
    PdfToolScreen(host, vm, "Choose a PDF to unlock", "Unlock", showProtectionNote = false) { s, _ ->
        val p = s.protection
        when {
            !s.wasEncrypted -> InfoNote("This PDF has no password and no restrictions, so there is nothing to remove.")
            p != null && p.userPassword.isEmpty() -> InfoNote("This PDF opens freely but is restricted (for example no printing or copying). Unlock saves a copy without those restrictions.")
            else -> InfoNote("You entered the password, so Forge can open this PDF. Unlock saves a copy that opens without any password.")
        }
        InfoNote("Forge only removes protection when you can already open the file. It cannot guess or crack a password you don't know. Only unlock files you're allowed to.", Modifier.padding(top = 8.dp))
    }
}
