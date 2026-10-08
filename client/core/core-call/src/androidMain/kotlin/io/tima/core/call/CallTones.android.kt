package io.tima.core.call

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Android: системные тоны вызова и «занято» — те же, что у обычного телефона, по стандарту
 * страны. Поток голосового звонка: звук идёт туда же, куда разговор, — в ухо или динамик.
 */
actual object CallTones {
    private val lock = Any()
    private var tone: ToneGenerator? = null
    private var ringing = false

    actual fun ringback(on: Boolean) = synchronized(lock) {
        if (on) {
            if (ringing) return@synchronized
            release()
            tone = make()?.also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
            ringing = tone != null
        } else if (ringing) {
            release()
        }
    }

    actual fun busy() = synchronized(lock) {
        release()
        val made = make() ?: return@synchronized
        made.startTone(ToneGenerator.TONE_SUP_BUSY, BUSY_TONE_MS)
        tone = made
        Handler(Looper.getMainLooper()).postDelayed(
            { synchronized(lock) { if (tone === made) release() } },
            BUSY_TONE_MS + 300L,
        )
    }

    private fun make(): ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME) }.getOrNull()

    private fun release() {
        tone?.let { runCatching { it.stopTone(); it.release() } }
        tone = null
        ringing = false
    }

    /** Громкость тона в процентах от громкости потока. */
    private const val VOLUME = 80
}
