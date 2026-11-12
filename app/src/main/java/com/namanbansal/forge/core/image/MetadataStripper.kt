package com.namanbansal.forge.core.image

import android.media.ExifInterface
import com.namanbansal.forge.core.ToolException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

enum class StripMode { ALL, LOCATION }

/** One readable metadata line, e.g. group "Location", label "Latitude". */
data class MetaItem(val group: String, val label: String, val value: String)

/**
 * Removes metadata WITHOUT re-encoding: JPEG / PNG / WebP are rewritten chunk by chunk, so the picture data is byte-identical.
 * JPEG "all" drops APP1 (EXIF + XMP), APP13 (Photoshop / IPTC) and comments; the orientation is kept as a tiny EXIF block so the
 * picture still shows upright. ICC colour profiles stay. HEIC and other formats cannot be rewritten safely and are refused.
 */
object MetadataStripper {
    enum class Kind { JPEG, PNG, WEBP }

    fun kindOf(b: ByteArray): Kind? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> Kind.JPEG
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte() -> Kind.PNG
        b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> Kind.WEBP
        else -> null
    }

    fun strip(data: ByteArray, mode: StripMode, tmpDir: File): ByteArray = when (kindOf(data)) {
        Kind.JPEG -> if (mode == StripMode.ALL) stripJpegAll(data) else stripJpegLocation(data, tmpDir)
        Kind.PNG -> stripPng(data, mode)
        Kind.WEBP -> stripWebp(data, mode)
        null -> throw ToolException("Only JPG, PNG and WebP can be cleaned without re-saving the picture. For other formats, convert to JPG first.")
    }

    // ---- JPEG ------------------------------------------------------------------------------------------------

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    /** Walks the segments before the scan data: (marker, start, end) with [start, end) covering marker + length + payload. */
    private fun jpegSegments(b: ByteArray): Pair<List<Triple<Int, Int, Int>>, Int> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        var p = 2
        while (p + 4 <= b.size) {
            if (b[p] != 0xFF.toByte()) throw ToolException("This JPEG looks damaged.")
            var m = b[p + 1].toInt() and 0xFF
            if (m == 0xFF) { p++; continue } // padding
            if (m == 0xDA) return out to p        // start of scan: everything after is picture data
            if (m == 0xD9) return out to p
            if (m in 0xD0..0xD7 || m == 0x01) { out += Triple(m, p, p + 2); p += 2; continue }
            val len = u16(b, p + 2)
            if (len < 2 || p + 2 + len > b.size) throw ToolException("This JPEG looks damaged.")
            out += Triple(m, p, p + 2 + len)
            p += 2 + len
        }
        return out to p
    }

    private fun isExif(b: ByteArray, s: Int, e: Int) = e - s > 10 && String(b, s + 4, 4, Charsets.ISO_8859_1) == "Exif"

    /** Orientation tag (1..8) read straight from the EXIF block; 1 when absent. */
    fun jpegOrientation(b: ByteArray): Int {
        val (segs, _) = jpegSegments(b)
        val seg = segs.firstOrNull { it.first == 0xE1 && isExif(b, it.second, it.third) } ?: return 1
        val t = seg.second + 10 // TIFF header start
        if (seg.third - t < 8) return 1
        val le = b[t] == 'I'.code.toByte()
        fun r16(o: Int) = if (le) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) else u16(b, o)
        fun r32(o: Int) = if (le) r16(o) or (r16(o + 2) shl 16) else (r16(o) shl 16) or r16(o + 2)
        val ifd = t + r32(t + 4)
        if (ifd + 2 > seg.third) return 1
        val n = r16(ifd)
        for (i in 0 until n) {
            val e = ifd + 2 + i * 12
            if (e + 12 > seg.third) break
            if (r16(e) == 0x0112) return r16(e + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }

    private fun orientationBlock(o: Int): ByteArray {
        val bb = ByteArrayOutputStream()
        bb.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, 34))
        bb.write("Exif".toByteArray(Charsets.ISO_8859_1)); bb.write(0); bb.write(0)
        bb.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8)) // TIFF header, big endian, IFD0 at 8
        bb.write(byteArrayOf(0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, o.toByte(), 0, 0)) // one SHORT entry: orientation
        bb.write(byteArrayOf(0, 0, 0, 0)) // no next IFD
        return bb.toByteArray()
    }

    fun stripJpegAll(b: ByteArray): ByteArray {
        val orientation = jpegOrientation(b)
        val (segs, scan) = jpegSegments(b)
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        var wroteOrientation = orientation == 1
        for ((m, s, e) in segs) {
            if (!wroteOrientation && m != 0xE0) { out.write(orientationBlock(orientation)); wroteOrientation = true }
            if (m == 0xE1 || m == 0xED || m == 0xFE) continue // EXIF / XMP, Photoshop-IPTC, comment
            out.write(b, s, e - s)
        }
        if (!wroteOrientation) out.write(orientationBlock(orientation))
        out.write(b, scan, b.size - scan)
        return out.toByteArray()
    }

    private fun stripJpegLocation(b: ByteArray, tmpDir: File): ByteArray {
        // XMP can carry coordinates too: drop XMP blocks that mention GPS, then let ExifInterface rewrite only the EXIF block.
        val (segs, scan) = jpegSegments(b)
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 2)
        for ((m, s, e) in segs) {
            if (m == 0xE1 && !isExif(b, s, e) && String(b, s, e - s, Charsets.ISO_8859_1).contains("GPS", ignoreCase = true)) continue
            out.write(b, s, e - s)
        }
        out.write(b, scan, b.size - scan)
        val tmp = File.createTempFile("forge-exif", ".jpg", tmpDir)
        try {
            tmp.writeBytes(out.toByteArray())
            val exif = ExifInterface(tmp.absolutePath)
            GPS_TAGS.forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF, ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_VERSION_ID,
    )

    // ---- PNG -------------------------------------------------------------------------------------------------

    private fun stripPng(b: ByteArray, mode: StripMode): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 8)
        var p = 8
        while (p + 12 <= b.size) {
            val len = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (len < 0 || p + 12 + len > b.size) throw ToolException("This PNG looks damaged.")
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            val drop = when (type) {
                "eXIf" -> true
                "tEXt", "zTXt", "tIME" -> mode == StripMode.ALL
                "iTXt" -> mode == StripMode.ALL || String(b, p + 8, minOf(len, 24), Charsets.ISO_8859_1).startsWith("XML:com.adobe.xmp")
                else -> false
            }
            if (!drop) out.write(b, p, 12 + len)
            p += 12 + len
            if (type == "IEND") break
        }
        return out.toByteArray()
    }

    // ---- WebP ------------------------------------------------------------------------------------------------

    private fun stripWebp(b: ByteArray, @Suppress("UNUSED_PARAMETER") mode: StripMode): ByteArray {
        val body = ByteArrayOutputStream(b.size)
        var p = 12
        fun le32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        while (p + 8 <= b.size) {
            val type = String(b, p, 4, Charsets.ISO_8859_1)
            val len = le32(p + 4)
            val padded = len + (len and 1)
            if (len < 0 || p + 8 + padded > b.size + 1) throw ToolException("This WebP looks damaged.")
            val end = minOf(b.size, p + 8 + padded)
            if (type == "EXIF" || type == "XMP ") { p = end; continue }
            val chunk = b.copyOfRange(p, end)
            if (type == "VP8X" && chunk.size > 8) chunk[8] = (chunk[8].toInt() and 0x08.inv() and 0x04.inv()).toByte() // clear EXIF + XMP flags
            body.write(chunk)
            p = end
        }
        val payload = body.toByteArray()
        val out = ByteArrayOutputStream(payload.size + 12)
        out.write("RIFF".toByteArray()); val size = payload.size + 4
        out.write(byteArrayOf(size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte()))
        out.write("WEBP".toByteArray()); out.write(payload)
        return out.toByteArray()
    }

    // ---- Reading ---------------------------------------------------------------------------------------------

    /** Human readable metadata present in [data] (empty when none). Never throws. */
    fun read(data: ByteArray): List<MetaItem> = try {
        val e = ExifInterface(ByteArrayInputStream(data))
        val items = ArrayList<MetaItem>()
        fun add(group: String, label: String, tag: String, fmt: (String) -> String = { it }) {
            e.getAttribute(tag)?.takeIf { it.isNotBlank() }?.let { items += MetaItem(group, label, fmt(it.trim())) }
        }
        val ll = FloatArray(2)
        if (e.getLatLong(ll)) {
            items += MetaItem("Location", "Latitude", "%.6f".format(ll[0].toDouble()))
            items += MetaItem("Location", "Longitude", "%.6f".format(ll[1].toDouble()))
        } else if (GPS_TAGS.any { e.getAttribute(it) != null }) items += MetaItem("Location", "GPS data", "present")
        e.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.let { items += MetaItem("Location", "Altitude", "%.0f m".format(it)) }
        add("Camera", "Make", ExifInterface.TAG_MAKE); add("Camera", "Model", ExifInterface.TAG_MODEL)
        add("Camera", "Lens", "LensModel"); add("Camera", "Software", ExifInterface.TAG_SOFTWARE)
        add("Camera", "ISO", ExifInterface.TAG_ISO_SPEED_RATINGS)
        add("Camera", "Aperture", ExifInterface.TAG_F_NUMBER) { "f/$it" }
        add("Camera", "Exposure", ExifInterface.TAG_EXPOSURE_TIME) { s -> s.toDoubleOrNull()?.let { if (it in 0.0..0.99) "1/${Math.round(1 / it)} s" else "$s s" } ?: s }
        add("Camera", "Focal length", ExifInterface.TAG_FOCAL_LENGTH) { s -> s.split('/').let { p -> if (p.size == 2 && p[1].toDoubleOrNull() != 0.0) "%.0f mm".format(p[0].toDouble() / p[1].toDouble()) else s } }
        add("Date", "Taken", ExifInterface.TAG_DATETIME_ORIGINAL); add("Date", "Modified", ExifInterface.TAG_DATETIME)
        add("Other", "Artist", ExifInterface.TAG_ARTIST); add("Other", "Copyright", ExifInterface.TAG_COPYRIGHT)
        if (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 2..8) add("Other", "Orientation", ExifInterface.TAG_ORIENTATION) { o -> "Rotated or mirrored ($o)" }
        items
    } catch (_: Throwable) {
        emptyList()
    }

    fun hasLocation(items: List<MetaItem>) = items.any { it.group == "Location" }
}
