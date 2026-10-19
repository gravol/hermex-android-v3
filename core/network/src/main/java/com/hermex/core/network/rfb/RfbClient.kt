package com.hermex.core.network.rfb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.hermex.core.network.DebugLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
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

        /**
         * How many header bytes follow a server-to-client message type, and what payload length those
         * header bytes describe (RFC 6143 §7.5.2, verified against the spec text and against what
         * TigerVNC actually sends).
         *
         * This used be inline arithmetic in the read loop, and two of the three numbers were wrong in
         * the direction that silently blanks the screen: Bell has NO payload (the old code consumed 7
         * bytes — the following six belong to the next message), and SetColourMapEntries carries
         * n colours x 3 channels x 2 bytes, which the old code never consumed at all. A short read
         * here does not fail; every later rectangle header lands bytes off-centre and the picture goes
         * blank while the connection stays up. It is kept as a pure function so the byte math can be
         * tested from RFC fixtures without a socket.
         */
        @JvmStatic
        internal fun headerLength(msgType: Int): Int = when (msgType) {
            1 -> 5      // pad(1) + firstColour u16 + nColours u16
            3 -> 7      // pad(3) + length u32
            255 -> 17   // TigerVNC ServerFbUpdate header
            else -> 0   // 0 = FramebufferUpdate (its own parser), 2 = Bell: no payload
        }

        /** Payload length described by [header]; 0 means "nothing follows". Throws for an unknown type. */
        @JvmStatic
        internal fun payloadLength(msgType: Int, header: ByteArray): Int = when (msgType) {
            0, 2 -> 0
            1 -> {
                require(header.size >= 5) { "SetColourMapEntries header needs 5 bytes" }
                val n = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
                n * 6   // each colour is red, green, blue — U16 each. NOT n*3.
            }
            3 -> {
                require(header.size >= 7) { "ServerCutText header needs 7 bytes" }
                ((header[3].toInt() and 0xFF) shl 24) or ((header[4].toInt() and 0xFF) shl 16) or
                    ((header[5].toInt() and 0xFF) shl 8) or (header[6].toInt() and 0xFF)
            }
            255 -> {
                require(header.size >= 17) { "ServerFbUpdate header needs 17 bytes" }
                ((header[13].toInt() and 0xFF) shl 24) or ((header[14].toInt() and 0xFF) shl 16) or
                    ((header[15].toInt() and 0xFF) shl 8) or (header[16].toInt() and 0xFF)
            }
            else -> throw IOException(
                "RFB protocol error: unknown server message type $msgType — ending the connection " +
                "rather than reading an unknown payload and desyncing into a blank screen")
        }
    }

    /** First n bytes as hex — handshake/frame evidence for the debug export. */
    private fun hex(b: ByteArray, n: Int = b.size): String =
        b.take(minOf(n, b.size)).joinToString(" ") { "%02x".format(it) }

    @Volatile var width = 0; private set
    @Volatile var height = 0; private set
    @Volatile var desktopName = ""; private set
    @Volatile var connected = false; private set

    /** ARGB framebuffer, row-major. Allocated on ServerInit. Decoder-owned — the UI must
     *  never read this array; it reads [snapshot]. */
    var pixels = IntArray(0); private set

    /** Published snapshot the UI renders. Copy-on-publish under [pubLock], so the UI never
     *  sees a framebuffer the decoder is mutating, and never a half-applied frame. */
    private var published = IntArray(0)
    private val pubLock = Any()

    /** Monotonic counter bumped on every published update — UI observes it. */
    @Volatile var frameSeq = 0L; private set

    /** Copy the decode buffer into the published snapshot. Call after a FULL update. */
    private fun publish() {
        synchronized(pubLock) {
            if (published.size != pixels.size) published = IntArray(pixels.size)
            pixels.copyInto(published)
        }
        frameSeq++
    }

    /** Stable snapshot for rendering. Never mutated by the decoder after publish. */
    fun snapshot(): IntArray = synchronized(pubLock) { published }

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

        // No SetPixelFormat. Xvnc's default is already 32bpp depth-24 little-endian
        // rgb888 @ 16/8/0 — the format we want — and TigerVNC closes the link on the
        // packet we used to send ("VNCSConnST: closing rfb.sock: invalid pixel format"),
        // which is why the bridge connected and the screen stayed black.

        // SetEncodings: Tight, TightPng, CopyRect, Raw.
        // TigerVNC's Xvnc numbering (proved against the live socket, NOT RFC 6143):
        // SetPixelFormat=0, SetEncodings=2, FramebufferUpdateRequest=3, KeyEvent=4,
        // PointerEvent=5 — type 1 is reserved and Xvnc closes the link on it.
        // The encoding count is a u16 here (4+4n bytes); the RFC's u32 makes a 22-byte
        // packet, the bridge frames 4+4n, and the 2 leftover bytes desync everything.
        val encs = intArrayOf(ENC_TIGHT, ENC_TIGHT_PNG, ENC_COPYRECT, ENC_RAW)
        val se = ByteArray(4 + 4 * encs.size)
        se[0] = 2; se[1] = 0
        writeU16(se, 2, encs.size)
        encs.forEachIndexed { i, e -> writeI32(se, 4 + 4 * i, e) }
        send(se)
    }

    // ── update loop ────────────────────────────────────────────────

    private fun loop() {
        while (true) {
            val msgType = read(1)[0].toInt() and 0xFF
            if (msgType == 0) { framebufferUpdate(); continue }
            // Server numbering (TigerVNC): 1 = SetColourMapEntries, 2 = Bell, 3 = ServerCutText,
            // 255 = ServerFbUpdate. The lengths come from headerLength/payloadLength so the byte math
            // is one table, testable from RFC fixtures, instead of inline guesses — see those two
            // functions for what was wrong before (Bell consumed 7 bytes it did not own; ColourMap's
            // n*6 payload was never consumed at all).
            val headLen = headerLength(msgType)
            val head = if (headLen > 0) read(headLen) else ByteArray(0)
            val payload = payloadLength(msgType, head)   // unknown type -> IOException, connection ends
            if (payload > 0) read(payload)
            when (msgType) {
                255 -> Log.w(TAG,
                    "ServerFbUpdate $payload bytes (ring-buffer protocol not enabled)")
                3 -> DebugLog.log("INFO", "BOT",
                    "ServerCutText ${payload} bytes ignored (clipboard not mirrored)")
                1 -> DebugLog.log("INFO", "BOT",
                    "SetColourMapEntries consumed cleanly (header=$headLen payload=$payload)")
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
            // Rectangle bounds are server-supplied. The handshake bounds the ALLOCATION,
            // not each rect: a desynced stream produces a rect that writes past the
            // framebuffer. Continuing after that prints garbage for the rest of the
            // session, so treat an out-of-bounds rect as fatal and end the connection.
            if (x + w > width || y + h > height) {
                throw IOException(
                    "rect out of bounds: $x,$y ${w}x$h in ${width}x$height (enc=$enc)")
            }
            if (enc == ENC_TIGHT || enc == ENC_TIGHT_PNG) {
                // Tight's zero-size rect is a control opcode, not a picture: x = stream
                // id, and it carries EXACTLY ONE byte — the flush code (0x21 flush the
                // zlib stream, 0x22 flush + drop its palette, anything else a no-op).
                // Reading zero bytes leaves that byte in the stream and every following
                // rect header lands one byte off, which is the blank-screen desync.
                if (w == 0 && h == 0) {
                    val code = read(1)[0].toInt() and 0xFF
                    val s = x and 3
                    if (code == 0x21) tightInflater[s].reset()
                    if (code == 0x22) { tightInflater[s].reset(); tightPaletteReady[s] = false }
                    continue
                }
            }
            when (enc) {
                ENC_RAW -> decodeRaw(x, y, w, h)
                ENC_COPYRECT -> decodeCopyRect(x, y, w, h)
                ENC_HEXTILE -> decodeHextile(x, y, w, h)
                ENC_TIGHT, ENC_TIGHT_PNG -> decodeTight(x, y, w, h, pngAllowed = true)
                else -> Log.w(TAG, "unhandled encoding $enc")
            }
        }
        // Publish AFTER the whole update, not per rect: a frame can be many rects, and the
        // UI must never see a half-applied frame. The snapshot is what the UI reads.
        if (nRects > 0) publish()
    }

    /** Ask for a full-frame update (call after input that needs a repaint). */
    fun requestFullUpdate() {
        // FramebufferUpdateRequest (TigerVNC type 3): type, pad, x, y, w, h = 10B.
        // There is no incremental flag on this server; a longer packet leaves a stray
        // byte that Xvnc reads as the next message type and closes on.
        val m = ByteArray(10)
        m[0] = 3; m[1] = 0
        writeU16(m, 2, 0); writeU16(m, 4, 0)
        writeU16(m, 6, width); writeU16(m, 8, height)
        send(m)
    }

    /** Incremental update request for a region (UI-driven, e.g. visible viewport). */
    fun requestUpdate(x: Int, y: Int, w: Int, h: Int, incremental: Boolean = true) {
        val m = ByteArray(10)
        m[0] = 3; m[1] = 0
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
                val base = (y + ty) * width + (x + tx)
                // RFC 6143 subencoding bits: 0x01 Raw, 0x02 BackgroundSpecified,
                // 0x04 ForegroundSpecified, 0x08 AnySubrects, 0x10 SubrectsColoured.
                // Reading Background on bit 0x01 makes a Raw tile consume 3 bytes where the
                // server sends tileW*tileH*3 — that desyncs the whole stream.
                if (sub and 0x02 != 0) bg = argbFromRgb(read(3))
                if (sub and 0x04 != 0) fg = argbFromRgb(read(3))
                if (sub and 0x01 != 0) { // Raw: full pixel data for this tile, in the
                    // server's pixel format (32bpp BGRA here), NOT 3-byte RGB triples.
                    val bpp = 4
                    for (r in 0 until tileH) {
                        val px = read(tileW * bpp)
                        for (c in 0 until tileW) {
                            pixels[base + r * width + c] = argbFromRaw(px, c * bpp)
                        }
                    }
                    continue
                }
                fillRect(base, tileW, tileH, bg)
                if (sub and 0x08 != 0) { // any subrects
                    val nSub = read(1)[0].toInt() and 0xFF
                    var color = fg
                    for (s in 0 until nSub) {
                        val cm = read(1)[0].toInt() and 0xFF
                        if (cm and 0x01 != 0) { color = argbFromRgb(read(3)) }
                        val xy = read(1)[0].toInt() and 0xFF
                        val wh = read(1)[0].toInt() and 0xFF
                        val sx = (xy shr 4) and 0x0F
                        val sy2 = xy and 0x0F
                        val sw = ((wh shr 4) and 0x0F) + 1
                        val sh = (wh and 0x0F) + 1
                        val c = if (cm and 0x01 != 0) color else fg
                        for (rr in 0 until sh) {
                            fillRect(base + (sy2 + rr) * width + sx, sw, 1, c)
                        }
                    }
                }
            }
        }
    }

    private fun fillRect(off: Int, w: Int, h: Int, color: Int) {
        for (rr in 0 until h) pixels.fill(color, off + rr * width, off + rr * width + w)
    }

    private fun argbFromRaw(b: ByteArray, o: Int = 0): Int =
        (0xFF shl 24) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)

    /** 3-byte **R,G,B** triple (Tight fill/palette and Hextile colours are RGB order,
     *  unlike the 32-bit framebuffer which is B,G,R,X) → ARGB. */
    private fun argbFromRgb(b: ByteArray, o: Int = 0): Int =
        (0xFF shl 24) or ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            (b[o + 2].toInt() and 0xFF)

    // ── Tight (TightVNC) ───────────────────────────────────────────
    //
    // Control byte: bits 7-6 = compression type (0 Basic, 1 JPEG, 2 PNG,
    // 3 CopyFromHistory). For Basic: bit 5 = useExplicitTopLevelColors,
    // bits 4-0 = numTopLevelColors (0..7, and 7 means "fill"). Index data
    // with 1..6 top-level colours is zlib-compressed on one of four streams
    // chosen by (y/16 + x/16) % 4, and each stream keeps its own palette.
    // A zero-size Tight rect (y = 0x21 / 0x22) flushes stream x.
    //
    // The old decoder read the LOW three bits as the compression type, so a
    // normal compressed desktop rect decoded as nothing — the blank screen.

    private val tightInflater = Array(4) { java.util.zip.Inflater() }
    private val tightPalette = Array(4) { IntArray(256) }
    private val tightPaletteReady = BooleanArray(4)

    /** Tight's 32 KB history: CopyFromHistory copies from the last decoded rect. */
    private var histRgba = ByteArray(0)
    private var histW = 0

    private fun decodeTight(x: Int, y: Int, w: Int, h: Int, pngAllowed: Boolean) {
        val ctrl = read(1)[0].toInt() and 0xFF
        // Tight's Fill special case: when the TOP THREE bits of the control byte are
        // zero, the rect is a solid colour — three RGB bytes follow and there is no
        // palette, no zlib, no stream. A desktop that opens with a blank background
        // sends exactly ctrl=0x00, so routing that to the basic path asks for a
        // palette that was never sent ("tight: no palette for stream 0") and the
        // screen stays black.
        if (ctrl and 0xE0 == 0) {
            val s = (y / 16 + x / 16) % 4
            fillRect(y * width + x, w, h, argbFromRgb(read(3)))
            tightPaletteReady[s] = false
            return
        }
        when ((ctrl shr 5) and 7) {
            1 -> decodeTightBasic(x, y, w, h, ctrl)
            2, 3 -> decodeTightImage(x, y, w, h)
            4 -> copyFromHistory(x, y, w, h)
            else -> throw IllegalStateException("tight: bad control byte $ctrl")
        }
    }

    /** Tight's 1/2/3-byte "compact length" (7 bits per byte, high bit = continue). */
    private fun readCompactLength(): Int {
        var len = 0
        var shift = 0
        while (true) {
            val b = read(1)[0].toInt() and 0xFF
            len = len or ((b and 0x7F) shl shift)
            if (b < 128) return len
            shift += 7
            if (shift > 21) throw IllegalStateException("tight: bad compact length")
        }
    }

    private fun decodeTightBasic(x: Int, y: Int, w: Int, h: Int, ctrl: Int) {
        // Basic compression control byte: bits 4-3 = filter id (0 Copy, 1 Palette,
        // 2 Gradient), bits 2-0 = compression level. The old decoder read bit 5 as
        // "explicit palette" — bit 5 belongs to the compression TYPE field, so every
        // basic rect made it consume palette bytes that were never sent, desyncing the
        // stream (log: "unhandled encoding -350941171", "unknown server msg 235").
        val filterId = (ctrl and 0x18) shr 3
        val level = ctrl and 0x07
        val s = (y / 16 + x / 16) % 4

        if (filterId != 0) read(1) // filter id byte follows the control byte
        val numColors = if (level == 0) 256 else (1 shl level)

        if (filterId == 1) {
            // TightFilterPalette: the filter byte carries (numColors - 1) and the
            // palette is the only way a stream palette ever arrives.
            val pal = read(numColors * 3)
            for (i in 0 until numColors) tightPalette[s][i] = argbFromRgb(pal, i * 3)
            tightPaletteReady[s] = true
        }

        if (numColors == 1) {
            fillRect(y * width + x, w, h, tightPalette[s][0])
            saveHistory(x, y, w, h)
            return
        }

        if (!tightPaletteReady[s]) {
            throw IllegalStateException("tight: no palette for stream $s (ctrl=$ctrl level=$level)")
        }

        val n = w * h
        val idx = ByteArray(n)
        if (level == 0) {
            // Level 0 is uncompressed TPI: one palette-index byte per pixel.
            read(n).copyInto(idx)
        } else {
            val compLen = readCompactLength()
            if (compLen == 0) {
                // Spec: zero-length compressed data means "fill with the last colour".
                fillRect(y * width + x, w, h, tightPalette[s][0])
                saveHistory(x, y, w, h)
                return
            }
            val comp = read(compLen)
            val inf = tightInflater[s]
            inf.reset()
            inf.setInput(comp)
            var got = 0
            while (got < n) {
                val k = inf.inflate(idx, got, n - got)
                if (k == 0) break
                got += k
            }
            if (got != n) {
                throw IllegalStateException("tight zlib: inflated $got of $n (stream $s, $compLen B)")
            }
        }

        if (filterId == 2) {
            // Gradient: first pixel of each row comes from the palette, every following
            // pixel is the previous one plus a signed delta applied to all three
            // channels, clamped to 0..255.
            for (row in 0 until h) {
                val off = (y + row) * width + x
                var pr = 0; var pg = 0; var pb = 0
                for (col in 0 until w) {
                    if (col == 0) {
                        val p = tightPalette[s][idx[row * w].toInt() and 0xFF]
                        pr = (p shr 16) and 0xFF; pg = (p shr 8) and 0xFF; pb = p and 0xFF
                    } else {
                        val d = idx[row * w + col].toInt() // signed delta
                        pr = (pr + d).coerceIn(0, 255)
                        pg = (pg + d).coerceIn(0, 255)
                        pb = (pb + d).coerceIn(0, 255)
                    }
                    pixels[off + col] = (0xFF shl 24) or (pr shl 16) or (pg shl 8) or pb
                }
            }
            saveHistory(x, y, w, h)
            return
        }

        var p = 0
        for (row in 0 until h) {
            val off = (y + row) * width + x
            for (col in 0 until w) {
                pixels[off + col] = tightPalette[s][idx[p].toInt() and 0xFF]
                p++
            }
        }
        saveHistory(x, y, w, h)
    }

    private fun decodeTightImage(x: Int, y: Int, w: Int, h: Int) {
        val len = readCompactLength()
        val data = read(len)
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw IllegalStateException("tight image decode failed ($len bytes)")
        blitBitmap(bmp, x, y)
        saveHistory(bmp)
    }

    private fun copyFromHistory(x: Int, y: Int, w: Int, h: Int) {
        val src = read(4)
        val sx = u16(src, 0); val sy = u16(src, 2)
        if (histW == 0 || histRgba.isEmpty()) return
        for (row in 0 until h) {
            for (col in 0 until w) {
                val si = ((sy + row) * histW + (sx + col)) * 4
                if (si + 3 >= histRgba.size) continue
                val di = (y + row) * width + (x + col)
                if (di >= pixels.size) continue
                pixels[di] = (0xFF shl 24) or
                    ((histRgba[si + 2].toInt() and 0xFF) shl 16) or
                    ((histRgba[si + 1].toInt() and 0xFF) shl 8) or
                    (histRgba[si].toInt() and 0xFF)
            }
        }
    }

    private fun saveHistory(x: Int, y: Int, w: Int, h: Int) {
        val n = w * h
        if (n <= 0 || n > 4_000_000) return
        val out = ByteArray(n * 4)
        var p = 0
        for (row in 0 until h) {
            val off = (y + row) * width + x
            for (col in 0 until w) {
                val c = pixels[off + col]
                out[p] = (c and 0xFF).toByte()
                out[p + 1] = ((c shr 8) and 0xFF).toByte()
                out[p + 2] = ((c shr 16) and 0xFF).toByte()
                p += 4
            }
        }
        histRgba = out
        histW = w
    }

    private fun saveHistory(bmp: Bitmap) {
        val px = bmpToRgba(bmp)
        if (px.size <= 16_000_000) {
            histRgba = px
            histW = bmp.width
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
        m[0] = 5; m[1] = buttons.toByte()  // TigerVNC PointerEvent = 5
        writeU16(m, 2, x); writeU16(m, 4, y)
        send(m)
    }

    /** Key event: X11 keysym, down=true. */
    fun sendKey(keysym: Int, down: Boolean) {
        val m = ByteArray(8)
        m[0] = 4; m[1] = if (down) 1 else 0  // TigerVNC KeyEvent = 4
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
