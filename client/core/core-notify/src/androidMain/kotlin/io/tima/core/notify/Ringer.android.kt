package io.tima.core.notify

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import java.io.File

/**
 * Мелодия звонка и звук сообщения — играет приложение само (ВЗ3, ВЗ5).
 *
 * ── ПОЧЕМУ НЕ ЗВУКОМ КАНАЛА ─────────────────────────────────────────────────
 *
 * Три причины, каждой хватило бы одной:
 *
 * 1. Мелодия звонка идёт **по кругу до исхода** — ответили, отклонили, положили, пропущен.
 *    Звук канала играет один раз.
 * 2. **Свой файл** лежит в папке приложения, а системный звук уведомления его не
 *    прочитает — нужен был бы FileProvider с раздачей прав системному процессу.
 * 3. **Звук канала после создания не меняется.** Смена выбора в настройках молча не
 *    действовала бы; пересоздавать канал на каждый выбор — терять настройки человека.
 *
 * Поэтому каналы беззвучные (`tima.calls.2`, `tima.messages.2`), а звучит это место.
 *
 * ── TIMA НЕ ГРОМЧЕ СИСТЕМЫ ──────────────────────────────────────────────────
 *
 * Беззвучный режим — ни звука, ни вибрации; режим вибрации — только вибрация; «Не
 * беспокоить» — тишина. Громкость звонка — громкостью **звонка** телефона
 * (`USAGE_NOTIFICATION_RINGTONE`), не мультимедиа.
 */
internal object AndroidRinger {

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var vibrating: Vibrator? = null

    /** Звонок: мелодия по кругу и вибрация рисунком, пока не [stop]. */
    @Synchronized
    fun ring(context: Context, choice: SoundChoice) {
        stop()
        val mode = mode(context)
        if (mode == Mode.Quiet) {
            Journal.note(LogCode.CALL, "мелодия не играет — телефон в тишине")
            return
        }
        vibrator(context)?.let { v ->
            runCatching { v.vibrate(VibrationEffect.createWaveform(RING_PATTERN, 0)) }
            vibrating = v
        }
        if (mode == Mode.Sound && choice != SoundChoice.Silent) {
            player = play(context, choice, ring = true)
        }
    }

    /** Сообщение: звук один раз и короткая вибрация. */
    @Synchronized
    fun once(context: Context, choice: SoundChoice) {
        // Звонок идёт — сообщение его не перебивает.
        if (player != null) return
        val mode = mode(context)
        if (mode == Mode.Quiet) return
        vibrator(context)?.let { runCatching { it.vibrate(VibrationEffect.createOneShot(ONCE_MS, VibrationEffect.DEFAULT_AMPLITUDE)) } }
        if (mode == Mode.Sound && choice != SoundChoice.Silent) {
            play(context, choice, ring = false)?.setOnCompletionListener { it.release() }
        }
    }

    @Synchronized
    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        vibrating?.let { runCatching { it.cancel() } }
        vibrating = null
    }

    private enum class Mode { Sound, Vibrate, Quiet }

    private fun mode(context: Context): Mode {
        val dnd = context.getSystemService(NotificationManager::class.java)
            ?.currentInterruptionFilter
            ?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
            ?: false
        if (dnd) return Mode.Quiet
        return when (context.getSystemService(AudioManager::class.java)?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> Mode.Quiet
            AudioManager.RINGER_MODE_VIBRATE -> Mode.Vibrate
            else -> Mode.Sound
        }
    }

    private fun play(context: Context, choice: SoundChoice, ring: Boolean): MediaPlayer? {
        val uri: Uri = when (choice) {
            is SoundChoice.System -> Uri.parse(choice.uri)
            is SoundChoice.File -> Uri.fromFile(File(choice.path))
            else -> RingtoneManager.getDefaultUri(if (ring) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_NOTIFICATION)
        } ?: return null
        val usage = if (ring) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_NOTIFICATION
        return runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, uri)
                isLooping = ring
                prepare()
                start()
            }
        }.onFailure {
            // Выбранного больше нет (файл удалили, мелодию стёрла прошивка) — не молчим, а
            // играем то, что в системе по умолчанию.
            Journal.trouble(LogCode.CALL, "звук не проигрался — беру системный", "почему" to it.message.orEmpty())
        }.getOrNull() ?: if (choice !is SoundChoice.Default) play(context, SoundChoice.Default, ring) else null
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }?.takeIf { it.hasVibrator() }

    /** Рисунок звонка: пауза, гудок, пауза, гудок — по кругу. */
    private val RING_PATTERN = longArrayOf(0, 800, 400, 800, 1600)
    private const val ONCE_MS = 150L
}
