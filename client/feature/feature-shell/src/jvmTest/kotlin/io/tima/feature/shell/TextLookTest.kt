package io.tima.feature.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.font.FontFamily
import io.tima.core.ui.AppFont
import io.tima.core.ui.familyOf
import io.tima.core.ui.Appearance
import io.tima.core.ui.LocalTextLook
import io.tima.core.ui.TextLook
import io.tima.core.ui.TextPlace
import io.tima.core.ui.ThemeChoice
import io.tima.core.ui.TimaColors
import io.tima.core.words.Language
import io.tima.core.ui.LocalWords
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Шрифты и размеры — ПЛАН-ШРИФТОВ Ш1…Ш5.
 *
 * Проверяется то, что человек увидит и потеряет: что размер вправду меняет картинку,
 * что группы независимы, что выбранное переживает перезапуск, и — главное — **что текст
 * больше не обрезается молча**.
 */
class TextLookTest {

    @Test
    fun размер_группы_меняет_картинку_а_соседнюю_группу_не_трогает() {
        val обычный = снимок("меню-обычный", TextLook())
        val крупный = снимок("меню-крупный", TextLook().withSize(TextPlace.MENU, 22))
        assertTrue(обычный.difference(крупный) > 0.0, "ручка «меню» ничего не изменила")

        // Шапка над списком — другая группа: её размер остался прежним.
        val шапка = снимок("шапка-крупная", TextLook().withSize(TextPlace.HEADERS, 26))
        assertTrue(шапка.difference(крупный) > 0.0, "шапка и меню меняются вместе — группы не разделены")
    }

    @Test
    fun длинный_пункт_настроек_переносится_а_не_обрезается() {
        // До Ш2 «Секретная фраза и устройства» обрезалась уже при ×1.3, а по-испански
        // при обычном размере.
        //
        // Меряется, как в [RowFitTest]: один и тот же список на узкой ширине и на
        // заведомо широкой при ОДНОМ размере. Узкий выше — значит перенеслось; равны —
        // значит обрезано. Холст высокий нарочно: на телефонном список упирается в низ
        // и обе высоты становятся одинаковыми независимо от переноса.
        val look = TextLook().withSize(TextPlace.MENU, 22)
        val узкий = снимок("настройки-перенос-узкий", look, ширина = 360, высота = 2000)
        val широкий = снимок("настройки-перенос-широкий", look, ширина = 900, высота = 2000)
        assertTrue(
            высота(узкий) > высота(широкий),
            "на узкой ширине список не стал выше — значит текст обрезан, а не перенесён",
        )
    }

    @Test
    fun шрифты_из_сборки_грузятся() {
        // Снимком это не проверить: `capture` НАРОЧНО подставляет всем снимкам
        // эталонный шрифт, иначе меры поплывут от машины (см. ReferenceFont). Поэтому
        // проверяется то, что и важно: ресурс на месте и семейство собирается.
        // Отсутствующий файл уронил бы `familyOf` прямо здесь.
        var roboto: FontFamily? = null
        var openSans: FontFamily? = null
        var system: FontFamily? = null
        capture("шрифты-грузятся", 10, 10, dark = false) {
            roboto = familyOf(AppFont.Roboto)
            openSans = familyOf(AppFont.OpenSans)
            system = familyOf(AppFont.System)
        }
        assertTrue(roboto != null, "Roboto не собрался — файла нет в ресурсах")
        assertTrue(openSans != null, "Open Sans не собрался — файла нет в ресурсах")
        assertEquals(null, system, "«Системный» обязан остаться платформенным, то есть null")
    }

    @Test
    fun выбранное_переживает_перезапуск() {
        val было = Appearance(ThemeChoice.Light, TimaColors.light)
            .let { it.copy(text = it.text.copy(font = AppFont.OpenSans).withSize(TextPlace.MESSAGES, 19)) }
        val стало = Appearance.read(было.write(), systemDark = false)

        assertEquals(AppFont.OpenSans, стало.text.font)
        assertEquals(19, стало.text.sizeOf(TextPlace.MESSAGES))
        assertEquals(TextPlace.MENU.base, стало.text.sizeOf(TextPlace.MENU), "нетронутая группа должна остаться обычной")
    }

    @Test
    fun испорченная_запись_не_роняет_и_не_ломает_размеры() {
        val стало = Appearance.read("font=НЕТТАКОГО\nsize.MENU=999\nsize.TABS=15\n", systemDark = false)
        assertEquals(AppFont.byDefault, стало.text.font, "непонятный шрифт — умолчание, а не падение")
        assertEquals(TextPlace.MENU.base, стало.text.sizeOf(TextPlace.MENU), "размер вне ступеней не принимается")
        assertEquals(15, стало.text.sizeOf(TextPlace.TABS), "годное значение рядом с негодным обязано уцелеть")
    }

    private fun снимок(имя: String, look: TextLook, ширина: Int = 360, высота: Int = 700) =
        capture(имя, ширина, высота, dark = false) {
        CompositionLocalProvider(
            LocalTextLook provides look,
            LocalWords provides (Language.Russian.words ?: error("нет русского словаря")),
        ) {
            SettingsScreen(opened = null, onOpen = {}, onBack = {}) { }
        }
        }

    /** До какой строки сверху экран непустой: перенос виден ростом списка. */
    private fun высота(shot: io.tima.testui.Snapshot): Int {
        var last = 0
        for (y in 0 until shot.height) {
            if (!io.tima.testui.Snapshot.close(shot.color(24, y), TimaColors.light.surface)) last = y + 1
        }
        return last
    }
}
