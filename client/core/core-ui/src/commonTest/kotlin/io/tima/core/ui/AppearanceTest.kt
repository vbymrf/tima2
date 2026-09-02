package io.tima.core.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Оформление: выбор темы, своя палитра и её запись.
 *
 * **Главное здесь — что запись переживает перезапуск без потерь.** Всё остальное человек
 * заметит сразу; потерянный цвет он заметит через неделю и решит, что «настройки не
 * работают».
 */
class AppearanceTest {

    @Test
    fun запись_и_чтение_возвращают_то_же_самое() {
        val custom = TimaColors.light
            .with(ColorSlot.NAVIGATION, Color(0xFF3355FF))
            .with(ColorSlot.LINE, Color(0x40FF0000))
        val before = Appearance(ThemeChoice.Custom, custom)

        val after = Appearance.read(before.write(), systemDark = false)

        assertEquals(before.choice, after.choice)
        for (slot in ColorSlot.entries) {
            assertEquals(
                before.custom.slot(slot),
                after.custom.slot(slot),
                "цвет «${slot.title}» не пережил запись",
            )
        }
    }

    /**
     * Прозрачность переживает запись.
     *
     * Половина токенов полупрозрачна — линия, рамка, тихие подложки. Формат из шести
     * знаков потерял бы их молча, превратив линию списка в сплошную полосу поперёк
     * экрана; поэтому знаков восемь, и вот проверка на это.
     */
    @Test
    fun прозрачность_не_теряется() {
        assertEquals("24000000", Color(0x24000000).hex())
        assertEquals(Color(0x24000000), colorOf("24000000"))
        assertEquals(0.14f, colorOf("24000000")!!.alpha, absoluteTolerance = 0.01f)
    }

    @Test
    fun цвет_разбирается_в_привычных_записях() {
        val green = Color(0xFF8AC44A)
        assertEquals(green, colorOf("8AC44A"), "шесть знаков означают непрозрачный")
        assertEquals(green, colorOf("#8ac44a"), "решётка и строчные — так пишут везде")
        assertEquals(green, colorOf("  FF8AC44A  "), "пробелы по краям набирает кто угодно")
    }

    /**
     * Непонятное — `null`, а не чёрный.
     *
     * Подставленный вместо ошибки цвет выглядит как принятый ввод: человек набрал
     * «зелёный», получил чёрный и решил, что приложение так работает.
     */
    @Test
    fun непонятный_цвет_не_подменяется_чёрным() {
        for (bad in listOf("", "8AC44", "8AC44AA", "зелёный", "GGGGGG", "#12345")) {
            assertNull(colorOf(bad), "«$bad» обязан быть неразобранным, а не цветом")
        }
    }

    /**
     * Испорченная строка не обнуляет остальное.
     *
     * Оформление — не то, ради чего стоит не пускать человека в переписку. Непонятная
     * строка пропускается, соседние цвета остаются.
     */
    @Test
    fun испорченная_запись_теряет_только_испорченное() {
        val stored = """
            choice=Custom
            NAVIGATION=FF3355FF
            ACTIVITY=совсем не цвет
            это вообще не строка настроек
            =
            SURFACE=FF102030
        """.trimIndent()

        val read = Appearance.read(stored, systemDark = false)

        assertEquals(ThemeChoice.Custom, read.choice)
        assertEquals(Color(0xFF3355FF), read.custom.navigation, "целый цвет обязан прочитаться")
        assertEquals(Color(0xFF102030), read.custom.surface, "цвет после испорченного — тоже")
        assertEquals(
            TimaColors.light.activity,
            read.custom.activity,
            "на месте испорченного обязано остаться значение по умолчанию",
        )
    }

    @Test
    fun пустое_хранилище_даёт_тему_системы() {
        assertEquals(ThemeChoice.Light, Appearance.read(null, systemDark = false).choice)
        assertEquals(ThemeChoice.Dark, Appearance.read("", systemDark = true).choice)
        assertEquals(ThemeChoice.Dark, Appearance.read("   ", systemDark = true).choice)
    }

    /**
     * Неизвестная тема в записи не роняет приложение.
     *
     * Так выглядит откат на прежнюю сборку: в хранилище лежит имя, которого в этой
     * версии ещё или уже нет.
     */
    @Test
    fun незнакомая_тема_читается_как_умолчание() {
        assertEquals(ThemeChoice.Light, Appearance.read("choice=Радужная", systemDark = false).choice)
    }

    @Test
    fun выбранная_тема_решает_чем_рисовать() {
        val custom = TimaColors.light.with(ColorSlot.NAVIGATION, Color(0xFF3355FF))
        assertEquals(TimaColors.light, Appearance(ThemeChoice.Light, custom).colors)
        assertEquals(TimaColors.dark, Appearance(ThemeChoice.Dark, custom).colors)
        assertEquals(custom, Appearance(ThemeChoice.Custom, custom).colors)
    }

    /**
     * Своя палитра переживает переключение на светлую и обратно.
     *
     * «Я же настраивал» — худший вид потери: работа человека исчезает от действия,
     * которое выглядело безобидным.
     */
    @Test
    fun своя_палитра_хранится_даже_когда_выбрана_не_она() {
        val custom = TimaColors.light.with(ColorSlot.NAVIGATION, Color(0xFF3355FF))
        val stored = Appearance(ThemeChoice.Light, custom).write()

        val read = Appearance.read(stored, systemDark = false)

        assertEquals(ThemeChoice.Light, read.choice)
        assertEquals(Color(0xFF3355FF), read.custom.navigation)
    }

    /**
     * Открыты ровно семнадцать цветов, и три закрыты по причине, а не по забывчивости.
     *
     * Числа держатся здесь, чтобы новый цвет в наборе не оказался открыт молча: `when`
     * в [slot] и [with] уронит компиляцию, а этот тест назовёт, что решение не принято.
     */
    @Test
    fun открыто_семнадцать_цветов_из_двадцати() {
        assertEquals(17, ColorSlot.entries.size)

        // Что закрыто, проверяется от обратного: ни один открытый цвет не меняет ни
        // цвета кода, ни цвета эмоций.
        var custom = TimaColors.light
        for (slot in ColorSlot.entries) custom = custom.with(slot, Color(0xFF123456))
        assertEquals(TimaColors.light.inkCode, custom.inkCode, "чернила кода менять нельзя")
        assertEquals(TimaColors.light.paperCode, custom.paperCode, "бумагу кода менять нельзя")
        assertEquals(TimaColors.light.emotion, custom.emotion, "эмоции обязаны остаться серыми")

        // И при этом открытых цветов действительно семнадцать разных: одинаковых имён
        // в перечне быть не может, иначе две строки экрана правили бы одно значение.
        assertEquals(
            ColorSlot.entries.size,
            ColorSlot.entries.map { it.title }.distinct().size,
            "две строки с одним названием",
        )
        assertNotEquals(custom, TimaColors.light, "ни один цвет не применился — набор мёртв")
    }

    /** Каждый открытый цвет действительно меняется поодиночке. */
    @Test
    fun каждый_цвет_меняется_сам_по_себе() {
        val mark = Color(0xFF123456)
        for (slot in ColorSlot.entries) {
            val changed = TimaColors.light.with(slot, mark)
            assertEquals(mark, changed.slot(slot), "«${slot.title}» не применился")
            val others = ColorSlot.entries.filter { it != slot && changed.slot(it) != TimaColors.light.slot(it) }
            assertTrue(others.isEmpty(), "«${slot.title}» задел заодно: ${others.map { it.title }}")
        }
    }
}
