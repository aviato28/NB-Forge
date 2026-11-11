package com.namanbansal.forge.util

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.namanbansal.forge.core.device.BarcodeClassifier
import com.namanbansal.forge.core.device.ScanContent
import com.namanbansal.forge.core.util.QrPayloads
import com.namanbansal.forge.core.util.QrRender
import org.junit.Assert.*
import org.junit.Test

class QrPayloadsTest {
    private val bs = "\\"

    @Test fun wifiEscapesSpecials() {
        val w = QrPayloads.wifi("My;Net:5G", "pa" + bs + "ss;w,\"d", "WPA", hidden = true)
        assertEquals("WIFI:T:WPA;S:My" + bs + ";Net" + bs + ":5G;P:pa" + bs + bs + "ss" + bs + ";w" + bs + ",$bs\"d;H:true;;", w)
        assertEquals("WIFI:T:nopass;S:Cafe;;", QrPayloads.wifi("Cafe", "ignored", "nopass"))
        assertEquals("WIFI:T:WEP;S:a;P:b;;", QrPayloads.wifi("a", "b", "wep"))
    }

    @Test fun wifiRoundTripsThroughTheScannerParser() {
        val w = BarcodeClassifier.classify(QrPayloads.wifi("Café; \"Guest\":1", "p;a:s,s\\word", "WPA", true)) as ScanContent.Wifi
        assertEquals("Café; \"Guest\":1", w.ssid); assertEquals("p;a:s,s\\word", w.password); assertTrue(w.hidden); assertEquals("WPA", w.security)
    }

    @Test fun vcardEscapesAndRoundTrips() {
        val v = QrPayloads.vcard("Jane", "Doe, Jr.", phone = "+1 555 0100", email = "j@x.test", org = "Acme; Inc", title = "CTO", url = "https://x.test", address = "1 Main St, Town")
        assertTrue(v.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n")); assertTrue(v.endsWith("END:VCARD"))
        assertTrue(v.contains("N:Doe" + bs + ", Jr.;Jane;;;")); assertTrue(v.contains("ORG:Acme" + bs + "; Inc"))
        val c = BarcodeClassifier.classify(v) as ScanContent.Contact
        assertEquals("Jane Doe, Jr.", c.name); assertEquals(listOf("+1 555 0100"), c.phones); assertEquals(listOf("j@x.test"), c.emails); assertEquals("Acme; Inc", c.org); assertEquals("CTO", c.title)
        assertFalse(QrPayloads.vcard("A", "B").contains("TEL"))
        assertTrue(QrPayloads.vcard("A", "B", address = "x\ny").contains("ADR:;;x" + bs + "ny;;;;"))
    }

    @Test fun emailPhoneSmsUrl() {
        assertEquals("mailto:a@b.test?subject=Hi%20there%20%26%20you&body=Line%201%0ALine%202", QrPayloads.email("a@b.test", "Hi there & you", "Line 1\nLine 2"))
        assertEquals("mailto:a@b.test", QrPayloads.email(" a@b.test "))
        val e = BarcodeClassifier.classify(QrPayloads.email("a@b.test", "Hi there & you", "x")) as ScanContent.Email
        assertEquals("Hi there & you", e.subject)
        assertEquals("tel:+15550100", QrPayloads.phone("+1 (555) 0100")); assertEquals("smsto:+123:hi", QrPayloads.sms("+1-23", "hi"))
        assertEquals("https://example.com/x", QrPayloads.url(" example.com/x ")); assertEquals("http://a.test", QrPayloads.url("http://a.test"))
    }

    private fun decode(text: String, level: ErrorCorrectionLevel = ErrorCorrectionLevel.M): String {
        val m = QrRender.matrix(text, level)
        val (px, side) = QrRender.pixels(m, 400, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        return QRCodeReader().decode(BinaryBitmap(GlobalHistogramBinarizer(RGBLuminanceSource(side, side, px))), mapOf(com.google.zxing.DecodeHintType.PURE_BARCODE to true)).text
    }

    @Test fun renderedCodeDecodesBackToThePayload() {
        for (t in listOf("https://forge.nbansal28.com/get?x=1", "hello", "Zażółć gęślą jaźń ✓", QrPayloads.wifi("Home", "secret"), QrPayloads.vcard("Jane", "Doe", phone = "123"))) assertEquals(t, decode(t))
        assertEquals("H level", decode("H level", ErrorCorrectionLevel.H))
    }

    @Test fun tooMuchTextAndEmptyAreFriendly() {
        try { QrRender.matrix("x".repeat(5000)); fail() } catch (e: QrRender.TooLong) { assertTrue(e.message!!.contains("too much")) }
        try { QrRender.matrix(""); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun pixelScaleKeepsWholeModules() {
        val m = QrRender.matrix("a")
        val (px, side) = QrRender.pixels(m, 1000, 0xFF112233.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(0, side % m.width); assertTrue(side <= 1000); assertEquals(side * side, px.size); assertTrue(px.any { it == 0xFF112233.toInt() })
        assertEquals(m.width, QrRender.pixels(m, 5, 0, 0).second)
    }

    @Test fun contrastWarnings() {
        assertEquals(21.0, QrRender.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertNull(QrRender.warning(0xFF000000.toInt(), 0xFFFFFFFF.toInt())); assertNull(QrRender.warning(0xFF18181B.toInt(), 0xFFFFF1E8.toInt()))
        assertNotNull(QrRender.warning(0xFFCCCCCC.toInt(), 0xFFFFFFFF.toInt())); assertTrue(QrRender.warning(0xFFFFFFFF.toInt(), 0xFF000000.toInt())!!.contains("Light-on-dark"))
    }
}
