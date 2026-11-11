package com.namanbansal.forge.core.device

import java.net.URLDecoder

/** What a scanned code means. [raw] is always the exact text in the code. */
sealed interface ScanContent {
    val raw: String
    /** Short type name shown on the result. */
    val label: String

    data class Url(override val raw: String, val url: String) : ScanContent { override val label get() = "Link" }
    data class Wifi(override val raw: String, val ssid: String, val password: String, val security: String, val hidden: Boolean) : ScanContent { override val label get() = "Wi-Fi network" }
    data class Contact(
        override val raw: String, val name: String, val phones: List<String>, val emails: List<String>,
        val org: String = "", val title: String = "", val address: String = "", val url: String = "",
    ) : ScanContent { override val label get() = "Contact" }
    data class Phone(override val raw: String, val number: String) : ScanContent { override val label get() = "Phone number" }
    data class Email(override val raw: String, val address: String, val subject: String = "", val body: String = "") : ScanContent { override val label get() = "Email" }
    data class Sms(override val raw: String, val number: String, val body: String = "") : ScanContent { override val label get() = "Text message" }
    data class Geo(override val raw: String, val lat: Double, val lon: Double, val query: String = "") : ScanContent { override val label get() = "Location" }
    data class Product(override val raw: String, val format: String) : ScanContent { override val label get() = "Product code" }
    data class Text(override val raw: String) : ScanContent { override val label get() = "Text" }
}

object BarcodeClassifier {
    private val PRODUCT_FORMATS = setOf("EAN_13", "EAN_8", "UPC_A", "UPC_E", "ISBN")

    fun classify(raw: String, format: String = ""): ScanContent {
        val text = raw.trim()
        val lower = text.lowercase()
        return when {
            lower.startsWith("wifi:") -> parseWifi(raw, text) ?: ScanContent.Text(raw)
            lower.startsWith("begin:vcard") -> parseVCard(raw, text)
            lower.startsWith("mecard:") -> parseMeCard(raw, text)
            lower.startsWith("tel:") -> ScanContent.Phone(raw, text.substring(4).trim())
            lower.startsWith("mailto:") -> parseMailto(raw, text)
            lower.startsWith("matmsg:") -> parseMatMsg(raw, text)
            lower.startsWith("smsto:") || lower.startsWith("sms:") -> parseSms(raw, text)
            lower.startsWith("geo:") -> parseGeo(raw, text) ?: ScanContent.Text(raw)
            lower.startsWith("http://") || lower.startsWith("https://") -> if (text.any { it.isWhitespace() }) ScanContent.Text(raw) else ScanContent.Url(raw, text)
            lower.startsWith("www.") && !text.any { it.isWhitespace() } && text.contains('.') -> ScanContent.Url(raw, "https://$text")
            format in PRODUCT_FORMATS -> ScanContent.Product(raw, format)
            else -> ScanContent.Text(raw)
        }
    }

    /** Splits `K:v;K:v;;` honouring backslash escapes. Keys are upper-cased. */
    internal fun fields(body: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val cur = StringBuilder()
        var i = 0
        fun flush() {
            val s = cur.toString(); cur.clear()
            if (s.isEmpty()) return
            val colon = unescapedColon(s)
            if (colon > 0) out += s.substring(0, colon).uppercase() to unescape(s.substring(colon + 1))
        }
        while (i < body.length) {
            val ch = body[i]
            if (ch == '\\' && i + 1 < body.length) { cur.append(ch).append(body[i + 1]); i += 2; continue }
            if (ch == ';') flush() else cur.append(ch)
            i++
        }
        flush()
        return out
    }

    private fun unescapedColon(s: String): Int {
        var i = 0
        while (i < s.length) { if (s[i] == '\\') i += 2 else if (s[i] == ':') return i else i++ }
        return -1
    }

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2 } else { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }

    private fun parseWifi(raw: String, text: String): ScanContent? {
        val f = fields(text.substring(5)).toMap()
        val ssid = f["S"] ?: return null
        val security = (f["T"] ?: "nopass").let { if (it.isBlank()) "nopass" else it }
        return ScanContent.Wifi(raw, ssid, f["P"].orEmpty(), security, f["H"].equals("true", ignoreCase = true))
    }

    private fun parseMeCard(raw: String, text: String): ScanContent {
        val f = fields(text.substring(7))
        fun all(k: String) = f.filter { it.first == k }.map { it.second }
        val n = all("N").firstOrNull().orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.let { if (it.size == 2) "${it[1]} ${it[0]}" else it.joinToString(" ") }
        return ScanContent.Contact(raw, n, all("TEL"), all("EMAIL"), org = all("ORG").firstOrNull().orEmpty(), address = all("ADR").firstOrNull().orEmpty().replace(",", " ").trim(), url = all("URL").firstOrNull().orEmpty())
    }

    private fun parseVCard(raw: String, text: String): ScanContent {
        // Unfold continuation lines (a line starting with a space or tab continues the previous one).
        val lines = mutableListOf<String>()
        text.lines().forEach { l -> if ((l.startsWith(" ") || l.startsWith("\t")) && lines.isNotEmpty()) lines[lines.lastIndex] = lines.last() + l.substring(1) else lines += l.trimEnd('\r') }
        var name = ""; var n = ""; var org = ""; var title = ""; var adr = ""; var url = ""
        val phones = mutableListOf<String>(); val emails = mutableListOf<String>()
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).substringBefore(';').uppercase().substringAfterLast('.')
            val rawValue = line.substring(colon + 1)
            val value = vUnescape(rawValue)
            when (key) {
                "FN" -> name = value
                "N" -> n = splitUnescaped(rawValue).let { p -> listOf(p.getOrNull(3), p.getOrNull(1), p.getOrNull(2), p.getOrNull(0)).filter { !it.isNullOrBlank() }.joinToString(" ") }
                "TEL" -> if (value.isNotBlank()) phones += value
                "EMAIL" -> if (value.isNotBlank()) emails += value
                "ORG" -> org = splitUnescaped(rawValue).filter { it.isNotBlank() }.joinToString(", ")
                "TITLE" -> title = value
                "ADR" -> adr = splitUnescaped(rawValue).filter { it.isNotBlank() }.joinToString(", ")
                "URL" -> url = value
            }
        }
        return ScanContent.Contact(raw, name.ifBlank { n }, phones, emails, org, title, adr, url)
    }

    /** Splits a vCard structured value on `;` that is not escaped, then unescapes each part. */
    private fun splitUnescaped(raw: String): List<String> {
        val parts = mutableListOf<String>(); val cur = StringBuilder(); var i = 0
        while (i < raw.length) {
            if (raw[i] == '\\' && i + 1 < raw.length) { cur.append(raw[i]).append(raw[i + 1]); i += 2 }
            else if (raw[i] == ';') { parts += vUnescape(cur.toString()); cur.clear(); i++ }
            else { cur.append(raw[i]); i++ }
        }
        parts += vUnescape(cur.toString())
        return parts
    }

    private fun vUnescape(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                sb.append(when (val c = s[i + 1]) { 'n', 'N' -> '\n'; else -> c }); i += 2
            } else { sb.append(s[i]); i++ }
        }
        return sb.toString()
    }

    private fun parseMailto(raw: String, text: String): ScanContent {
        val rest = text.substring(7)
        val addr = decode(rest.substringBefore('?'))
        val q = rest.substringAfter('?', "")
        val params = q.split('&').filter { it.contains('=') }.associate { it.substringBefore('=').lowercase() to decode(it.substringAfter('=')) }
        return ScanContent.Email(raw, addr, params["subject"].orEmpty(), params["body"].orEmpty())
    }

    private fun parseMatMsg(raw: String, text: String): ScanContent {
        val f = fields(text.substring(7)).toMap()
        return ScanContent.Email(raw, f["TO"].orEmpty(), f["SUB"].orEmpty(), f["BODY"].orEmpty())
    }

    private fun parseSms(raw: String, text: String): ScanContent {
        val rest = text.substringAfter(':')
        val number = rest.substringBefore(':').substringBefore('?')
        val body = if (rest.contains(':')) rest.substringAfter(':') else rest.substringAfter("body=", "").let { decode(it) }
        return ScanContent.Sms(raw, number, body)
    }

    private fun parseGeo(raw: String, text: String): ScanContent? {
        val rest = text.substring(4)
        val coords = rest.substringBefore('?').substringBefore(';').split(',')
        val lat = coords.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = coords.getOrNull(1)?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val q = rest.substringAfter("q=", "").substringBefore('&')
        return ScanContent.Geo(raw, lat, lon, decode(q))
    }

    private fun decode(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { s }
}
