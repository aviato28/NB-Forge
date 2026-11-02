package com.namanbansal.forge.tools

import androidx.compose.ui.graphics.vector.ImageVector
import com.namanbansal.forge.core.FileKind

enum class Category(val title: String) {
    PDF("PDF"),
    IMAGE("Image"),
    VIDEO("Video & Audio"),
    DEVICE("Device"),
    UTILITIES("Utilities"),
    FILES("Files"),
}

/** Section headers inside the PDF category, in display order. */
object PdfGroup {
    const val ORGANISE = "Organise"
    const val OPTIMISE = "Optimise"
    const val EDIT = "Edit"
    const val FORMS = "Forms & Signing"
    const val SECURITY = "Security"
    const val CONVERT = "Convert"
    const val ANALYSE = "Analyse"
    val ORDER = listOf(ORGANISE, OPTIMISE, EDIT, FORMS, SECURITY, CONVERT, ANALYSE)
}

enum class ToolStatus { Available, ComingSoon }

/**
 * The tool contract. Every tool in Forge is one [ToolDef] in [ToolRegistry].
 * [group] is the section within a category (only PDF has several); other categories use the category title.
 */
data class ToolDef(
    val code: String,
    val name: String,
    val category: Category,
    val group: String,
    val description: String,
    val keywords: List<String>,
    val icon: ImageVector,
    /** Unambiguous label for pinned tiles and chips ("Compress PDF"); equals [name] when that is already clear. */
    val shortLabel: String,
    /** Kinds of file this tool takes as input; empty for tools that do not start from a file. */
    val accepts: Set<FileKind>,
    val status: ToolStatus,
)
