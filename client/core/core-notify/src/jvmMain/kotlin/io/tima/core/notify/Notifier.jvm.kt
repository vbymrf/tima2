package io.tima.core.notify

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import java.awt.SystemTray
import java.awt.TrayIcon

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
    }

    /** Подсказка гаснет сама; снимать нечего. */
    override fun hide(key: String) = Unit

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
