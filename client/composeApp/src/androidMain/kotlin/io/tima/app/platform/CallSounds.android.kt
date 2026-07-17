package io.tima.app.platform

import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.tima.app.diag.AppDiagnostics

private var ringtone: Ringtone? = null
private var tone: ToneGenerator? = null
private var vibrator: Vibrator? = null

@Synchronized
actual fun startRinging(incoming: Boolean) {
    stopRinging()
    val ctx = AndroidAppContext.app
    runCatching {
        if (incoming) {
            // Системный рингтон пользователя: звонок должен звучать как звонок
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(ctx, uri)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
            vibrator = (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (ctx.getSystemService(VibratorManager::class.java))?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    ctx.getSystemService(Vibrator::class.java)
                }
                )?.apply {
                vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 900), 0))
            }
        } else {
            // Гудок дозвона. STREAM_MUSIC, а не VOICE_CALL: до установки разговора
            // голосовой поток может быть не слышен.
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70).apply {
                startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }
    }.onFailure { AppDiagnostics.add("звонок: звук вызова не запустился — ${it.message}") }
}

@Synchronized
actual fun stopRinging() {
    runCatching { ringtone?.stop() }
    ringtone = null
    runCatching { tone?.stopTone(); tone?.release() }
    tone = null
    runCatching { vibrator?.cancel() }
    vibrator = null
}
