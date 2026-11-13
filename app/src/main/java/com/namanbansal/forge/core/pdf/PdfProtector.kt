package com.namanbansal.forge.core.pdf

import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import java.util.UUID

/** What the person may still do with a protected PDF. */
data class PdfPermissions(
    val print: Boolean = true, val copy: Boolean = true, val modify: Boolean = true,
    val annotate: Boolean = true, val forms: Boolean = true,
) {
    fun toAccess(): AccessPermission = AccessPermission().apply {
        setCanPrint(print); setCanPrintDegraded(print)
        setCanExtractContent(copy); setCanExtractForAccessibility(true)
        setCanModify(modify); setCanAssembleDocument(modify)
        setCanModifyAnnotations(annotate); setCanFillInForm(forms)
    }
}

/** Builds the AES-256 protection for the Protect tool. */
object PdfProtector {
    /** @return protection that [PdfProtection.applyTo] writes (user password opens the file, a random owner password if none given) */
    fun protection(user: String, owner: String, perms: PdfPermissions): PdfProtection {
        if (user.isEmpty() && perms == PdfPermissions()) throw ToolException("Set a password to open the file, or restrict at least one permission.")
        return PdfProtection(user, perms.toAccess().permissionBytes, ownerPassword = owner.ifEmpty { UUID.randomUUID().toString() }, keyLength = 256)
    }
}

/** Rough password strength 0 (empty) .. 4 (strong): length and variety, with a short list of obvious choices penalised. */
object PasswordStrength {
    private val common = setOf("password", "12345678", "123456789", "qwertyui", "letmein1", "iloveyou", "11111111", "abc12345", "password1")

    fun score(pw: String): Int {
        if (pw.isEmpty()) return 0
        if (pw.lowercase() in common) return 1
        var classes = 0
        if (pw.any { it.isLowerCase() }) classes++
        if (pw.any { it.isUpperCase() }) classes++
        if (pw.any { it.isDigit() }) classes++
        if (pw.any { !it.isLetterOrDigit() }) classes++
        var s = when { pw.length < 6 -> 1; pw.length < 8 -> 2; pw.length < 12 -> 3; else -> 4 }
        if (classes <= 1) s = minOf(s, 2)
        else if (classes == 2) s = minOf(s, 3)
        if (pw.toSet().size <= 3) s = 1
        return s
    }

    fun label(score: Int) = when (score) { 0 -> ""; 1 -> "Weak"; 2 -> "Fair"; 3 -> "Good"; else -> "Strong" }
}
