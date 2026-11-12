package com.namanbansal.forge.core

/** Pure naming rules for output files: `<original>_<action>.<ext>`, de-duplicated as `... (1)`. */
object OutputNaming {
    private val illegal = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")
    private const val MAX_BASE = 100

    /** Original name without its extension, made safe for any filesystem. */
    fun baseName(original: String): String {
        val noExt = original.substringBeforeLast('.', original).ifBlank { original }
        return noExt.replace(illegal, "_").trim().trim('.').take(MAX_BASE).ifBlank { "file" }
    }

    /**
     * Builds the first free name. [taken] answers "does a file with this exact display name already exist?".
     * If the original already ends with `_<action>` it is not repeated (invoice_rotated.pdf -> invoice_rotated (1).pdf).
     */
    fun build(original: String, action: String, ext: String, taken: (String) -> Boolean): String {
        val base = baseName(original)
        val suffix = "_$action"
        val stem = if (base.endsWith(suffix)) base else base + suffix
        val cleanExt = ext.trimStart('.')
        var candidate = "$stem.$cleanExt"
        var n = 1
        while (taken(candidate)) {
            candidate = "$stem ($n).$cleanExt"
            n++
            if (n > 10_000) return "$stem ${System.currentTimeMillis()}.$cleanExt"
        }
        return candidate
    }

    /** The file's own name made safe, de-duplicated as `name (1).pdf` (no action suffix). */
    fun keep(original: String, taken: (String) -> Boolean): String {
        val ext = original.substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all(Char::isLetterOrDigit) } ?: ""
        val base = baseName(original)
        val dot = if (ext.isEmpty()) "" else ".$ext"
        var candidate = base + dot
        var n = 1
        while (taken(candidate)) {
            candidate = "$base ($n)$dot"
            n++
            if (n > 10_000) return "$base ${System.currentTimeMillis()}$dot"
        }
        return candidate
    }

    /** What the user typed in the Rename box, made safe; ".pdf" is added when they left the extension off. Null for an empty name. */
    fun renamed(typed: String, defaultExt: String = "pdf"): String? {
        val t = typed.trim()
        if (t.isEmpty()) return null
        val hasExt = t.substringAfterLast('.', "").let { it.length in 1..5 && it.all(Char::isLetterOrDigit) } && t.contains('.')
        val base = baseName(if (hasExt) t else "$t.$defaultExt")
        val ext = if (hasExt) t.substringAfterLast('.') else defaultExt
        return "$base.$ext"
    }
}
