package com.namanbansal.forge.core

/** Coarse file categories used to match files to tools. */
enum class FileKind {
    PDF, IMAGE, VIDEO, AUDIO, OTHER;

    companion object {
        fun fromMime(mime: String?): FileKind {
            val m = mime?.lowercase()?.substringBefore(';')?.trim().orEmpty()
            return when {
                m == "application/pdf" || m == "application/x-pdf" -> PDF
                m.startsWith("image/") -> IMAGE
                m.startsWith("video/") -> VIDEO
                m.startsWith("audio/") -> AUDIO
                else -> OTHER
            }
        }

        fun fromName(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> PDF
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif" -> IMAGE
            "mp4", "mov", "mkv", "webm", "3gp", "avi" -> VIDEO
            "mp3", "wav", "m4a", "aac", "ogg", "flac" -> AUDIO
            else -> OTHER
        }

        /** MIME filter array for the SAF pickers. */
        fun pickerMimes(vararg kinds: FileKind): Array<String> = kinds.flatMap {
            when (it) {
                PDF -> listOf("application/pdf")
                IMAGE -> listOf("image/*")
                VIDEO -> listOf("video/*")
                AUDIO -> listOf("audio/*")
                OTHER -> listOf("*/*")
            }
        }.distinct().toTypedArray()
    }
}
