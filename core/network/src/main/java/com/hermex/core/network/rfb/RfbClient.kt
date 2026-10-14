package com.hermex.core.network.rfb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Native RFB (VNC) client — Tier 3 Bot Screen.
 *
 * Talks raw RFB bytes over a byte-pipe (the gateway's
 * `/api/display/ws?display_ticket=…` WebSocket, binary frames). The
 * handshake sequence below was verified byte-for-byte against the
 * gateway's TigerVNC Xvnc (`-SecurityTypes None`):
 *
 *   server: "RFB 003.008\n"
 *   client: "RFB 003.008\n"
 *   server: 2 bytes (offered security type + TigerVNC extra)
 *   client: 0x01 (None)
 *   server: 4-byte SecurityResult (0 = OK)
 *   client: 0x01 (ClientInit, shared=1)
 *   server: ServerInit (24 bytes + name)
 *
 * Decoders: Raw(0), CopyRect(-5), Hextile(-6), Tight(-260), TightPng(-261).
 * Input: pointer events + key events. The framebuffer is an IntArray of
 * 0xAARRGGBB pixels; the UI layer blits it into a Bitmap.
 */
class RfbClient(
    private val sink: (ByteArray) -> Unit,
) {
    companion object {
        private const val TAG = "RfbClient"
        const val ENC_RAW = 0
        const val ENC_COPYRECT = -5
        const val ENC_HEXTILE = -6
        const val ENC_TIGHT = -260
        const val ENC_TIGHT_PNG = -261

        /** Largest single field the parser will ever allocate. A real RFB read is bounded by
         *  the framebuffer (1440x900x4 = 5.2 MB for a full Raw update); anything above this
         *  is a desync, and allocating it is what OOM-killed the app. */
        const val MAX_READ = 8 shl 20
    }

    /** First n bytes as hex — handshake/frame evidence for the debug export. */
    private fun hex(b: ByteArray, n: Int = b.size): String =
        b.take(minOf(n, b.size)).joinToString(" ") { "%02x".format(it) }

    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var desktopName = ""; private set
    @Volatile var connected = false; private set

    /** ARGB framebuffer, row-major. Allocated on ServerInit. */
    var pixels = IntArray(0); private set

    /** Monotonic counter bumped on every framebuffer update — UI observes it. */
    @Volatile var frameSeq = 0L; private set

    private val inbox = ConcurrentLinkedQueue<ByteArray>()
    private var carry = ByteArray(0)

    /** Set by the engine when the WebSocket really closes — the ONLY thing that ends a read. */
    @Volatile private var pipeClosed = false

    /** Per-read budget: the greeting arrives the instant the bridge opens Xvnc's socket, so a
     *  silent pipe for this long means the link is dead, not merely slow. */
    private val readBudgetNs = 20L * 1_000_000_000

    /** Why run() ended — surfaced in the debug export by the engine. */
    @Volatile var lastError: String? = null
        private set

    fun closePipe() { pipeClosed = true }

    /** Feed one binary WebSocket frame (or socket read) into the parser. */
    fun feed(bytes: ByteArray) {
        inbox.add(bytes)
    }

    /**
     * Drive the protocol on the calling thread: completes the handshake,
     * then decodes updates until the pipe closes. Returns when the
     * connection ends. Call from a dedicated IO coroutine.
     */
    fun run() {
        try {
            handshake()
            connected = true
            requestFullUpdate()
            loop()
        } catch (e: java.util.concurrent.CancellationException) {
            lastError = "cancelled"
            throw e
        } catch (e: Exception) {
            lastError = e.message
            Log.d(TAG, "run ended: ${e.message}")
        } finally {
            connected = false
        }
    }

    // ── handshake ──────────────────────────────────────────────────

    private fun handshake() {
        val ver = read(12)
        if (String(ver, 0, 8) != "RFB 003.") throw IllegalStateException("bad greeting: ${ver.size}")
        send("RFB 003.008\n".toByteArray())
        val offered = read(2) // TigerVNC sends offered type + one extra byte
        send(byteArrayOf(0x01)) // security type: None
        val sec = read(4)
        val secResult = ((sec[0].toInt() and 0xFF) shl 24) or ((sec[1].toInt() and 0xFF) shl 16) or
            ((sec[2].toInt() and 0xFF) shl 8) or (sec[3].toInt() and 0xFF)
        if (secResult != 0) throw IllegalStateException("security rejected: $secResult")
        send(byteArrayOf(0x01)) // ClientInit: shared=1
        // ServerInit = width(2) + height(2) + pixel-format(16) + name-length(4) = 24 bytes,
        // then the name. The pixel format is 16 bytes (bpp,depth,be,tc + 3×u16 max +
        // 3×u8 shift + 3 pad) — reading 32 here swallows the name-length plus the first
        // four name bytes, and the "name length" then lands on ASCII ("es:d") and asks
        // for 1.7 GB, which is what OOM-killed 0.1.878.
        val init = read(24)
        width = u16(init, 0)
        height = u16(init, 2)
        val nameLen = u32(init, 20)
        // ServerInit is the anchor of the whole stream. If geometry/nameLen are not sane,
        // we are not looking at a ServerInit — log the bytes verbatim so the export names
        // the desync instead of OOM-crashing on a garbage allocation.
        if (width !in 1..8192 || height !in 1..8192 || nameLen !in 0..4096 ||
            width.toLong() * height > 32_000_000
        ) {
            throw IllegalStateException(
                "ServerInit desync: ${width}x$height nameLen=$nameLen init=[${hex(init, 24)}]"
            )
        }
        desktopName = if (nameLen > 0) String(read(nameLen), Charsets.ISO_8859_1) else ""
        pixels = IntArray(width * height)
        Log.d(TAG, "ServerInit ${width}x$height '$desktopName'")

        // SetPixelFormat: 32bpp, depth 24, little-endian, truecolor, rgb888 @ 16/8/0
        val pf = ByteArray(20)
        pf[0] = 0
        pf[4] = 32; pf[5] = 24; pf[6] = 0; pf[7] = 1
        pf[8] = 0xFF.toByte(); pf[9] = 0; pf[10] = 0xFF.toByte(); pf[11] = 0
        pf[12] = 0xFF.toByte(); pf[13] = 0
        pf[14] = 16; pf[15] = 8; pf[16] = 0
        send(pf)

        // SetEncodings: Tight, TightPng, CopyRect, Raw
        val encs = intArrayOf(ENC_TIGHT, ENC_TIGHT_PNG, ENC_COPYRECT, ENC_RAW)
        val se = ByteArray(6 + 4 * encs.size)
        se[0] = 2; se[1] = 0
        writeI32(se, 2, encs.size)
        encs.forEachIndexed { i, e -> writeI32(se, 6 + 4 * i, e) }
        send(se)
    }

    // ── update loop ────────────────────────────────────────────────

    private fun loop() {
        while (true) {
            val msgType = read(1)[0].toInt() and 0xFF
            when (msgType) {
                0 -> framebufferUpdate()
                1 -> read(1) // Bell — ignore
                2 -> { val n = u32(read(4), 0); read(n) } // ServerFakeDesktopName
                255 -> { val n = u32(read(4), 0); val m = String(read(n)); Log.w(TAG, "server error: $m") }
                else -> { Log.w(TAG, "unknown server msg $msgType"); return }
            }
        }
    }

    private fun framebufferUpdate() {
        read(1) // pad
        val nRects = u16(read(2), 0)
        for (i in 0 until nRects) {
            val hdr = read(8)
            val x = u16(hdr, 0); val y = u16(hdr, 2)
            val w = u16(hdr, 4); val h = u16(hdr, 6)
            val enc = i32(read(4), 0)
            when (enc) {
                ENC_RAW -> decodeRaw(x, y, w, h)
                ENC_COPYRECT -> decodeCopyRect(x, y, w, h)
                ENC_HEXTILE -> decodeHextile(x, y, w, h)
                ENC_TIGHT, ENC_TIGHT_PNG -> decodeTight(x, y, w, h, pngAllowed = true)
                else -> Log.w(TAG, "unhandled encoding $enc")
            }
        }
        if (nRects > 0) frameSeq++
    }

    /** Ask for a full-frame update (call after input that needs a repaint). */
    fun requestFullUpdate() {
        val m = ByteArray(10)
        m[0] = 3; m[1] = 1
        writeU16(m, 2, 0); writeU16(m, 4, 0)
        writeU16(m, 6, width); writeU16(m, 8, height)
        send(m)
    }

    /** Incremental update request for a region (UI-driven, e.g. visible viewport). */
    fun requestUpdate(x: Int, y: Int, w: Int, h: Int, incremental: Boolean = true) {
        val m = ByteArray(10)
        m[0] = 3; m[1] = if (incremental) 1 else 0
        writeU16(m, 2, x); writeU16(m, 4, y)
        writeU16(m, 6, w); writeU16(m, 8, h)
        send(m)
    }

    // ── decoders ───────────────────────────────────────────────────

    private fun decodeRaw(x: Int, y: Int, w: Int, h: Int) {
        val data = read(w * h * 4)
        var p = 0
        for (row in 0 until h) {
            val off = (y + row) * width + x
            for (col in 0 until w) {
                val b = data[p].toInt() and 0xFF
                val g = data[p + 1].toInt() and 0xFF
                val r = data[p + 2].toInt() and 0xFF
                pixels[off + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                p += 4
            }
        }
    }

    private fun decodeCopyRect(x: Int, y: Int, w: Int, h: Int) {
        val src = read(4)
        val sx = u16(src, 0); val sy = u16(src, 2)
        // Overlapping copy: direction matters.
        val rows = if (sy > y) h - 1 downTo 0 else 0 until h
        for (row in rows) {
            val srow = (sy + row) * width + sx
            val drow = (y + row) * width + x
            pixels.copyInto(pixels, drow, srow, srow + w)
        }
    }

    private fun decodeHextile(x: Int, y: Int, w: Int, h: Int) {
        var bg = 0xFF000000.toInt()
        var fg = 0xFFFFFFFF.toInt()
        for (ty in 0 until h step 16) {
            for (tx in 0 until w step 16) {
                val tileW = minOf(16, w - tx)
                val tileH = minOf(16, h - ty)
                val sub = read(1)[0].toInt() and 0xFF
                if (sub and 0x01 != 0) bg = argbFromRaw(read(3))
                if (sub and 0x02 != 0) fg = argbFromRaw(read(3))
                val base = (y + ty) * width + (x + tx)
                if (sub and 0x08 != 0) { // any subrects
                    fillRect(base, tileW, tileH, bg)
                    val nSub = read(1)[0].toInt() and 0xFF
                    var pos = 0
                    var color = fg
                    var colorPending = false
                    for (s in 0 until nSub) {
                        val cm = read(1)[0].toInt() and 0xFF
                        if (cm and 0x01 != 0) { color = argbFromRaw(read(3)); colorPending = true }
                        val xy = read(1)[0].toInt() and 0xFF
                        val wh = read(1)[0].toInt() and 0xFF
                        val sx = (xy shr 4) and 0x0F
                        val sy2 = xy and 0x0F
                        val sw = ((wh shr 4) and 0x0F) + 1
                        val sh = (wh and 0x0F) + 1
                        val c = if (cm and 0x01 != 0) color else if (cm and 0x02 != 0) fg
                            else if (colorPending) color else fg
                        for (rr in 0 until sh) {
                            val off = base + (sy2 + rr) * width + sx
                            fillRect(off, sw, 1, c)
                        }
                        pos++
                    }
                } else {
                    fillRect(base, tileW, tileH, bg)
                }
            }
        }
    }

    private fun fillRect(off: Int, w: Int, h: Int, color: Int) {
        for (rr in 0 until h) pixels.fill(color, off + rr * width, off + rr * width + w)
    }

    private fun argbFromRaw(b: ByteArray): Int =
        (0xFF shl 24) or ((b[2].toInt() and 0xFF) shl 16) or ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF)

    // Tight history: the last rect's raw pixel data, for copy-from-previous.
    private var tightHistory = ByteArray(0)

    private fun decodeTight(x: Int, y: Int, w: Int, h: Int, pngAllowed: Boolean) {
        val ctrl = read(1)[0].toInt() and 0xFF
        val compType = ctrl and 0x07
        when (compType) {
            0 -> { // fill: 3-byte pixel replicated
                val px = argbFromRaw(read(3))
                fillRect(y * width + x, w, h, px)
                tightHistory = ByteArray(0)
            }
            3 -> { // copy from history
                val src = read(4)
                val sx = u16(src, 0); val sy = u16(src, 2)
                for (row in 0 until h) {
                    val srow = (sy + row) * width + sx
                    val drow = (y + row) * width + x
                    pixels.copyInto(pixels, drow, srow, srow + w)
                }
            }
            1, 2, 4, 5, 6, 7 -> { // jpeg / tpic(png) / tpicq
                val explicit = ctrl and 0x80 != 0
                val type = if (explicit) (ctrl shr 3) and 0x07 else compType
                val len: Int = if (type == 1 || type == 2) {
                    if (ctrl and 0x40 != 0) { // 3-byte length
                        val l = read(3)
                        u24(l, 0)
                    } else {
                        val n = ((ctrl shr 6) and 0x03) + 1
                        val l = read(n)
                        var v = 0; for (b in l) v = (v shl 8) or (b.toInt() and 0xFF); v
                    }
                } else {
                    // tpicq: quantized quality in bits 3-5, length follows
                    val q = (ctrl shr 3) and 0x07
                    if (ctrl and 0x40 != 0) {
                        val l = read(3); u24(l, 0)
                    } else {
                        val n = ((ctrl shr 6) and 0x03) + 1
                        val l = read(n)
                        var v = 0; for (b in l) v = (v shl 8) or (b.toInt() and 0xFF); v
                    }
                }
                val data = read(len)
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                    ?: throw IllegalStateException("image decode failed (type $type, $len bytes)")
                blitBitmap(bmp, x, y)
                if (bmp.width * bmp.height * 4 <= 1_000_000) {
                    tightHistory = bmpToRgba(bmp)
                }
            }
            else -> throw IllegalStateException("tight compType $compType")
        }
    }

    private fun blitBitmap(bmp: Bitmap, x: Int, y: Int) {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (row in 0 until bmp.height) {
            val off = (y + row) * width + x
            px.copyInto(pixels, off, row * bmp.width, row * bmp.width + bmp.width)
        }
    }

    private fun bmpToRgba(bmp: Bitmap): ByteArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val out = ByteArray(px.size * 4)
        px.forEachIndexed { i, c ->
            out[i * 4] = (c and 0xFF).toByte()
            out[i * 4 + 1] = ((c shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((c shr 16) and 0xFF).toByte()
        }
        return out
    }

    // ── input ──────────────────────────────────────────────────────

    /** Pointer event: normalized (0..1) coords → server pixels. */
    fun sendPointer(nx: Float, ny: Float, buttons: Int) {
        val x = (nx * width).toInt().coerceIn(0, width - 1)
        val y = (ny * height).toInt().coerceIn(0, height - 1)
        val m = ByteArray(6)
        m[0] = 5; m[1] = buttons.toByte()
        writeU16(m, 2, x); writeU16(m, 4, y)
        send(m)
    }

    /** Key event: X11 keysym, down=true. */
    fun sendKey(keysym: Int, down: Boolean) {
        val m = ByteArray(8)
        m[0] = 4; m[1] = if (down) 1 else 0
        writeI32(m, 4, keysym)
        send(m)
    }

    fun sendText(text: String) {
        for (ch in text) {
            val ks = ch.code.toLong() + if (ch.code < 256) 0 else 0x01000000
            sendKey(ks.toInt(), true)
            sendKey(ks.toInt(), false)
        }
    }

    // ── transport plumbing ─────────────────────────────────────────

    private fun send(bytes: ByteArray) = sink(bytes)

    private fun read(n: Int): ByteArray {
        // A length this big is never a real RFB field: it means the stream is desynced
        // (we are reading frame data, or text, as a length). Allocating it OOM-crashes
        // the whole app, so refuse it here and let the log name the desync.
        if (n < 0 || n > MAX_READ) {
            throw IllegalStateException(
                "rfb stream desync: bogus length $n (max $MAX_READ); carry=${carry.size} " +
                    "head=${hex(carry, minOf(carry.size, 24))}"
            )
        }
        val out = ByteArray(n)
        var pos = 0
        val start = System.nanoTime()
        while (pos < n) {
            if (carry.isEmpty()) {
                val next = inbox.poll()
                if (next == null) {
                    // An empty queue is NOT a closed pipe: WS frames arrive on OkHttp's reader
                    // thread, so the greeting can lag this poll by milliseconds. The old code
                    // threw EOF here, killing the handshake before byte 1 ever landed.
                    if (pipeClosed) throw EOFException("rfb pipe closed")
                    if (System.nanoTime() - start > readBudgetNs) {
                        throw EOFException("no rfb bytes for ${readBudgetNs / 1_000_000_000}s (wanted $n)")
                    }
                    Thread.sleep(2)
                    continue
                }
                carry = next
            }
            val take = minOf(n - pos, carry.size)
            System.arraycopy(carry, 0, out, pos, take)
            pos += take
            carry = if (take == carry.size) ByteArray(0) else carry.copyOfRange(take, carry.size)
        }
        return out
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u24(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)
    private fun i32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int) = i32(b, o)
    private fun writeU16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 8) and 0xFF).toByte(); b[o + 1] = (v and 0xFF).toByte()
    }
    private fun writeI32(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 24) and 0xFF).toByte(); b[o + 1] = ((v shr 16) and 0xFF).toByte()
        b[o + 2] = ((v shr 8) and 0xFF).toByte(); b[o + 3] = (v and 0xFF).toByte()
    }
}
