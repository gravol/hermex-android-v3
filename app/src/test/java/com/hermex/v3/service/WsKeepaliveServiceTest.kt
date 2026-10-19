package com.hermex.v3.service

import android.content.Intent
import android.app.Service
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The two things v0.1.896 changed about the keepalive foreground service (audit Phase 6, #19).
 *
 * Both are about what the service claims. A START_STICKY redelivery arrives with a null intent: no
 * chat ViewModel asked for this service, so leaving it running showed a "Connected" notification for a
 * connection nobody owned and kept the process alive for a phantom. And there was no way for the user
 * to stop it — on Android 14+ the notification is swipeable anyway, but swiping does not stop the
 * service, it just hides the evidence.
 */
@RunWith(RobolectricTestRunner::class)
class WsKeepaliveServiceTest {

    private fun started() = Robolectric.buildService(WsKeepaliveService::class.java).create()

    /** Robolectric's own record of a `stopSelf()` call on this service instance. */
    private fun stoppedItself(service: WsKeepaliveService) = shadowOf(service).isStoppedBySelf()

    @Test fun systemRedeliveryWithNoIntentStopsItselfInsteadOfClaimingAConnection() {
        val c = started()
        val result = c.get().onStartCommand(null, 0, 7)
        assertEquals("a start nobody asked for must not be sticky",
            Service.START_NOT_STICKY, result)
        assertTrue(stoppedItself(c.get()))
    }

    @Test fun theStopActionStopsTheServiceAndIsNotStickiedBack() {
        val c = started()
        val intent = Intent(ApplicationProvider.getApplicationContext(), WsKeepaliveService::class.java)
            .setAction(WsKeepaliveService.ACTION_STOP)
        val result = c.get().onStartCommand(intent, 0, 9)
        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(stoppedItself(c.get()))
    }

    @Test fun aRealStartFromTheAppIsStillStickyAndKeepsRunning() {
        val c = started()
        val intent = Intent(ApplicationProvider.getApplicationContext(), WsKeepaliveService::class.java)
        val result = c.get().onStartCommand(intent, 0, 1)
        // The app starts this on a real WebSocket connect; under system pressure it SHOULD come back,
        // because the chat ViewModel will reconnect and the turn may still be running.
        assertEquals(Service.START_STICKY, result)
        assertFalse(stoppedItself(c.get()))
    }
}
