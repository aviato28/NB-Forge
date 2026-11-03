package com.namanbansal.forge.tools

import com.namanbansal.forge.core.FileKind
import com.namanbansal.forge.ui.icons.ForgeIcons

/**
 * Every tool Forge offers. Names and descriptions come from docs/Forge-Longlist.pdf.
 * To ship a tool: add its screen to [ToolScreens], and add its code to [AVAILABLE]. See docs/ADDING_A_TOOL.md.
 */
object ToolRegistry {

    /** Codes of tools that have a real implementation. Everything else shows the "Coming soon" screen. */
    private val AVAILABLE: Set<String> = setOf("P1", "P2", "P3", "P4", "P5", "P6", "P16", "P17", "P18", "P19", "P25", "P27", "P40")

    /**
     * Unambiguous labels for tiles and chips where [ToolDef.name] alone is not enough
     * ("Compress" exists for PDF, image and video). Tools not listed use their name.
     * A unit test guarantees every resulting label is unique.
     */
    private val SHORT_LABELS: Map<String, String> = mapOf(
        "P1" to "Merge PDF", "P2" to "Split PDF", "P4" to "Rotate PDF", "P7" to "Crop PDF", "P8" to "Booklet",
        "P9" to "Compress PDF", "P10" to "PDF grayscale", "P11" to "Flatten PDF", "P12" to "Repair PDF",
        "P13" to "Sanitise PDF", "P14" to "Linearise PDF", "P15" to "Edit PDF text", "P16" to "Add to PDF",
        "P17" to "Annotate PDF", "P18" to "Whiteout", "P19" to "Redact PDF", "P20" to "Watermark PDF",
        "P21" to "Page numbers", "P22" to "PDF bookmarks", "P23" to "PDF metadata", "P24" to "PDF links",
        "P27" to "Sign PDF", "P28" to "Digital sign", "P29" to "Protect PDF", "P30" to "Unlock PDF",
        "P33" to "PDF → text", "P35" to "Office → PDF", "P37" to "OCR PDF", "P38" to "PDF assets",
        "P39" to "Compare PDFs", "P40" to "PDF viewer", "P41" to "PDF batch",
        "I1" to "Resize image", "I2" to "Compress image", "I3" to "Convert image", "I4" to "Remove background",
        "I6" to "Colour picker", "I7" to "Upscale image", "I10" to "Text overlay", "I12" to "Annotate image",
        "V1" to "Trim video", "V2" to "Compress video", "V3" to "Convert video", "V8" to "Video speed", "V9" to "Mute video",
        "D3" to "Protractor", "D8" to "QR scanner", "F1" to "Zip files",
    )

    /** File kinds a tool can take as input (drives "Open with" and "Next" suggestions). Defaults by category. */
    private val ACCEPTS_OVERRIDES: Map<String, Set<FileKind>> = mapOf(
        "P32" to setOf(FileKind.IMAGE), "P35" to emptySet(), "P43" to emptySet(),
    )

    private fun acceptsFor(code: String, category: Category): Set<FileKind> = ACCEPTS_OVERRIDES[code] ?: when (category) {
        Category.PDF -> setOf(FileKind.PDF)
        Category.IMAGE -> setOf(FileKind.IMAGE)
        Category.VIDEO -> setOf(FileKind.VIDEO)
        else -> emptySet()
    }

    private fun tool(
        code: String,
        name: String,
        category: Category,
        group: String,
        description: String,
        keywords: List<String>,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
    ) = ToolDef(
        code = code,
        name = name,
        category = category,
        group = group,
        description = description,
        keywords = keywords,
        icon = icon,
        shortLabel = SHORT_LABELS[code] ?: name,
        accepts = acceptsFor(code, category),
        status = if (code in AVAILABLE) ToolStatus.Available else ToolStatus.ComingSoon,
    )

    val all: List<ToolDef> by lazy {
        listOf(
        tool("P1", "Merge", Category.PDF, PdfGroup.ORGANISE, "Combine multiple PDFs into one, drag to set order.", listOf("combine", "join", "append"), ForgeIcons.Merge),
        tool("P2", "Split", Category.PDF, PdfGroup.ORGANISE, "By page range, every N pages, by bookmarks, or by target file size.", listOf("separate", "divide", "pages"), ForgeIcons.Split),
        tool("P3", "Page manager", Category.PDF, PdfGroup.ORGANISE, "Thumbnail grid: reorder, delete, duplicate pages by drag & drop.", listOf("reorder", "delete pages", "organize", "arrange"), ForgeIcons.Grid),
        tool("P4", "Rotate", Category.PDF, PdfGroup.ORGANISE, "Rotate single pages or the whole document.", listOf("turn", "orientation"), ForgeIcons.Rotate),
        tool("P5", "Extract pages", Category.PDF, PdfGroup.ORGANISE, "Pull selected pages out into a new PDF.", listOf("select", "pages", "subset"), ForgeIcons.Extract),
        tool("P6", "Insert pages", Category.PDF, PdfGroup.ORGANISE, "Add blank pages or pages from another PDF anywhere.", listOf("add", "blank", "page"), ForgeIcons.Plus),
        tool("P7", "Crop / trim", Category.PDF, PdfGroup.ORGANISE, "Crop margins manually or auto-detect whitespace.", listOf("margins", "trim", "whitespace"), ForgeIcons.Crop),
        tool("P8", "N-up / booklet", Category.PDF, PdfGroup.ORGANISE, "2 or 4 pages per sheet, booklet imposition for printing.", listOf("print", "imposition", "booklet", "nup"), ForgeIcons.Layers),
        tool("P9", "Compress", Category.PDF, PdfGroup.OPTIMISE, "Presets (screen / ebook / print) plus custom DPI and image quality.", listOf("shrink", "reduce", "size", "smaller", "optimize"), ForgeIcons.Compress),
        tool("P10", "Grayscale", Category.PDF, PdfGroup.OPTIMISE, "Convert colour PDF to black & white to save ink and size.", listOf("black and white", "greyscale", "mono", "ink"), ForgeIcons.Drop),
        tool("P11", "Flatten", Category.PDF, PdfGroup.OPTIMISE, "Bake forms, annotations and layers into the page.", listOf("forms", "annotations", "layers"), ForgeIcons.Layers),
        tool("P12", "Repair", Category.PDF, PdfGroup.OPTIMISE, "Attempt to fix corrupted or broken PDFs.", listOf("fix", "corrupt", "broken", "recover"), ForgeIcons.Shield),
        tool("P13", "Sanitise", Category.PDF, PdfGroup.OPTIMISE, "Strip metadata, embedded files, JavaScript, hidden content.", listOf("sanitize", "clean", "privacy", "metadata", "javascript"), ForgeIcons.Eraser),
        tool("P14", "Linearise", Category.PDF, PdfGroup.OPTIMISE, "Optimise for fast web view (page 1 loads first).", listOf("linearize", "web", "fast view", "optimize"), ForgeIcons.Flash),
        tool("P15", "Edit existing text", Category.PDF, PdfGroup.EDIT, "True inline text editing. The hardest tool on the list.", listOf("edit", "text", "modify"), ForgeIcons.Type),
        tool("P16", "Add content", Category.PDF, PdfGroup.EDIT, "Text boxes, shapes, images, lines, arrows.", listOf("text box", "shapes", "insert", "image"), ForgeIcons.Plus),
        tool("P17", "Annotate", Category.PDF, PdfGroup.EDIT, "Highlight, underline, strike through, draw with a pen and add sticky notes.", listOf("highlight", "markup", "notes", "comment"), ForgeIcons.Pen),
        tool("P18", "Whiteout & replace", Category.PDF, PdfGroup.EDIT, "Cover content and type over it.", listOf("whiteout", "cover", "replace"), ForgeIcons.Eraser),
        tool("P19", "True redaction", Category.PDF, PdfGroup.EDIT, "Actually deletes content underneath; search-to-redact emails, phones, etc.", listOf("redact", "black out", "privacy", "hide"), ForgeIcons.Shield),
        tool("P20", "Watermark", Category.PDF, PdfGroup.EDIT, "Text or image, opacity, angle, tiling.", listOf("stamp", "brand", "overlay"), ForgeIcons.Drop),
        tool("P21", "Page numbers / headers", Category.PDF, PdfGroup.EDIT, "Page numbers, headers, footers, Bates numbering.", listOf("numbering", "footer", "header", "bates"), ForgeIcons.Hash),
        tool("P22", "Bookmarks", Category.PDF, PdfGroup.EDIT, "Create and edit the outline / table of contents.", listOf("outline", "toc", "contents"), ForgeIcons.Bookmark),
        tool("P23", "Metadata", Category.PDF, PdfGroup.EDIT, "Edit title, author, subject, keywords.", listOf("properties", "title", "author"), ForgeIcons.Info),
        tool("P24", "Hyperlinks", Category.PDF, PdfGroup.EDIT, "Add, edit and remove links.", listOf("links", "url"), ForgeIcons.Link),
        tool("P25", "Fill forms", Category.PDF, PdfGroup.FORMS, "Fill existing form fields.", listOf("form", "fields", "fill in"), ForgeIcons.FileText),
        tool("P26", "Create forms", Category.PDF, PdfGroup.FORMS, "Add text fields, checkboxes, dropdowns, signature boxes.", listOf("form builder", "fields", "checkbox"), ForgeIcons.Plus),
        tool("P27", "Sign", Category.PDF, PdfGroup.FORMS, "Draw, type or upload a signature; saved signature library.", listOf("signature", "esign", "sign document"), ForgeIcons.Pen),
        tool("P28", "Digital signatures", Category.PDF, PdfGroup.FORMS, "Certificate-based signing and signature verification.", listOf("certificate", "verify", "pki"), ForgeIcons.Shield),
        tool("P29", "Protect", Category.PDF, PdfGroup.SECURITY, "Password + permissions (no print, no copy, etc.).", listOf("password", "encrypt", "lock", "secure"), ForgeIcons.Lock),
        tool("P30", "Unlock", Category.PDF, PdfGroup.SECURITY, "Remove password when you know it.", listOf("decrypt", "remove password", "unlock"), ForgeIcons.Unlock),
        tool("P31", "PDF → images", Category.PDF, PdfGroup.CONVERT, "PNG / JPG / WebP per page at chosen DPI.", listOf("pdf to image", "png", "jpg", "webp", "export"), ForgeIcons.Image),
        tool("P32", "Images → PDF", Category.PDF, PdfGroup.CONVERT, "Photos/scans into a PDF, page size and margins.", listOf("image to pdf", "photos", "jpg to pdf"), ForgeIcons.FileText),
        tool("P33", "PDF → text / Markdown", Category.PDF, PdfGroup.CONVERT, "Extract readable text.", listOf("pdf to text", "markdown", "txt", "extract text"), ForgeIcons.Type),
        tool("P34", "PDF → Office", Category.PDF, PdfGroup.CONVERT, "Word / Excel / PowerPoint. Heavy, quality varies.", listOf("word", "excel", "powerpoint", "docx", "xlsx", "pptx"), ForgeIcons.Swap),
        tool("P35", "Office / HTML → PDF", Category.PDF, PdfGroup.CONVERT, "Usually needs a server or LibreOffice engine.", listOf("word to pdf", "html", "docx", "convert"), ForgeIcons.Swap),
        tool("P36", "PDF/A", Category.PDF, PdfGroup.CONVERT, "Archival-standard conversion.", listOf("archive", "pdfa", "archival"), ForgeIcons.Archive),
        tool("P37", "OCR", Category.PDF, PdfGroup.CONVERT, "Make scanned PDFs searchable and selectable.", listOf("text recognition", "scanned", "searchable"), ForgeIcons.Scan),
        tool("P38", "Extract assets", Category.PDF, PdfGroup.CONVERT, "Pull out embedded images and fonts.", listOf("images", "fonts", "embedded"), ForgeIcons.Extract),
        tool("P39", "Compare", Category.PDF, PdfGroup.ANALYSE, "Visual and text diff between two PDFs.", listOf("diff", "difference", "changes"), ForgeIcons.Diff),
        tool("P40", "Viewer", Category.PDF, PdfGroup.ANALYSE, "Fast viewer with search.", listOf("read", "open", "view"), ForgeIcons.Eye),
        tool("P41", "Batch mode", Category.PDF, PdfGroup.ANALYSE, "Run any tool on many files at once.", listOf("bulk", "many files", "batch"), ForgeIcons.Copy),
        tool("P42", "Workflows", Category.PDF, PdfGroup.ANALYSE, "Chain steps: merge → compress → watermark → protect, saved as recipes.", listOf("recipes", "automation", "chain"), ForgeIcons.Flow),
        tool("P43", "Scan to PDF", Category.PDF, PdfGroup.ANALYSE, "Phone camera scan with edge detect, deskew, enhance.", listOf("camera", "scanner", "document scan"), ForgeIcons.Scan),
        tool("I1", "Resize / crop", Category.IMAGE, Category.IMAGE.title, "Pixel, percent or preset sizes (social, passport, etc.).", listOf("resize", "crop", "dimensions", "passport"), ForgeIcons.Crop),
        tool("I2", "Compress", Category.IMAGE, Category.IMAGE.title, "Shrink file size with quality slider and preview.", listOf("shrink", "reduce", "jpg", "smaller"), ForgeIcons.Compress),
        tool("I3", "Convert", Category.IMAGE, Category.IMAGE.title, "HEIC, WebP, AVIF, PNG, JPG, SVG, ICO.", listOf("heic", "webp", "avif", "png", "jpg", "format"), ForgeIcons.Swap),
        tool("I4", "Background remover", Category.IMAGE, Category.IMAGE.title, "On-device AI cut-out.", listOf("remove background", "cutout", "transparent"), ForgeIcons.Wand),
        tool("I5", "Strip EXIF", Category.IMAGE, Category.IMAGE.title, "Remove location and camera data.", listOf("exif", "metadata", "gps", "location", "privacy"), ForgeIcons.Eraser),
        tool("I6", "Colour picker / palette", Category.IMAGE, Category.IMAGE.title, "Pick colours, extract palette from image.", listOf("color", "colour", "palette", "hex"), ForgeIcons.Palette),
        tool("I7", "Upscale", Category.IMAGE, Category.IMAGE.title, "AI enlarge without blur.", listOf("enlarge", "ai", "super resolution"), ForgeIcons.Sparkle),
        tool("I9", "Collage", Category.IMAGE, Category.IMAGE.title, "Grid layouts from multiple images.", listOf("grid", "layout", "combine photos"), ForgeIcons.Grid),
        tool("I10", "Text overlay / meme", Category.IMAGE, Category.IMAGE.title, "Add captions and text to images.", listOf("caption", "meme", "text"), ForgeIcons.Type),
        tool("I11", "Image ↔ Base64", Category.IMAGE, Category.IMAGE.title, "Encode/decode images as data URIs.", listOf("base64", "data uri", "encode", "decode"), ForgeIcons.Code),
        tool("I12", "Annotate / blur", Category.IMAGE, Category.IMAGE.title, "Arrows, boxes, blur or pixelate sensitive areas.", listOf("blur", "pixelate", "markup", "arrow"), ForgeIcons.Pen),
        tool("V1", "Trim / cut", Category.VIDEO, Category.VIDEO.title, "Cut start/end or remove sections.", listOf("trim", "cut", "clip"), ForgeIcons.Scissors),
        tool("V2", "Compress", Category.VIDEO, Category.VIDEO.title, "Shrink video to a target size.", listOf("shrink", "reduce", "mp4", "smaller"), ForgeIcons.Compress),
        tool("V3", "Convert", Category.VIDEO, Category.VIDEO.title, "MP4, WebM, MOV, MP3, WAV, etc.", listOf("mp4", "webm", "mov", "mp3", "wav", "format"), ForgeIcons.Swap),
        tool("V4", "Video → GIF", Category.VIDEO, Category.VIDEO.title, "Clip to GIF with fps and size control.", listOf("gif", "animation"), ForgeIcons.Film),
        tool("V5", "Extract audio", Category.VIDEO, Category.VIDEO.title, "Rip the audio track from video.", listOf("audio", "mp3", "sound", "rip"), ForgeIcons.Music),
        tool("V8", "Speed", Category.VIDEO, Category.VIDEO.title, "Speed up / slow down.", listOf("fast", "slow motion", "timelapse"), ForgeIcons.Gauge),
        tool("V9", "Mute", Category.VIDEO, Category.VIDEO.title, "Strip audio from video.", listOf("remove audio", "silent"), ForgeIcons.Mute),
        tool("D1", "Spirit level", Category.DEVICE, Category.DEVICE.title, "Bubble level using the phone's tilt sensor.", listOf("level", "bubble", "tilt", "leveller"), ForgeIcons.Level),
        tool("D2", "Ruler", Category.DEVICE, Category.DEVICE.title, "On-screen ruler, calibrated.", listOf("measure", "length", "cm", "inch"), ForgeIcons.Ruler),
        tool("D3", "Protractor / angle", Category.DEVICE, Category.DEVICE.title, "Measure angles with camera or tilt.", listOf("angle", "degrees", "protractor"), ForgeIcons.Angle),
        tool("D4", "Compass", Category.DEVICE, Category.DEVICE.title, "Heading with calibration.", listOf("heading", "north", "bearing", "direction"), ForgeIcons.Compass),
        tool("D5", "Sound meter", Category.DEVICE, Category.DEVICE.title, "dB level from the mic.", listOf("decibel", "db", "noise", "loudness"), ForgeIcons.Mic),
        tool("D6", "Flashlight", Category.DEVICE, Category.DEVICE.title, "Torch toggle plus strobe/SOS.", listOf("torch", "light", "strobe", "sos"), ForgeIcons.Torch),
        tool("D7", "Magnifier", Category.DEVICE, Category.DEVICE.title, "Camera zoom with freeze frame.", listOf("zoom", "magnify"), ForgeIcons.ZoomIn),
        tool("D8", "QR / barcode scanner", Category.DEVICE, Category.DEVICE.title, "Scan and act on codes.", listOf("qr", "barcode", "scan code"), ForgeIcons.Qr),
        tool("D9", "Light meter", Category.DEVICE, Category.DEVICE.title, "Lux estimate from camera.", listOf("lux", "brightness"), ForgeIcons.Sun),
        tool("D10", "Vibration meter", Category.DEVICE, Category.DEVICE.title, "Seismograph from accelerometer.", listOf("seismograph", "vibration", "accelerometer"), ForgeIcons.Pulse),
        tool("U1", "Unit converter", Category.UTILITIES, Category.UTILITIES.title, "Length, weight, temp, volume, data, etc.", listOf("units", "convert", "metric", "imperial"), ForgeIcons.Swap),
        tool("U2", "Currency converter", Category.UTILITIES, Category.UTILITIES.title, "Live rates (needs an API).", listOf("currency", "exchange", "forex", "money"), ForgeIcons.Currency),
        tool("U3", "Calculator", Category.UTILITIES, Category.UTILITIES.title, "Scientific with history.", listOf("calc", "maths", "math", "scientific"), ForgeIcons.Calc),
        tool("U4", "Percent / tip / split", Category.UTILITIES, Category.UTILITIES.title, "Quick money maths.", listOf("percentage", "tip", "split bill"), ForgeIcons.Percent),
        tool("U5", "Date calculator", Category.UTILITIES, Category.UTILITIES.title, "Difference between dates, age, add/subtract days.", listOf("date", "age", "days between"), ForgeIcons.Calendar),
        tool("U6", "Timezone converter", Category.UTILITIES, Category.UTILITIES.title, "World clock and meeting planner.", listOf("time zone", "world clock", "meeting"), ForgeIcons.Globe),
        tool("U7", "Timers", Category.UTILITIES, Category.UTILITIES.title, "Stopwatch, countdown, Pomodoro.", listOf("timer", "stopwatch", "countdown", "pomodoro"), ForgeIcons.Clock),
        tool("U8", "Randomiser", Category.UTILITIES, Category.UTILITIES.title, "Number, coin, dice, list picker.", listOf("random", "randomizer", "dice", "coin"), ForgeIcons.Dice),
        tool("U9", "Counter / tally", Category.UTILITIES, Category.UTILITIES.title, "Tap counter with multiple tallies.", listOf("tally", "count", "clicker"), ForgeIcons.Counter),
        tool("U10", "Loan / EMI", Category.UTILITIES, Category.UTILITIES.title, "Repayments, interest, amortisation table.", listOf("loan", "emi", "mortgage", "interest"), ForgeIcons.Bank),
        tool("U11", "BMI / health calcs", Category.UTILITIES, Category.UTILITIES.title, "BMI, BMR, calories.", listOf("bmi", "bmr", "calories", "health"), ForgeIcons.Heart),
        tool("U12", "QR generator", Category.UTILITIES, Category.UTILITIES.title, "URL, Wi-Fi, contact, text QR codes.", listOf("qr code", "generate", "wifi"), ForgeIcons.Qr),
        tool("F1", "Zip / unzip", Category.FILES, Category.FILES.title, "Create and extract archives.", listOf("zip", "unzip", "archive", "compress files"), ForgeIcons.Archive),
        )
    }

    private val byCode: Map<String, ToolDef> by lazy { all.associateBy { it.code } }

    fun get(code: String): ToolDef? = byCode[code]

    fun inCategory(category: Category): List<ToolDef> = all.filter { it.category == category }

    fun count(category: Category): Int = all.count { it.category == category }

    /** Tools of a category grouped into sections, in display order. */
    fun grouped(category: Category): List<Pair<String, List<ToolDef>>> {
        val tools = inCategory(category)
        val order = if (category == Category.PDF) PdfGroup.ORDER else listOf(category.title)
        return order.mapNotNull { g -> tools.filter { it.group == g }.takeIf { it.isNotEmpty() }?.let { g to it } }
    }

    /** Merge, Split, Viewer, Page manager: the tools that exist and are used most. */
    val DEFAULT_PINS = listOf("P1", "P2", "P40", "P3")

    /** The defaults of releases before the visual page tools; users still on exactly these are moved to [DEFAULT_PINS]. */
    val LEGACY_DEFAULT_PINS = listOf("P9", "P1", "P43", "D1")

    /** Hard cap on pinned tools. */
    const val MAX_PINS = 8

    /** Available tools that can take every one of [kinds] (e.g. a shared PDF), in registry order. */
    fun availableFor(kinds: Set<FileKind>, tools: List<ToolDef> = all): List<ToolDef> =
        tools.filter { t -> t.status == ToolStatus.Available && kinds.isNotEmpty() && t.accepts.containsAll(kinds) }
}
