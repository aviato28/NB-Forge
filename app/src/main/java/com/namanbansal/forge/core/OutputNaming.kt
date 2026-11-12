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

    /** [raw] kept as the file name (made safe), only de-duplicated: `photo.jpg`, `photo (1).jpg`. */
    fun unique(raw: String, taken: (String) -> Boolean): String {
        val clean = raw.replace(illegal, "_").trim().ifBlank { "file" }
        val stem = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "")
        var candidate = clean
        var n = 1
        while (taken(candidate)) {
            candidate = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
            if (++n > 10_000) break
        }
        return candidate
    }
}
