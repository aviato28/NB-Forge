package com.namanbansal.forge.device

import com.namanbansal.forge.core.device.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BarcodeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun url() {
        assertEquals(ScanContent.Url("https://forge.app/x?a=1", "https://forge.app/x?a=1"), BarcodeClassifier.classify("https://forge.app/x?a=1"))
        assertTrue(BarcodeClassifier.classify("HTTP://EXAMPLE.COM") is ScanContent.Url)
        assertEquals("https://www.example.com", (BarcodeClassifier.classify("www.example.com") as ScanContent.Url).url)
        assertTrue(BarcodeClassifier.classify("https://not a url") is ScanContent.Text)
    }

    @Test fun wifiWithEscapes() {
        val w = BarcodeClassifier.classify("WIFI:T:WPA;S:My\\;Net\\:5G;P:pa\\\\ss\\;word;H:true;;") as ScanContent.Wifi
        assertEquals("My;Net:5G", w.ssid); assertEquals("pa\\ss;word", w.password); assertEquals("WPA", w.security); assertTrue(w.hidden)
        val open = BarcodeClassifier.classify("WIFI:S:Cafe;T:nopass;;") as ScanContent.Wifi
        assertEquals("Cafe", open.ssid); assertEquals("", open.password); assertFalse(open.hidden)
        assertTrue(BarcodeClassifier.classify("WIFI:T:WPA;P:x;;") is ScanContent.Text) // no SSID
    }

    @Test fun vcard() {
        val raw = "BEGIN:VCARD\nVERSION:3.0\nN:Doe;Jane;;;\nFN:Jane Doe\nORG:Acme\\, Inc.\nTITLE:CTO\nTEL;TYPE=CELL:+1 555 0100\nTEL:+1 555 0101\nEMAIL:jane@acme.test\nADR:;;1 Main St;Springfield;;12345;\nEND:VCARD"
        val c = BarcodeClassifier.classify(raw) as ScanContent.Contact
        assertEquals("Jane Doe", c.name); assertEquals(listOf("+1 555 0100", "+1 555 0101"), c.phones)
        assertEquals(listOf("jane@acme.test"), c.emails); assertEquals("Acme, Inc.", c.org); assertEquals("CTO", c.title)
        assertTrue(c.address.contains("1 Main St"))
    }

    @Test fun vcardWithoutFnUsesN() {
        val c = BarcodeClassifier.classify("BEGIN:VCARD\nN:Doe;Jane;;Dr.;\nEND:VCARD") as ScanContent.Contact
        assertEquals("Dr. Jane Doe", c.name)
    }

    @Test fun meCard() {
        val c = BarcodeClassifier.classify("MECARD:N:Doe,John;TEL:123456;EMAIL:j@x.test;;") as ScanContent.Contact
        assertEquals("John Doe", c.name); assertEquals(listOf("123456"), c.phones); assertEquals(listOf("j@x.test"), c.emails)
    }

    @Test fun phoneEmailSmsGeo() {
        assertEquals("+4412345", (BarcodeClassifier.classify("tel:+4412345") as ScanContent.Phone).number)
        val e = BarcodeClassifier.classify("mailto:a@b.test?subject=Hi%20there&body=Line%201") as ScanContent.Email
        assertEquals("a@b.test", e.address); assertEquals("Hi there", e.subject); assertEquals("Line 1", e.body)
        val m = BarcodeClassifier.classify("MATMSG:TO:a@b.test;SUB:Hello;BODY:Body text;;") as ScanContent.Email
        assertEquals("Hello", m.subject)
        val s = BarcodeClassifier.classify("SMSTO:+123:See you at 5") as ScanContent.Sms
        assertEquals("+123", s.number); assertEquals("See you at 5", s.body)
        val g = BarcodeClassifier.classify("geo:48.8584,2.2945?q=Eiffel%20Tower") as ScanContent.Geo
        assertEquals(48.8584, g.lat, 1e-6); assertEquals(2.2945, g.lon, 1e-6); assertEquals("Eiffel Tower", g.query)
        assertTrue(BarcodeClassifier.classify("geo:999,2") is ScanContent.Text)
    }

    @Test fun productAndText() {
        assertTrue(BarcodeClassifier.classify("5901234123457", "EAN_13") is ScanContent.Product)
        assertTrue(BarcodeClassifier.classify("5901234123457", "CODE_128") is ScanContent.Text)
        assertEquals(ScanContent.Text("hello"), BarcodeClassifier.classify("hello"))
    }

    @Test fun historyDedupesTrimsAndSurvivesCorruption() {
        val f = tmp.newFile("h.json")
        var now = 1L
        val store = ScanHistoryStore(f) { now++ }
        assertTrue(store.load().isEmpty() || f.length() == 0L)
        store.add("a", "QR_CODE"); store.add("b", "QR_CODE")
        val l = store.add("a", "QR_CODE")
        assertEquals(listOf("a", "b"), l.map { it.raw })
        repeat(150) { store.add("x$it", "QR_CODE") }
        assertEquals(ScanHistoryStore.MAX, store.load().size)
        assertEquals("x149", store.load().first().raw)
        f.writeText("not json")
        assertTrue(store.load().isEmpty())
        store.add("z", "QR_CODE"); assertEquals(1, store.load().size)
        store.clear(); assertTrue(store.load().isEmpty())
    }

    @Test fun torchPatterns() {
        val sos = TorchPatterns.sos(100)
        assertEquals(9 + 0, sos.count { it.first })
        assertEquals(100L, sos[0].second); assertEquals(300L, sos[6].second)
        assertEquals(700L, sos.last().second)
        val st = TorchPatterns.strobe(10f)
        assertEquals(100L, st.sumOf { it.second })
        assertTrue(TorchPatterns.strobe(1000f).sumOf { it.second } >= 50L)
    }

    @Test fun imageFilterMatrices() {
        assertNull(ImageFilters.matrix(1f, false))
        val inv = ImageFilters.matrix(1f, true)!!
        assertEquals(-1f, inv[0], 0f); assertEquals(255f, inv[4], 0f)
        val hi = ImageFilters.matrix(2f, false)!!
        assertEquals(2f, hi[0], 0f); assertEquals(-128f, hi[4], 0f)
    }

    @Test fun uniqueNames() {
        val taken = setOf("a.png", "a (2).png")
        assertEquals("a (3).png", PictureSaver.uniqueName("a", "png") { it in taken })
        assertEquals("b.png", PictureSaver.uniqueName("b", "png") { it in taken })
    }
}
