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
        val m = ByteArray(32)
        m[0] = (w shr 8).toByte(); m[1] = w.toByte()
        m[2] = (h shr 8).toByte(); m[3] = h.toByte()
        m[4] = 32; m[5] = 24; m[6] = 0; m[7] = 1
        m[8] = 0xFF.toByte(); m[10] = 0xFF.toByte(); m[12] = 0xFF.toByte()
        m[14] = 16; m[15] = 8; m[16] = 0
        val nb = name.toByteArray()
        val n = nb.size
        m[28] = (n shr 24).toByte(); m[29] = (n shr 16).toByte()
        m[30] = (n shr 8).toByte(); m[31] = n.toByte()
        return m + nb
    }

    private fun handshakeBytes(): List<ByteArray> = listOf(
        "RFB 003.008\n".toByteArray(),
        byteArrayOf(0x01, 0x01),           // offered type + TigerVNC extra
        byteArrayOf(0, 0, 0, 0),           // SecurityResult OK
        serverInit(4, 2),
    )

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
        // SetPixelFormat (20) + SetEncodings (4+16) + FBU request (10)
        assertTrue("post-init messages", sent.size >= 14 + 20 + 20 + 10)
        assertEquals("SetPixelFormat type", 0, sent[14].toInt())
        assertEquals("bpp 32", 32, sent[18].toInt())
        assertEquals("SetEncodings type", 2, sent[34].toInt())
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
        // Tight ctrl=0 (fill), 3-byte BGR pixel
        val tight = rectHeader(0, 0, 2, 2, -260) + byteArrayOf(0x00, 0x33, 0x22, 0x11)
        p.feed(*handshakeBytes().toTypedArray())
        p.feed(fbUpdate(tight))
        p.client.run()
        for (i in intArrayOf(0, 1, 4, 5)) assertEquals(0xFF112233.toInt(), p.client.pixels[i])
    }

    @Test fun hextile_solid_bg_tile() {
        val p = Pipe()
        // 2x2, one 16x16 tile clipped to 2x2, subencoding=0x01 (bg specified)
        val tile = rectHeader(0, 0, 2, 2, -6) + byteArrayOf(0x01, 0x33, 0x22, 0x11)
        p.feed(*handshakeBytes().toTypedArray())
        p.feed(fbUpdate(tile))
        p.client.run()
        for (i in intArrayOf(0, 1, 4, 5)) assertEquals(0xFF112233.toInt(), p.client.pixels[i])
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
}
