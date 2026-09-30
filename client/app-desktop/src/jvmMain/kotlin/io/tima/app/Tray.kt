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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage

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
        // Автозапуск — и здесь, и в «Настройки → Разрешения» (заказчик 2026-09-27: из трея не
        // убирать). Два места с одним состоянием — поэтому подпись спрашивает реестр при
        // каждом открытии меню, а не помнит своё: включили в настройках — здесь уже видно.
        val autostart = MenuItem(autostartLabel()).apply {
            isEnabled = Autostart.available
            addActionListener {
                Autostart.set(!Autostart.enabled())
                label = autostartLabel()
            }
        }
        val menu = PopupMenu().apply {
            add(MenuItem("Открыть TIMA").apply { addActionListener { onOpen() } })
            add(autostart)
            addSeparator()
            // «Выйти» у нас — уйти, оставив фон (заказчик 2026-09-30); этот пункт закрывает совсем.
            add(MenuItem("Закрыть приложение").apply { addActionListener { onExit() } })
        }
        val tray = TrayIcon(letter(), "TIMA", menu).apply {
            isImageAutoSize = true
            // Двойное нажатие по значку — привычный способ вернуть окно.
            addActionListener { onOpen() }
            // Меню Windows поднимает на отпускание кнопки — нажатие приходит раньше.
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { autostart.label = autostartLabel() }
            })
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

    private fun autostartLabel() =
        if (Autostart.enabled()) "Не запускать при входе в систему" else "Запускать при входе в систему"

    fun remove() {
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        DesktopNotices.install(null)
        icon = null
    }

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
