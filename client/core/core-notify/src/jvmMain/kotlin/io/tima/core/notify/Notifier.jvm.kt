package io.tima.core.notify

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File

/**
 * ПК: всплывающая подсказка у значка в трее.
 *
 * ── ПОЧЕМУ AWT, А НЕ СРЕДСТВА COMPOSE ───────────────────────────────────────
 *
 * У Compose Desktop показ уведомления идёт через `TrayState`, а он живёт **в
 * композиции**. Нам нужно ровно обратное: уведомление ставит процесс, у которого окна
 * может не быть вовсе — в том и смысл трея, что окно закрыли, а приложение осталось.
 *
 * Значок берётся готовым: его заводит тот, кто строит трей (У4), и передаёт сюда. Свой
 * второй значок означал бы две иконки TIMA в трее.
 *
 * ── ЧЕГО ЗДЕСЬ НЕТ ──────────────────────────────────────────────────────────
 *
 * Снятия строки. Всплывающая подсказка Windows живёт своей жизнью и гаснет сама;
 * [hide] поэтому ничего не делает — и это честнее, чем делать вид. На Android строка
 * висит до снятия, и там [hide] работает.
 */
class DesktopNotifier(private val icon: TrayIcon?) : Notifier {

    override fun show(notice: Notice) {
        val tray = icon ?: return
        // Имя и повод одной строкой: у всплывающей подсказки заголовок короткий, и
        // длинное имя в нём обрезается посередине слова.
        val title = notice.who ?: APP
        val kind = when (notice.kind) {
            NoticeKind.Call -> TrayIcon.MessageType.WARNING
            NoticeKind.Message -> TrayIcon.MessageType.INFO
        }
        runCatching { tray.displayMessage(title, notice.what, kind) }
            .onFailure { Journal.trouble(LogCode.PERM_DENIED, "уведомление не показано", "почему" to it.message.orEmpty()) }
        // Звук — тем же выбором, что на телефоне (ВЗ6): мелодия по кругу до снятия
        // строки звонка, звук сообщения — один раз.
        val call = notice.call
        if (call != null) {
            DesktopRinger.ring(notice.key, call.ring)
        } else {
            DesktopRinger.once(notice.sound)
        }
    }

    /** Подсказка гаснет сама; снимать нечего — но мелодия звонка обязана замолчать. */
    override fun hide(key: String) = DesktopRinger.stop(key)

    override fun hideAll() = Unit

    private companion object {
        const val APP = "TIMA"
    }
}

/**
 * На ПК разрешения не существует: показывает тот, у кого есть трей.
 *
 * Отсутствие трея — не отказ человека, а свойство системы, и спрашивать тут не о чем.
 */
actual fun notifyAccessWay(): NotifyAccessWay = NotifyAccessWay.Given

actual fun askNotifyAccess(onResult: (Boolean) -> Unit) = onResult(SystemTray.isSupported())

/**
 * Значок в трее, через который идёт показ.
 *
 * Заводит его тот, кто строит трей (У4), и отдаёт сюда. Свой второй значок означал бы
 * две иконки TIMA рядом, и человек не понял бы, какая из них настоящая.
 */
object DesktopNotices {

    @Volatile
    private var icon: TrayIcon? = null

    fun install(icon: TrayIcon?) {
        this.icon = icon
    }

    internal fun notifier(): Notifier = DesktopNotifier(icon)
}

actual fun platformNotifier(): Notifier = DesktopNotices.notifier()

/** На ПК усыплять приложение некому: службы энергосбережения в наши дела не лезут. */
actual fun awakeAllowed(): Boolean = true

actual fun askAwake() = Unit

/**
 * Звук на ПК — ВЗ6.
 *
 * ── ЧТО ЗВУЧИТ ──────────────────────────────────────────────────────────────
 *
 * Свой файл `wav` играется как есть. mp3, ogg и m4a Java без сторонних библиотек не
 * проигрывает, а своего списка стандартных мелодий у Windows для приложений нет — для них
 * и для «как в системе» звучит системный сигнал. Молчать нельзя: звонок, который не
 * слышно, — пропущенный.
 */
internal object DesktopRinger {

    @Volatile
    private var ringingKey: String? = null

    @Volatile
    private var clip: javax.sound.sampled.Clip? = null

    @Volatile
    private var beeper: Thread? = null

    @Synchronized
    fun ring(key: String, choice: SoundChoice) {
        stopAll()
        if (choice == SoundChoice.Silent) return
        ringingKey = key
        val file = (choice as? SoundChoice.File)?.path?.let(::File)
        clip = file?.takeIf { it.extension.equals("wav", ignoreCase = true) }?.let { openClip(it) }
        val c = clip
        if (c != null) {
            c.loop(javax.sound.sampled.Clip.LOOP_CONTINUOUSLY)
            return
        }
        beeper = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    java.awt.Toolkit.getDefaultToolkit().beep()
                    Thread.sleep(BEEP_EVERY_MS)
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
    }

    @Synchronized
    fun once(choice: SoundChoice) {
        if (choice == SoundChoice.Silent || ringingKey != null) return
        val file = (choice as? SoundChoice.File)?.path?.let(::File)
        val c = file?.takeIf { it.extension.equals("wav", ignoreCase = true) }?.let { openClip(it) }
        if (c != null) c.start() else java.awt.Toolkit.getDefaultToolkit().beep()
    }

    @Synchronized
    fun stop(key: String) {
        if (key == ringingKey) stopAll()
    }

    private fun stopAll() {
        clip?.let { runCatching { it.stop() }; runCatching { it.close() } }
        clip = null
        beeper?.interrupt()
        beeper = null
        ringingKey = null
    }

    private fun openClip(file: File): javax.sound.sampled.Clip? = runCatching {
        val stream = javax.sound.sampled.AudioSystem.getAudioInputStream(file)
        javax.sound.sampled.AudioSystem.getClip().apply { open(stream) }
    }.onFailure {
        Journal.trouble(LogCode.CALL, "звук не проигрался — системный сигнал", "почему" to it.message.orEmpty())
    }.getOrNull()

    private const val BEEP_EVERY_MS = 1500L
}
