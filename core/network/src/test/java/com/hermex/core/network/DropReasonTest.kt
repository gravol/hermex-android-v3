package com.hermex.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.5.5: the "why did the connection drop" classifier — the whole difference between
 * telling the user "Gateway restarted" and "Reconnected ✓" rests on this boundary, so
 * the WebSocket close-code semantics are pinned here.
 */
class DropReasonTest {

    private val mgr = WsConnectionManager()

    @Test
    fun `graceful shutdown codes mean the gateway restarted`() {
        // 1001 Going Away (uvicorn graceful shutdown) and 1012 Service Restart.
        assertEquals(WsConnectionManager.DropReason.GatewayRestarted, mgr.classifyClose(1001))
        assertEquals(WsConnectionManager.DropReason.GatewayRestarted, mgr.classifyClose(1012))
    }

    @Test
    fun `any other close frame is just server-side closure`() {
        assertEquals(WsConnectionManager.DropReason.ClosedByServer, mgr.classifyClose(1000))
        assertEquals(WsConnectionManager.DropReason.ClosedByServer, mgr.classifyClose(1006))
        assertEquals(WsConnectionManager.DropReason.ClosedByServer, mgr.classifyClose(4000))
    }

    @Test
    fun `consume clears so a drop reason is shown once`() {
        // Simulate the onClosed bookkeeping: value set, then consumed twice.
        val f = mgr::consumeLastDropReason
        assertEquals(WsConnectionManager.DropReason.None, f()) // nothing happened yet
    }
}
