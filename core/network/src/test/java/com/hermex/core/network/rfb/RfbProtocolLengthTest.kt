package com.hermex.core.network.rfb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * Server-to-client message lengths, from RFC 6143 §7.5.2 fixtures.
 *
 * Why this file exists: a wrong payload length in the read loop does not throw. The connection stays
 * up, every following rectangle header lands a few bytes off-centre, and what the user sees is a
 * screen that stopped updating. Both of the bugs this pins were exactly that shape — Bell was read as
 * if it carried 7 bytes (it carries none), and SetColourMapEntries' n x 6 colour payload was never
 * consumed at all. The client also sends no SetPixelFormat, so Bell is a realistic trigger rather than
 * a theoretical one (`printf '\a'` on the remote host produces one).
 *
 * The length table is a pure function for precisely this reason: these are byte fixtures, no socket.
 */
class RfbProtocolLengthTest {

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), (v and 0xFF).toByte())

    @Test fun bellCarriesNoPayloadAtAll() {
        assertEquals("Bell is type 2 with zero header bytes", 0, RfbClient.headerLength(2))
        assertEquals("RFC 6143: Bell has no body — reading 7 bytes here eats the next message's start",
            0, RfbClient.payloadLength(2, ByteArray(0)))
    }

    @Test fun colourMapEntriesAreFiveHeaderBytesPlusSixPerColour() {
        // type | padding(1) | firstColour u16 | nColours u16 | n x (red,green,blue) u16
        val header = byteArrayOf(0) + u16(0) + u16(3)
        assertEquals(5, RfbClient.headerLength(1))
        assertEquals("each colour is red+green+blue as U16 — six bytes, not three",
            18, RfbClient.payloadLength(1, header))
        val none = byteArrayOf(0) + u16(0) + u16(0)
        assertEquals(0, RfbClient.payloadLength(1, none))
        val big = byteArrayOf(0) + u16(0) + u16(255)
        assertEquals(255 * 6, RfbClient.payloadLength(1, big))
    }

    @Test fun serverCutTextIsThreePaddingBytesThenAFourByteLength() {
        val text = "hello w".toByteArray()
        val header = byteArrayOf(0, 0, 0) + byteArrayOf(
            (text.size shr 24).toByte(), (text.size shr 16).toByte(),
            (text.size shr 8).toByte(), text.size.toByte())
        assertEquals(7, RfbClient.headerLength(3))
        assertEquals(text.size, RfbClient.payloadLength(3, header))
    }

    @Test fun tigerVncServerFbUpdateHeaderCarriesItsOwnSizeAtOffsetThirteen() {
        val header = ByteArray(17)
        val size = 4096
        header[13] = (size shr 24).toByte(); header[14] = (size shr 16).toByte()
        header[15] = (size shr 8).toByte(); header[16] = size.toByte()
        assertEquals(17, RfbClient.headerLength(255))
        assertEquals(size, RfbClient.payloadLength(255, header))
    }

    /** The old code logged and returned on an unknown type; a silent stop is how a blank screen hides. */
    @Test fun anUnknownMessageTypeIsAProtocolErrorNotASilentStop() {
        for (t in intArrayOf(4, 7, 42, 254)) {
            val e = assertThrows("type $t must not be silently tolerated",
                IOException::class.java) { RfbClient.payloadLength(t, ByteArray(0)) }
            assertTrue("message should name the protocol error and the type: ${e.message}",
                e.message!!.contains("RFB protocol error") && e.message!!.contains(t.toString()))
        }
    }

    /**
     * Walking a concatenated stream with nothing but the length table must land exactly on the end of
     * each message — that is the whole difference between "screen keeps updating" and a desync.
     */
    @Test fun aConcatenatedStreamWalksCleanlyMessageByMessage() {
        val bell = byteArrayOf(2)
        val colourMap = byteArrayOf(1, 0) + u16(0) + u16(3) + ByteArray(18)
        val cut = "hello".toByteArray()
        val cutText = byteArrayOf(3, 0, 0, 0, 0, 0, 0, cut.size.toByte()) + cut

        val stream = bell + colourMap + cutText + bell
        var pos = 0
        var messages = 0
        while (pos < stream.size) {
            val type = stream[pos].toInt() and 0xFF
            pos += 1
            val hl = RfbClient.headerLength(type)
            val head = stream.copyOfRange(pos, pos + hl)
            pos += hl
            val payload = RfbClient.payloadLength(type, head)
            pos += payload
            messages++
        }
        assertEquals("the walk must stop exactly at the end of the stream", stream.size, pos)
        assertEquals(4, messages)
    }

    @Test fun randomisedConcatenationsStillWalkToExactlyTheEnd() {
        repeat(200) { iteration ->
            val rnd = Random(iteration)
            val out = java.io.ByteArrayOutputStream()
            var expected = 0
            repeat(1 + rnd.nextInt(8)) {
                val msg: ByteArray = when (rnd.nextInt(3)) {
                    0 -> byteArrayOf(2)                                    // Bell: nothing follows
                    1 -> {                                                // SetColourMapEntries
                        val n = rnd.nextInt(40)
                        byteArrayOf(1, 0) + u16(0) + u16(n) + ByteArray(n * 6)
                    }
                    else -> {                                             // ServerCutText
                        val len = rnd.nextInt(60)
                        byteArrayOf(3, 0, 0, 0, 0, 0, (len shr 8).toByte(), len.toByte()) +
                            ByteArray(len)
                    }
                }
                out.write(msg)
                expected += msg.size
            }
            val stream = out.toByteArray()
            assertEquals("fixture bytes must match the declared message sizes", expected, stream.size)
            var pos = 0
            while (pos < stream.size) {
                val type = stream[pos].toInt() and 0xFF
                pos += 1
                val hl = RfbClient.headerLength(type)
                val head = stream.copyOfRange(pos, pos + hl)
                pos += hl
                pos += RfbClient.payloadLength(type, head)
            }
            assertEquals("walk $iteration desynced", stream.size, pos)
        }
    }
}
