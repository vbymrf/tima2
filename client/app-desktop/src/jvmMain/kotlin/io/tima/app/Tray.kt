package io.tima.app

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.notify.DesktopNotices
import java.awt.Color
import java.awt.Font
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File

/**
 * Значок в трее — ПЛАН-УВЕДОМЛЕНИЙ.md, У4.
 *
 * ── ПОЧЕМУ AWT, А НЕ `Tray` ИЗ COMPOSE ──────────────────────────────────────
 *
 * Уведомления на ПК показываются через `TrayIcon.displayMessage`, а у Compose значок
 * спрятан внутри композиции и наружу не отдаётся. Два значка — свой для показа и
 * композиционный для меню — означали бы две иконки TIMA рядом, и человек не понял бы,
 * какая из них настоящая.
 *
 * Поэтому значок один и заводится здесь, до всякой композиции. Compose остаётся
 * рисовать окно.
 *
 * ── ЧТО ЗНАЧИТ «ЗАКРЫТЬ ОКНО» ───────────────────────────────────────────────
 *
 * Раньше — `exitApplication()`: закрыли окно, и процесса больше нет. Пока уведомлений не
 * было, это было честно. Теперь закрытие окна прячет окно, а выйти можно из меню значка:
 * иначе уведомление на ПК невозможно в принципе.
 */
object Tray {

    private var icon: TrayIcon? = null

    /**
     * Поставить значок.
     *
     * @param onOpen показать окно.
     * @param onExit выйти по-настоящему.
     * @return `false` — трея в системе нет. Тогда окно обязано закрываться выходом, иначе
     *   приложение станет невыключаемым: ни значка, ни окна.
     */
    fun install(onOpen: () -> Unit, onExit: () -> Unit): Boolean {
        if (!SystemTray.isSupported()) {
            Journal.trouble(LogCode.APP_START, "трея в системе нет — окно закрывается выходом")
            return false
        }
        val menu = PopupMenu().apply {
            add(MenuItem("Открыть TIMA").apply { addActionListener { onOpen() } })
            add(
                MenuItem(autostartLabel()).apply {
                    addActionListener {
                        val wasOn = Autostart.enabled()
                        Autostart.set(!wasOn)
                        label = autostartLabel()
                    }
                },
            )
            addSeparator()
            add(MenuItem("Выйти").apply { addActionListener { onExit() } })
        }
        val tray = TrayIcon(letter(), "TIMA", menu).apply {
            isImageAutoSize = true
            // Двойное нажатие по значку — привычный способ вернуть окно.
            addActionListener { onOpen() }
        }
        return runCatching {
            SystemTray.getSystemTray().add(tray)
            icon = tray
            // Отсюда и только отсюда показ берёт значок: свой второй он заводить не
            // должен.
            DesktopNotices.install(tray)
            true
        }.getOrElse {
            Journal.trouble(LogCode.APP_START, "значок в трее не встал", "почему" to it.message.orEmpty())
            false
        }
    }

    fun remove() {
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        DesktopNotices.install(null)
        icon = null
    }

    private fun autostartLabel() =
        if (Autostart.enabled()) "Не запускать при входе в систему" else "Запускать при входе в систему"

    /**
     * Значок рисуется, а не берётся файлом.
     *
     * Ресурс пришлось бы класть в сборку и держать в согласии с иконкой окна; буква
     * читается в трее не хуже и не расходится ни с чем.
     */
    private fun letter(): BufferedImage {
        val size = 16
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x7A, 0xC9, 0x43)
        g.fillRoundRect(0, 0, size, size, 5, 5)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        val metrics = g.fontMetrics
        val text = "T"
        g.drawString(text, (size - metrics.stringWidth(text)) / 2, (size + metrics.ascent) / 2 - 1)
        g.dispose()
        return image
    }
}

/**
 * Запуск при входе в систему — У4.
 *
 * Заказчик просил, чтобы приложение **просило** автозапуск, а не ставило его молча.
 * Спрашивает пункт меню значка: там же, где человек управляет поведением приложения
 * без окна, и там же, где он это отключит.
 *
 * ── ЗАПИСЬ В РЕЕСТР, А НЕ ЯРЛЫК В «АВТОЗАГРУЗКЕ» ────────────────────────────
 *
 * Ярлык пришлось бы создавать средствами оболочки Windows (COM), а это зависимость и
 * своя порция отказов. Значение в `HKCU\...\Run` ставится одной командой, видно человеку
 * в «Диспетчере задач → Автозагрузка» и снимается им же оттуда — то есть у человека
 * остаётся способ отменить нашу настройку мимо нас, и это правильно.
 *
 * `HKCU`, а не `HKLM`: права администратора нам не нужны и просить их не за что.
 */
object Autostart {

    private const val KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private const val NAME = "TIMA"

    fun enabled(): Boolean = runCatching {
        val process = ProcessBuilder("reg", "query", KEY, "/v", NAME)
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        process.exitValue() == 0
    }.getOrDefault(false)

    fun set(on: Boolean) {
        val command = if (on) {
            val path = executable() ?: run {
                Journal.trouble(LogCode.APP_START, "автозапуск: не нашли, что запускать")
                return
            }
            listOf("reg", "add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", path, "/f")
        } else {
            listOf("reg", "delete", KEY, "/v", NAME, "/f")
        }
        runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start().waitFor()
            Journal.note(LogCode.APP_START, "автозапуск", "включён" to on)
        }.onFailure {
            Journal.trouble(LogCode.APP_START, "автозапуск не настроен", "почему" to it.message.orEmpty())
        }
    }

    /**
     * Чем себя запускать.
     *
     * У упакованного приложения это `TIMA.exe` рядом с `runtime`; запущенное из Gradle
     * его не имеет, и тогда автозапуск просто не ставится — предлагать разработчику
     * запускать Gradle при входе в систему незачем.
     */
    private fun executable(): String? {
        val home = System.getProperty("jpackage.app-path")
        if (!home.isNullOrBlank() && File(home).exists()) return home
        return null
    }
}
