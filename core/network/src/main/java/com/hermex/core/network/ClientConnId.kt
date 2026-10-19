package com.hermex.core.network

import java.security.SecureRandom

/**
 * Short client-side connection id (P5 instrumentation, A1).
 *
 * The gateway logs its own per-connection transport ids (`transport=715e85ef3350`) and
 * stamps every routed frame with them. The app journal had nothing comparable, so a
 * device trial's journal could not be joined to the gateway frame trace — the D/14
 * question "did those 424 frames reach the phone at all?" was unanswerable from our
 * side. Every socket this app opens now mints one of these at open time; it appears on
 * the `WS|conn` line and on every `TRACE|frame` line, which makes "frames routed to
 * transport X" and "frames parsed by client cid Y" the same statement.
 *
 * Format deliberately matches the gateway's id style (short lowercase hex) and is
 * process-local: it is a log join key, not an identity the server ever sees.
 */
object ClientConnId {

    private const val LEN = 8
    private const val HEX = "0123456789abcdef"
    private val random = SecureRandom()

    /** A fresh 8-char lowercase hex id. Unique for practical purposes; uniqueness is
     *  asserted in tests rather than guaranteed by a counter, because the value only
     *  ever has to distinguish sockets inside one app process. */
    fun new(): String {
        val sb = StringBuilder(LEN)
        repeat(LEN) { sb.append(HEX[random.nextInt(16)]) }
        return sb.toString()
    }
}
