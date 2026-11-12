package io.tima.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import io.tima.core.ui.ColorSlot
import io.tima.core.ui.Stage
import io.tima.core.ui.TimaColors
import io.tima.core.ui.slot
import io.tima.domain.chat.ActiveNotice
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.NoticeCounts
import io.tima.domain.chat.NoticeTab
import io.tima.domain.chat.NoticeWhat
import io.tima.feature.chat.BookState
import io.tima.feature.chat.ChatScreen
import io.tima.feature.chat.ChatState
import io.tima.feature.chat.ChatsState
import io.tima.feature.shell.LocalWindowNav
import io.tima.feature.shell.Window
import io.tima.feature.shell.WindowNav
import io.tima.feature.shell.WindowSwitchingScreen
import io.tima.feature.shell.WindowTab
import io.tima.testui.capture
import java.io.File
import kotlin.test.Test

/**
 * Пробы цветовых палитр (заказчик 2026-11-12: «Сделай макеты разных стилей… по три картинки на стиль: окно Телефон,
 * окно Переключение окон и окно чата… 8 стилей из них [четырёх зелёных], по два… белый и светло-зелёный… для каждого
 * должны быть выбраны цвета для Настройки — Цвета — все»).
 *
 * Не проверка, а генератор: снимает настоящие экраны приложения в каждой палитре — окно «Телефон» с вкладками,
 * числами непрочитанного и панелью перехода, переключение окон поверх него и переписку. Запускается только с
 * `TIMA_PALETTES=1`, иначе — пропуск. Снимки — `build/снимки/палитра-<n>-<экран>.png`, таблица цветов всех пунктов
 * «Цветов» — `build/снимки/палитры.txt`.
 */
class PaletteProbesTest {

    /** Палитра пробы: имя и значение каждого пункта «Настройки → Цвета». */
    class Probe(val id: Int, val title: String, val colors: TimaColors)

    @Test
    fun пробы_палитр() {
        if (System.getenv("TIMA_PALETTES") != "1") return println("пропуск: пробы палитр — по TIMA_PALETTES=1")
        val table = StringBuilder()
        for (p in PROBES) {
            capture("палитра-${p.id}-телефон", W, H, dark = false, colors = p.colors) { phone() }
            capture("палитра-${p.id}-переключение", W, H, dark = false, colors = p.colors) {
                Box {
                    phone()
                    WindowSwitchingScreen(
                        current = Window.Phone,
                        name = "Пётр Смирнов",
                        alias = "@petr_smirnov",
                        phone = "+7 916 000-11-22",
                        onSelect = {},
                        onClose = {},
                        onSettings = {},
                        counters = NAV_COUNTERS,
                    )
                }
            }
            capture("палитра-${p.id}-чат", W, H, dark = false, colors = p.colors) { chat() }
            table.append("${p.id}. ${p.title}\n")
            for (slot in ColorSlot.entries) table.append("  ${slot.name.padEnd(12)} ${hex(p.colors.slot(slot))}\n")
        }
        File("build/снимки").mkdirs()
        File("build/снимки/палитры.txt").writeText(table.toString())
        // Обзор: восемь палитр в ряд по экрану, вдвое мельче — сравнивать глазом.
        for (screen in listOf("телефон", "переключение", "чат")) {
            val sw = W / 2
            val sh = H / 2
            val gap = 8
            val sheet = java.awt.image.BufferedImage(PROBES.size * (sw + gap) - gap, sh + 28, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = sheet.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, sheet.width, sheet.height)
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 13)
            PROBES.forEachIndexed { i, p ->
                val shot = javax.imageio.ImageIO.read(File("build/снимки/палитра-${p.id}-$screen.png"))
                g.drawImage(shot, i * (sw + gap), 28, sw, sh, null)
                g.color = java.awt.Color.BLACK
                g.drawString("${p.id}. ${p.title}", i * (sw + gap) + 2, 18)
            }
            g.dispose()
            javax.imageio.ImageIO.write(sheet, "png", File("build/снимки/палитры-обзор-$screen.png"))
        }
    }

    @Composable
    private fun phone() = Stage(
        column = {
            CompositionLocalProvider(LocalWindowNav provides WindowNav(Window.Phone, NAV_WINDOWS, NAV_COUNTERS, {})) {
                PhoneWindow(
                    tab = WindowTab.Chats,
                    onTab = {},
                    list = CHATS,
                    book = BookState(),
                    onSearchInBook = {},
                    onOpen = {},
                    onOpenPerson = {},
                    onNew = {},
                    onSettings = {},
                    onSwitchWindows = {},
                    onNeighbourWindow = {},
                    onView = {},
                    onToggleSection = {},
                    onAddContact = {},
                    onInvite = {},
                    noticeCounts = NOTICES,
                    onOpenedContacts = {},
                    onAllowContacts = null,
                )
            }
        },
    )

    @Composable
    private fun chat() = ChatScreen(
        state = ChatState(lines = LINES),
        peer = "Аня Борисова",
        caption = "в сети",
        onSet = {},
        onSend = {},
        onBack = {},
    )

    companion object {
        const val W = 380
        const val H = 800

        private fun hex(c: Color): String {
            val v = c.value.toLong() ushr 32
            return "#" + v.toString(16).padStart(8, '0').uppercase()
        }

        private fun rgb(v: Long) = Color(0xFF000000L or v)
        private fun alpha(c: Color, a: Int) = c.copy(alpha = a / 255f)

        /**
         * Палитра из пяти решений: акцент (навигация, кнопки), поверхность, функциональная (шапка, вкладки), текст,
         * своё сообщение; остальные пункты «Цветов» выводятся из них так же, как в светлой теме (текст 70 % и 62 %,
         * рамки 28 % и 14 %, тихая подложка 6 %), янтарь и красный — прежние по смыслу.
         */
        private fun palette(nav: Long, surface: Long, functional: Long, text: Long, my: Color, confirmed: Long): TimaColors {
            val t = rgb(text)
            return TimaColors.light.copy(
                navigation = rgb(nav),
                activity = Color(0xFFFEBB02),
                confirmed = rgb(confirmed),
                surface = rgb(surface),
                functional = rgb(functional),
                text = t,
                text2 = alpha(t, 0xB3),
                text3 = alpha(t, 0x9E),
                my = my,
                author = Color(0xFFFFFFFF),
                border = alpha(t, 0x47),
                line = alpha(t, 0x24),
                onAccent = Color(0xFFFFFFFF),
                onAmber = Color(0xFF000000),
                inPlate = Color(0xFFFFFFFF),
                softAccent = alpha(rgb(nav), 0x33),
                quiet = alpha(t, 0x0F),
                alarm = Color(0xFFE0544E),
                link = Color(0xFF1E6FD9),
            )
        }

        val PROBES = listOf(
            // Зелёный 1 — олива.
            Probe(1, "Олива · белый", palette(0x675F3B, 0xFFFFFF, 0xF3F2EA, 0x181200, alpha(rgb(0xCAC7A6), 0x99), 0x1A6254)),
            Probe(2, "Олива · светло-зелёный", palette(0x39331B, 0xF6F5EE, 0xCDCAA9, 0x181200, alpha(rgb(0xCBC8A5), 0xCC), 0x1A6254)),
            // Зелёный 2 — хвоя.
            Probe(3, "Хвоя · белый", palette(0x245043, 0xFFFFFF, 0xF1F2EA, 0x112019, alpha(rgb(0xC6C5A7), 0x99), 0x1E4C3F)),
            Probe(4, "Хвоя · светло-зелёный", palette(0x172F22, 0xF5F4EC, 0xCAC7A6, 0x112019, alpha(rgb(0xC6C5A7), 0xCC), 0x245043)),
            // Зелёный 3 — мох.
            Probe(5, "Мох · белый", palette(0x5A5537, 0xFFFFFF, 0xF2F1E9, 0x2C2610, alpha(rgb(0xCDCAA9), 0x99), 0x1A6254)),
            Probe(6, "Мох · светло-зелёный", palette(0x2C2610, 0xF5F4ED, 0xC6C5A7, 0x181200, alpha(rgb(0x847C57), 0x55), 0x1A6254)),
            // Зелёный 4 — изумруд.
            Probe(7, "Изумруд · белый", palette(0x1A6254, 0xFFFFFF, 0xF1F2EA, 0x001F1B, alpha(rgb(0xCAC7A6), 0x99), 0x054C3E)),
            Probe(8, "Изумруд · светло-зелёный", palette(0x033735, 0xF5F4EC, 0xCBC8A5, 0x001F1B, alpha(rgb(0xCBC8A5), 0xCC), 0x1A6254)),
        )

        val NAV_WINDOWS = listOf(Window.Phone, Window.Social, Window.Activity, Window.Page)
        val NAV_COUNTERS = mapOf(Window.Phone to 3, Window.Social to 12)

        private fun chatLine(id: String, title: String, preview: String, minutes: Int, outgoing: Boolean = false) = ChatSummary(
            chatId = id,
            title = title,
            kind = ChatKind.Personal,
            peerId = "u-$id",
            preview = preview,
            lastOutgoing = outgoing,
            lastDisplay = if (outgoing) MessageDisplay.SENT else MessageDisplay.RECEIVED,
            atMs = 1_731_400_000_000 - minutes * 60_000L,
            unread = 0,
        )

        val CHATS = ChatsState(
            chats = listOf(
                chatLine("1", "Аня Борисова", "Созвонимся вечером?", 2),
                chatLine("2", "Мама", "Купи хлеба, пожалуйста", 15),
                chatLine("3", "Поход в горы", "Палатку беру я", 40, outgoing = true),
                chatLine("4", "Сергей Иванов", "Отправил документы", 95),
                chatLine("5", "Лена Г.", "Спасибо!", 180, outgoing = true),
                chatLine("6", "Работа", "Планёрка в 10:00", 600),
            ),
            read = true,
        )

        val NOTICES = NoticeCounts(
            listOf(
                ActiveNotice(NoticeTab.Chats, "1", NoticeWhat.Message),
                ActiveNotice(NoticeTab.Chats, "2", NoticeWhat.Message),
                ActiveNotice(NoticeTab.Calls, "u-4", NoticeWhat.Missed),
            ),
        )

        private fun line(id: String, text: String, outgoing: Boolean, minutes: Int) = ChatLine(
            dedupKey = id,
            chatId = "chat-1",
            display = if (outgoing) MessageDisplay.SENT else MessageDisplay.RECEIVED,
            text = text,
            outgoing = outgoing,
            atMs = 1_731_400_000_000 - minutes * 60_000L,
            localId = 1,
        )

        val LINES = listOf(
            line("d-6", "Созвонимся вечером?", outgoing = false, minutes = 2),
            line("d-5", "Да, после восьми удобно", outgoing = true, minutes = 3),
            line("d-4", "Отлично, я наберу по видео", outgoing = false, minutes = 5),
            line("d-3", "Фото с прогулки скинешь?", outgoing = true, minutes = 30),
            line("d-2", "Конечно, вечером пришлю", outgoing = false, minutes = 32),
            line("d-1", "Привет! Как дела?", outgoing = true, minutes = 60),
        )
    }
}
