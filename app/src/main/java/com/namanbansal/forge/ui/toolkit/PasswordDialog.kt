package com.namanbansal.forge.ui.toolkit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.namanbansal.forge.core.pdf.PasswordPrompt
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

/** "This PDF is password protected" dialog with a wrong-password message. */
@Composable
fun PasswordDialog(prompt: PasswordPrompt, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    val c = ForgeTheme.colors
    var password by rememberSaveable(prompt.fileName) { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.surface,
        title = { Text("Password needed", style = ForgeType.Section, color = c.text) },
        text = {
            Column {
                Text("\"${prompt.fileName}\" is protected. Enter its password to open it.", style = ForgeType.Small, color = c.text2)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    isError = prompt.wrong,
                    supportingText = { if (prompt.wrong) Text("That password is wrong. Try again.") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onSubmit(password) }),
                    trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show", color = c.accent) } },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.accent, unfocusedBorderColor = c.border, cursorColor = c.accent,
                        focusedTextColor = c.text, unfocusedTextColor = c.text, focusedLabelColor = c.accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) { Text("Unlock", color = c.accent) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = c.text2) } },
    )
}
