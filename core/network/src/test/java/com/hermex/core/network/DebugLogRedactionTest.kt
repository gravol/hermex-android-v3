package com.hermex.core.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method

/**
 * Refactor guard for the journal's secret redaction (not a fix): the debug journal is
 * shareable, so every entry is scrubbed at write time. P5 instrumentation doubles the
 * line rate, which made me add per-pattern pre-filters to [DebugLog.redactSecrets]; this
 * test pins the OUTPUT of the eight patterns so the fast path cannot quietly change what
 * leaks into a shared log.
 *
 * These assertions pass before the optimization too — that is the point: they pin
 * behaviour, they do not demonstrate it.
 */
class DebugLogRedactionTest {

    private val redact: Method =
        DebugLog::class.java.getDeclaredMethod("redactSecrets", String::class.java)
            .apply { isAccessible = true }

    private fun r(s: String): String = redact.invoke(DebugLog, s) as String

    @Test fun jsonPasswordIsMasked() {
        assertEquals("""{"password":"***","user":"jeff"}""",
            r("""{"password":"hunter2","user":"jeff"}"""))
    }

    @Test fun singleUseTicketIsMaskedInJsonAndUrl() {
        assertEquals("""{"ticket":"***"}""", r("""{"ticket":"abc-123-def"}"""))
        // Pins existing behaviour, including its quirk: the replacement literal is
        // "&ticket=***", so a leading "?" is normalized to "&". Pre-existing, unchanged here.
        assertEquals("/api/ws&ticket=***&x=1", r("/api/ws?ticket=abc-123-def&x=1"))
    }

    @Test fun authAndCookieHeaderLinesAreMasked() {
        assertEquals("Cookie: ***", r("Cookie: session=deadbeef; other=1"))
        assertEquals("Set-Cookie: ***", r("Set-Cookie: session=deadbeef"))
        assertEquals("Authorization: ***", r("Authorization: Bearer zzz"))
    }

    @Test fun yamlStyleConfigSecretsAndSkKeysAreMasked() {
        // The config endpoint returns YAML inside a JSON string: keys are unquoted.
        assertEquals("api_key: ***", r("api_key: sk-or-v1-abcdefghijklmnop"))
        assertEquals("model served from sk-***", r("model served from sk-abcdefghij"))
    }

    @Test fun ordinaryTraceLinesPassThroughUntouched() {
        val line = "TRACE|disp|cid=75c57175 sid=p2live01 seq=424 type=message.complete " +
            "disp=APPLIED vm=2123312756 rowId=msg-p2-replay-assistant-0001 contentLen=1290"
        assertEquals(line, r(line))
        val gate = "TRACE|gate_poll|id=1 needsReload=false running=false count=2 local=2"
        assertEquals(gate, r(gate))
    }
}
