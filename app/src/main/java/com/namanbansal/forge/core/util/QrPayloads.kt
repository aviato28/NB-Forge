package com.namanbansal.forge.core.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Text for each QR type, escaped the way scanners expect (Wi-Fi `\ ; , : "`, vCard `\ ; , newline`). */
object QrPayloads {
    private fun wifiEscape(s: String): String = buildString { s.forEach { if (it in "\\;,:\"") append('\\'); append(it) } }

    /** `WIFI:T:WPA;S:name;P:pass;H:true;;`; [security] is WPA, WEP or nopass. */
    fun wifi(ssid: String, password: String, security: String = "WPA", hidden: Boolean = false): String {
        val sec = if (security.equals("nopass", true)) "nopass" else security.uppercase()
        return "WIFI:T:$sec;S:${wifiEscape(ssid)};" + (if (sec != "nopass") "P:${wifiEscape(password)};" else "") + (if (hidden) "H:true;" else "") + ";"
    }

    private fun vEscape(s: String): String = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    /** vCard 3.0. Blank fields are left out. */
    fun vcard(first: String, last: String, phone: String = "", email: String = "", org: String = "", title: String = "", url: String = "", address: String = ""): String = buildString {
        append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        append("N:${vEscape(last)};${vEscape(first)};;;\r\n")
        append("FN:${vEscape(listOf(first, last).filter { it.isNotBlank() }.joinToString(" "))}\r\n")
        if (org.isNotBlank()) append("ORG:${vEscape(org)}\r\n")
        if (title.isNotBlank()) append("TITLE:${vEscape(title)}\r\n")
        if (phone.isNotBlank()) append("TEL;TYPE=CELL:${vEscape(phone)}\r\n")
        if (email.isNotBlank()) append("EMAIL:${vEscape(email)}\r\n")
        if (url.isNotBlank()) append("URL:${vEscape(url)}\r\n")
        if (address.isNotBlank()) append("ADR:;;${vEscape(address)};;;;\r\n")
        append("END:VCARD")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun email(address: String, subject: String = "", body: String = ""): String {
        val q = listOfNotNull(subject.takeIf { it.isNotEmpty() }?.let { "subject=${enc(it)}" }, body.takeIf { it.isNotEmpty() }?.let { "body=${enc(it)}" })
        return "mailto:${address.trim()}" + if (q.isEmpty()) "" else "?" + q.joinToString("&")
    }

    fun phone(number: String): String = "tel:" + number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    fun sms(number: String, body: String = ""): String = "smsto:" + number.filter { it.isDigit() || it == '+' } + if (body.isNotEmpty()) ":$body" else ""

    /** Adds https:// when the text looks like a bare web address. */
    fun url(text: String): String {
        val t = text.trim()
        return if (t.contains("://") || t.startsWith("mailto:", true) || t.startsWith("tel:", true)) t else "https://$t"
    }
}

/** QR rendering on top of ZXing core (Apache-2.0). */
object QrRender {
    class TooLong : Exception("That is too much text for a QR code. Shorten it and try again.")

    fun matrix(text: String, level: ErrorCorrectionLevel = ErrorCorrectionLevel.M, margin: Int = 4): BitMatrix {
        if (text.isEmpty()) throw IllegalArgumentException("Nothing to encode")
        try {
            return QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.ERROR_CORRECTION to level, EncodeHintType.MARGIN to margin, EncodeHintType.CHARACTER_SET to "UTF-8"),
            )
        } catch (e: WriterException) { throw TooLong() }
    }

    /** ARGB pixels at [sizePx] square (rounded down to a whole number of modules so edges stay sharp). */
    fun pixels(m: BitMatrix, sizePx: Int, fg: Int, bg: Int): Pair<IntArray, Int> {
        val scale = max(1, sizePx / m.width)
        val side = m.width * scale
        val out = IntArray(side * side)
        for (y in 0 until side) { val my = y / scale; for (x in 0 until side) out[y * side + x] = if (m.get(x / scale, my)) fg else bg }
        return out to side
    }

    private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    fun luminance(argb: Int): Double = 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)

    /** WCAG contrast ratio, 1 to 21. */
    fun contrast(a: Int, b: Int): Double { val la = luminance(a); val lb = luminance(b); return (max(la, lb) + 0.05) / (min(la, lb) + 0.05) }

    /** Null when the colours should scan fine, else a short warning. Scanners expect dark modules on a light background. */
    fun warning(fg: Int, bg: Int): String? = when {
        contrast(fg, bg) < 3.0 -> "Low contrast: this code may not scan. Pick a darker colour or a lighter background."
        luminance(fg) > luminance(bg) -> "Light-on-dark codes are not read by every scanner. Test it before you print it."
        else -> null
    }
}
