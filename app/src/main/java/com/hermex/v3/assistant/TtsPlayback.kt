package com.hermex.v3.assistant

import android.media.MediaPlayer
import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * Inline TTS playback (v0.5.4). The dashboard /api/audio/speak route returns the
 * synthesized reply as a base64 data URL; we spill it to cache and play it with
 * MediaPlayer (mp3 from the configured chain — no extra media dependency).
 */
object TtsPlayback {

    private const val TAG = "Hermex"

    @Volatile
    private var player: MediaPlayer? = null

    fun play(context: Context, dataUrl: String) {
        val file = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        try {
            val comma = dataUrl.indexOf(',')
            val b64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
            file.writeBytes(Base64.decode(b64, Base64.DEFAULT))

            // One clip at a time: a newer reply interrupts the older clip.
            player?.let { old ->
                runCatching { old.release() }
            }

            val mp = MediaPlayer()
            player = mp
            mp.setOnCompletionListener { finish(mp, file) }
            mp.setOnErrorListener { _, _, _ -> finish(mp, file); true }
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "TTS playback failed", e)
            runCatching { file.delete() }
        }
    }

    private fun finish(mp: MediaPlayer, file: File) {
        runCatching { mp.release() }
        if (player === mp) player = null
        runCatching { file.delete() }
    }
}
