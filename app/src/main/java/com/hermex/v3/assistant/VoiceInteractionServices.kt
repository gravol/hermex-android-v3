package com.hermex.v3.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.hermex.v3.AppState
import com.hermex.v3.MainActivity

/**
 * Phone-assistant integration (v0.5.4).
 *
 * Hermex can be set as the Android "assistant app": Settings > Apps >
 * Default apps > Assistant, or in-app via RoleManager.requestRole
 * (Settings > Phone Assistant > "Set as assistant"). GrapheneOS grants the
 * role to sideloaded apps; its picker has a known empty-list bug, in which
 * case `adb shell cmd role add-role-holder android.app.role.ASSISTANT
 * com.hermex.v3` works — but the in-app role request avoids adb entirely,
 * which matters because Jeff's GrapheneOS 10a never enables USB debugging.
 *
 * The session does NOT render VoiceInteraction's own overlay: GrapheneOS has
 * no system speech recognition or hotword, and our pipeline is phone-record
 * → dashboard /api/audio/transcribe. The session just pulls Hermex's chat
 * front-and-center and arms the mic (AppState.pendingVoiceStart).
 *
 * API note (Android 15+/compileSdk 35): the session service is wired through
 * the voice-interaction meta-XML (NOT getSessionServiceInfo, and
 * onCreateSession became onNewSession). Same XML requires a
 * recognitionService — the legacy subsystem is deprecated but the parser
 * still refuses without one, hence the "not supported" stub below (the exact
 * trick Dicio uses on GrapheneOS).
 */
class HermexVoiceInteractionService : VoiceInteractionService()

class HermexVoiceSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        HermexAssistantSession(this)
}

class HermexAssistantSession(private val host: VoiceInteractionSessionService) :
    VoiceInteractionSession(host) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        // Same-process guarantee: the services run inside the Hermex process,
        // so ChatScreen sees this on cold AND warm entry.
        AppState.pendingVoiceStart = true
        val intent = Intent(host, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        try {
            host.startActivity(intent)
        } catch (e: Exception) {
            Log.w("Hermex", "Assistant launch failed", e)
        }
        finish()
    }
}

/**
 * Required by the framework's meta-XML parser ("No recognitionService
 * specified" otherwise) — never actually used: recognition happens on the
 * gateway. Any launch attempt fails fast and honestly.
 */
class HermexRecognitionStub : RecognitionService() {

    override fun onStartListening(intent: Intent, callback: Callback) {
        callback.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(callback: Callback) { /* never started */ }

    override fun onCancel(callback: Callback) { /* never started */ }
}
