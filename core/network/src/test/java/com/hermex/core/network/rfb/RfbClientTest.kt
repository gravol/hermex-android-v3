package com.hermex.core.network.rfb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the RFB decoder: handshake sequence, Raw, CopyRect,
 * Hextile, Tight-fill, and input event framing. Image encodings (JPEG /
 * PNG via BitmapFactory) are covered on-device, not here.
 */
class RfbClientTest {

    private class Pipe {
        val out = mutableListOf<ByteArray>()
        val client = RfbClient { bytes -> out.add(bytes) }

        /** Feed the whole server stream, then close it: run() ends on EOF, as on a real socket. */
        fun feed(vararg chunks: ByteArray) {
            chunks.forEach { client.feed(it) }
            client.closePipe()
        }

        /** Feed without closing — for the late-arrival test. */
        fun feedOpen(vararg chunks: ByteArray) { chunks.forEach { client.feed(it) } }

        /** All bytes the client has sent, concatenated. */
        fun sent(): ByteArray = out.fold(ByteArray(0)) { acc, b -> acc + b }
    }

    private fun serverInit(w: Int, h: Int, name: String = "test"): ByteArray {
        // Byte-for-byte the layout Xvnc on the bot desktop sends (verified against the live
        // rfb.sock): w,h + 16-byte pixel format + name-length, then the name.
        val m = ByteArray(24)
        m[0] = (w shr 8).toByte(); m[1] = w.toByte()
        m[2] = (h shr 8).toByte(); m[3] = h.toByte()
        m[4] = 32; m[5] = 24; m[6] = 0; m[7] = 1
        m[8] = 0; m[9] = 0xFF.toByte(); m[10] = 0; m[11] = 0xFF.toByte()
        m[12] = 0; m[13] = 0xFF.toByte()
        m[14] = 16; m[15] = 8; m[16] = 0
        val nb = name.toByteArray()
        val n = nb.size
        m[20] = (n shr 24).toByte(); m[21] = (n shr 16).toByte()
        m[22] = (n shr 8).toByte(); m[23] = n.toByte()
        return m + nb
    }

    private fun handshakeBytes(): List<ByteArray> = listOf(
        "RFB 003.008\n".toByteArray(),
        byteArrayOf(0x01, 0x01),           // offered type + TigerVNC extra
        byteArrayOf(0, 0, 0, 0),           // SecurityResult OK
        serverInit(4, 2),
    )

    @Test fun serverInit_desync_is_refused_not_allocated() {
        // 0.1.878 OOM-crashed allocating 1,702,050,416 bytes read as a desktop-name length
        // (ASCII "es:p"). The parser must refuse that and report it, never allocate it.
        val p = Pipe()
        val bad = serverInit(4, 2).copyOf(24)
        bad[20] = 0x65; bad[21] = 0x73; bad[22] = 0x3a; bad[23] = 0x70
        p.feed(
            "RFB 003.008\n".toByteArray(),
            byteArrayOf(0x01, 0x01),
            byteArrayOf(0, 0, 0, 0),
            bad,
        )
        p.client.run()
        val err = p.client.lastError ?: "no error"
        assertTrue("desync named: $err", err.contains("ServerInit desync"))
        assertTrue("bytes logged: $err", err.contains("65 73 3a 70"))
    }

    @Test fun serverInit_parses_the_live_xvnc_bytes() {
        // Golden capture from the bot desktop's rfb.sock (1440x900, "hermes:default").
        val p = Pipe()
        val init = ("05a003842018000100ff00ff00ff1008000000000000000e" +
            "6865726d65733a64656661756c74").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        p.feed("RFB 003.008\n".toByteArray(), byteArrayOf(0x01, 0x01), byteArrayOf(0, 0, 0, 0), init)
        p.client.run()
        assertEquals(1440, p.client.width)
        assertEquals(900, p.client.height)
        assertEquals("hermes:default", p.client.desktopName)
        assertEquals("rfb pipe closed", p.client.lastError) // clean end-of-stream, not a desync
    }

    @Test fun bogus_rect_length_is_refused_before_allocation() {
        // A Raw update claiming 4096x4096 = 67 MB: refuse it, don't allocate it.
        val p = Pipe()
        p.feed(*handshakeBytes().toTypedArray())
        p.feed(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0, 0x10, 0, 0x10, 0, 0, 0, 0, 0))
        p.client.run()
        val err = p.client.lastError ?: "no error"
        // Two guards cover this: the rect-bounds check (4096x4096 does not fit a 4x2
        // framebuffer) and the read-length cap. Either refusing before allocating is the
        // requirement; the bounds check fires first for this rect.
        assertTrue("allocation guard fired: $err",
            err.contains("bogus length") || err.contains("rect out of bounds"))
    }

    @Test fun handshake_completes_and_reports_geometry() {
        val p = Pipe()
        p.feed(*handshakeBytes().toTypedArray())
        // EOF after handshake: run() exits the loop.
        p.client.run()
        assertEquals(4, p.client.width)
        assertEquals(2, p.client.height)
        assertEquals("test", p.client.desktopName)
        val sent = p.sent()
        assertTrue("client echoes version",
            String(sent, 0, 12) == "RFB 003.008\n")
        assertTrue("client picks None", sent[12] == 0x01.toByte())
        assertTrue("client sends ClientInit shared", sent[13] == 0x01.toByte())
        // SetPixelFormat is NOT sent: Xvnc's default is already 32bpp depth-24 LE rgb888
        // @16/8/0, and TigerVNC closed the link on the packet we used to send. So the sent
        // layout is: 12 version + 1 security type + 1 ClientInit + SetEncodings(4+4n) +
        // FBURequest(10) = 44 bytes.
        assertTrue("post-init messages", sent.size >= 14 + 20 + 10)
        // TigerVNC numbering: SetEncodings = 2 (type 1 is reserved; Xvnc closes on it).
        assertEquals("SetEncodings type", 2, sent[14].toInt())
        assertEquals("encoding count is u16", 4, ((sent[16].toInt() and 0xFF) shl 8) or sent[17].toInt())
        val fb = sent.copyOfRange(34, 44)
        assertEquals("FBURequest type", 3, fb[0].toInt())
        assertEquals("FBURequest width", 4, ((fb[6].toInt() and 0xFF) shl 8) or fb[7].toInt())
        assertEquals("FBURequest height", 2, ((fb[8].toInt() and 0xFF) shl 8) or fb[9].toInt())
    }

    @Test fun handshake_waits_for_late_bytes() {
        // The bridge opens Xvnc's socket on connect, so the greeting lands a few ms AFTER
        // run() starts. The old parser threw EOF on the first empty poll — this is the
        // regression test for "display ws opens, zero bytes, handshake timeout".
        val p = Pipe()
        val t = Thread { p.client.run() }
        t.start()
        Thread.sleep(400)
        p.feedOpen(*handshakeBytes().toTypedArray())
        Thread.sleep(400)
        assertTrue("handshake survived the empty-queue window", p.client.connected)
        assertEquals(4, p.client.width)
        p.client.closePipe()
        t.join(5_000)
    }

    private fun fbUpdate(vararg rects: ByteArray): ByteArray {
        val head = byteArrayOf(0, 0, 0, rects.size.toByte())
        return head + rects.fold(ByteArray(0)) { a, b -> a + b }
    }

    private fun rectHeader(x: Int, y: Int, w: Int, h: Int, enc: Int): ByteArray {
        val m = ByteArray(12)
        m[0] = (x shr 8).toByte(); m[1] = x.toByte()
        m[2] = (y shr 8).toByte(); m[3] = y.toByte()
        m[4] = (w shr 8).toByte(); m[5] = w.toByte()
        m[6] = (h shr 8).toByte(); m[7] = h.toByte()
        m[8] = (enc shr 24).toByte(); m[9] = (enc shr 16).toByte()
        m[10] = (enc shr 8).toByte(); m[11] = enc.toByte()
        return m
    }

    @Test fun raw_update_fills_framebuffer() {
        val p = Pipe()
        // 4x2 raw, BGRA: pixel (0,0) = R=0x11 G=0x22 B=0x33
        val px = ByteArray(4 * 2 * 4)
        px[0] = 0x33; px[1] = 0x22; px[2] = 0x11; px[3] = 0
        p.feed(*handshakeBytes().toTypedArray())
        p.feed(fbUpdate(rectHeader(0, 0, 4, 2, 0) + px))
        p.client.run()
        assertEquals(0xFF112233.toInt(), p.client.pixels[0])
        assertEquals(1, p.client.frameSeq)
    }

    @Test fun copyrect_copies_region() {
        val p = Pipe()
        val px = ByteArray(4 * 2 * 4)
        px[0] = 0x33; px[1] = 0x22; px[2] = 0x11 // (0,0)
        val src = byteArrayOf(0, 0, 0, 0) // src x=0 y=0
        p.feed(*handshakeBytes().toTypedArray())
        p.feed(fbUpdate(rectHeader(0, 0, 4, 2, 0) + px))
        p.feed(fbUpdate(rectHeader(2, 1, 1, 1, -5) + src))
        p.client.run()
        // copy (0,0) → (2,1): index (1*4+2)=6
        assertEquals(0xFF112233.toInt(), p.client.pixels[6])
    }

    @Test fun tight_fill_replicates_pixel() {
        val p = Pipe()
        // Tight fill = control byte with numTopLevelColors == 7, then one R,G,B triple.
        val tight = rectHeader(0, 0, 2, 2, -260) + byteArrayOf(0x07, 0x33, 0x22, 0x11)
        p.feed(*handshakeBytes().toTypedArray())
        p.feed(fbUpdate(tight))
        p.client.run()
        for (i in intArrayOf(0, 1, 4, 5)) assertEquals(0xFF332211.toInt(), p.client.pixels[i])
    }

    @Test fun tight_zlib_indices_decode() {
        // The common desktop case: Basic encoding, explicit palette, zlib-compressed
        // palette indices. The old decoder never handled zlib at all.
        val p = Pipe()
        val idx = byteArrayOf(0, 1, 1, 0)
        val def = java.util.zip.Deflater(6)
        def.setInput(idx); def.finish()
        val buf = ByteArray(64)
        val n = def.deflate(buf)
        def.end()
        val comp = buf.copyOf(n)
        // Tight control byte: bits 7-5 = type (000 Fill, 001 Basic), bits 4-3 = filter
        // (0 Copy, 1 Palette, 2 Gradient), bits 2-0 = level. A palette arrives ONLY via
        // TightFilterPalette, whose filter byte carries (numColors - 1). The old test put
        // "explicit palette" in bit 5, which belongs to the type field — the decoder read
        // palette bytes that were never sent and desynced.
        // ctrl = Basic(type 001 = 0x20) + Palette filter (bits 4-3 = 01 = 0x08) + level 1
        // = 0x29. Bits 7-5 are the TYPE field: 0x09 has top three bits zero, which is Fill.
        var body = rectHeader(0, 0, 2, 2, -260) + byteArrayOf(0x29.toByte())
        body += byteArrayOf(0x01.toByte())          // filter byte: numColors-1 = 1 -> 2 colours
        body += byteArrayOf(0xFF.toByte(), 0, 0)     // palette[0] = red  (R,G,B)
        body += byteArrayOf(0, 0xFF.toByte(), 0)     // palette[1] = green
        body += byteArrayOf(comp.size.toByte())      // compact length (<128 = one byte)
        body += comp
        p.feed(*handshakeBytes().toTypedArray())
        p.feed(fbUpdate(body))
        p.client.run()
        assertEquals(0xFFFF0000.toInt(), p.client.pixels[0])
        assertEquals(0xFF00FF00.toInt(), p.client.pixels[1])
        assertEquals(0xFF00FF00.toInt(), p.client.pixels[4])
        assertEquals(0xFFFF0000.toInt(), p.client.pixels[5])
        assertEquals(null, p.client.lastError?.takeIf { it.startsWith("tight") })
    }

    @Test fun hextile_solid_bg_tile() {
        val p = Pipe()
        // 2x2, one 16x16 tile clipped to 2x2. RFC 6143: subencoding bit 0x02 =
        // BackgroundSpecified (0x01 is Raw). Hextile colours are R,G,B (like Tight), not
        // the framebuffer's B,G,R,X order.
        val tile = rectHeader(0, 0, 2, 2, -6) + byteArrayOf(0x02.toByte(), 0x33.toByte(), 0x22.toByte(), 0x11.toByte())
        p.feed(*handshakeBytes().toTypedArray())
        p.feed(fbUpdate(tile))
        p.client.run()
        for (i in intArrayOf(0, 1, 4, 5)) assertEquals(0xFF332211.toInt(), p.client.pixels[i])
    }

    @Test fun pointer_and_key_events_are_framed() {
        val p = Pipe()
        p.feed(*handshakeBytes().toTypedArray())
        p.client.run()
        val before = p.sent().size
        p.client.sendPointer(0.5f, 0.5f, 0x01)
        p.client.sendKey(0x41, true) // 'A'
        val sent = p.sent()
        val ptr = sent.copyOfRange(before, before + 6)
        assertEquals("pointer type", 5, ptr[0].toInt())
        assertEquals("button mask", 0x01, ptr[1].toInt())
        val x = ((ptr[2].toInt() and 0xFF) shl 8) or (ptr[3].toInt() and 0xFF)
        assertEquals("x = half of 4", 2, x)
        val key = sent.copyOfRange(before + 6, before + 14)
        assertEquals("key type", 4, key[0].toInt())
        assertEquals("down", 1, key[1].toInt())
        val ks = ((key[4].toInt() and 0xFF) shl 24) or ((key[5].toInt() and 0xFF) shl 16) or
            ((key[6].toInt() and 0xFF) shl 8) or (key[7].toInt() and 0xFF)
        assertEquals("keysym A", 0x41, ks)
    }

    @Test fun hextile_raw_tile_consumes_32bit_pixels() {
        // RFC 6143 subencoding bit 0x01 = Raw: the tile's pixel data is appended in the
        // server's pixel format (32bpp BGRA here). Reading it as 3-byte RGB triples consumes
        // 12 bytes for a 2x2 tile instead of 16 and desyncs every following rect.
        val p = Pipe()
        val tile = byteArrayOf(0x01.toByte(),                            // subencoding: Raw
            0x33.toByte(), 0x22.toByte(), 0x11.toByte(), 0x00.toByte(),   // (0,0) R=11 G=22 B=33
            0x44.toByte(), 0x55.toByte(), 0x66.toByte(), 0x00.toByte(),   // (1,0)
            0x77.toByte(), 0x88.toByte(), 0x99.toByte(), 0x00.toByte(),   // (0,1)
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0x00.toByte())   // (1,1)
        p.feed(*handshakeBytes().toTypedArray())
        p.feed(fbUpdate(rectHeader(0, 0, 2, 2, -6) + tile))
        p.client.run()
        assertEquals(0xFF112233.toInt(), p.client.pixels[0])
        assertEquals(0xFF665544.toInt(), p.client.pixels[1])
        assertEquals(0xFF998877.toInt(), p.client.pixels[4])
        assertEquals(0xFFCCBBAA.toInt(), p.client.pixels[5])
    }

    @Test fun hextile_background_and_foreground_bits() {
        // 0x02 = BackgroundSpecified, 0x04 = ForegroundSpecified, 0x08 = AnySubrects.
        // The old decoder read Background on 0x01 and Foreground on 0x02.
        val p = Pipe()
        val tile = byteArrayOf((0x02 or 0x04 or 0x08).toByte(),   // bg + fg + subrects
            0x11.toByte(), 0x22.toByte(), 0x33.toByte(),          // bg RGB
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),          // fg RGB
            0x01.toByte(),                                        // 1 subrect
            0x02.toByte(),                                        // subrect uses foreground
            0x11.toByte(),                                        // x=1, y=1
            0x00.toByte())                                        // w=1, h=1
        p.feed(*handshakeBytes().toTypedArray())
        p.feed(fbUpdate(rectHeader(0, 0, 2, 2, -6) + tile))
        p.client.run()
        assertEquals(0xFF112233.toInt(), p.client.pixels[0])  // bg
        assertEquals(0xFF112233.toInt(), p.client.pixels[1])
        assertEquals(0xFFAABBCC.toInt(), p.client.pixels[5])  // subrect at (1,1)
    }

    @Test fun out_of_bounds_rect_is_fatal() {
        // Framebuffer is 4x2. A rect at x=2 w=4 runs past the right edge — that is a desync,
        // and continuing prints garbage for the rest of the session.
        val p = Pipe()
        p.feed(*handshakeBytes().toTypedArray())
        p.feed(fbUpdate(rectHeader(2, 0, 4, 2, 0) + ByteArray(4 * 2 * 4)))
        p.client.run()
        assertTrue("rect bounds rejected",
            (p.client.lastError ?: "").contains("rect out of bounds"))
    }
}
