package com.namanbansal.forge.core.pdf

import com.namanbansal.forge.core.ToolException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.IOException
import java.util.UUID

/**
 * The single policy for password-protected inputs: [PdfLoader] decrypts into a working copy so processing never deals
 * with encryption, and every tool writes its output with the SAME user password and permissions through [applyTo]
 * (AES-128). The owner password is random and not kept: only the user password the person typed is meaningful.
 * Tools that mix several sources use [forAll].
 */
class PdfProtection(val userPassword: String, val permissionBytes: Int) {

    /** Encrypts [doc] on its next save. Call after the document is complete, before `save`. */
    fun applyTo(doc: PDDocument) {
        try {
            val policy = StandardProtectionPolicy(UUID.randomUUID().toString(), userPassword, AccessPermission(permissionBytes))
            policy.encryptionKeyLength = 128
            doc.protect(policy)
        } catch (e: IOException) {
            throw ToolException("Could not keep the password on the saved copy: ${e.message ?: "encryption failed"}.", e)
        }
    }

    /** Short sentence for the options screen. */
    val note: String get() = if (userPassword.isEmpty()) "The saved copy keeps this PDF's restrictions." else "The saved copy keeps the password."

    companion object {
        /**
         * Protection for an output built from several sources: kept only when every source is encrypted with the same
         * password (then the output is protected with it); otherwise null and the output has no password.
         */
        fun forAll(sources: List<PdfSource>): PdfProtection? {
            if (sources.isEmpty() || sources.any { it.protection == null }) return null
            val first = sources.first().protection!!
            return if (sources.all { it.protection!!.userPassword == first.userPassword }) PdfProtection(first.userPassword, sources.fold(-1) { acc, s -> acc and s.protection!!.permissionBytes }) else null
        }
    }
}
