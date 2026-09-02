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

    /**
     * Непонятный ввод объясняет, чем он непонятен.
     *
     * Решение заказчика 2026-09-03: «вместо того, чтобы не дать сохранить неправильный,
     * с указанием, что не так». «Не сохранилось» без причины неотличимо от поломки, и
     * самый злой случай — русская «С» вместо латинской «C»: на глаз они одинаковы, и
     * без названного знака человек ищет ошибку там, где её нет.
     */
    @Test
    fun непонятный_цвет_объясняет_чем_он_непонятен() {
        assertNull(colorProblem("8AC44A"), "правильный цвет не может быть проблемой")
        assertNull(colorProblem("#ff8ac44a"), "решётка и строчные — тоже правильно")

        val strange = colorProblem("8AC44Ж")!!
        assertTrue(strange.contains("Ж"), "чужой знак обязан быть назван: «$strange»")

        val cyrillic = colorProblem("8AC44С")!!
        assertTrue(
            cyrillic.contains("С"),
            "русская «С» неотличима от латинской на глаз — её обязательно назвать: «$cyrillic»",
        )

        val short = colorProblem("8AC44")!!
        assertTrue(short.contains("5"), "длина обязана быть названа числом: «$short»")
        assertTrue(colorProblem("")!!.isNotBlank(), "у пустого поля тоже есть что сказать")
    }

    /**
     * О длине сообщают только тогда, когда знаки уже верные.
     *
     * Иначе человек чинит длину, дописывает знак, получает ту же ошибку и не понимает,
     * что дело было в другом знаке с самого начала.
     */
    @Test
    fun чужой_знак_называется_раньше_длины() {
        val both = colorProblem("Ж")!!
        assertTrue(both.contains("Ж"), "при двух бедах сразу называется знак, а не длина: «$both»")
    }

    /**
     * Палитра собрана из самих тем, а не выписана руками.
     *
     * Выписанный список разошёлся бы с темами при первой же правке и предлагал бы цвета,
     * которых в проекте уже нет.
     */
    @Test
    fun палитра_начинается_со_значений_этого_же_места() {
        for (slot in ColorSlot.entries) {
            val palette = paletteFor(slot)
            assertEquals(
                TimaColors.light.slot(slot),
                palette.first(),
                "«${slot.title}»: первым обязано стоять значение светлой темы",
            )
            assertTrue(
                palette.contains(TimaColors.dark.slot(slot)),
                "«${slot.title}»: значения тёмной темы в палитре нет",
            )
            assertEquals(palette.size, palette.distinct().size, "в палитре повторы")
            assertTrue(palette.size > ColorSlot.entries.size, "палитра беднее одной темы")
        }
    }

    // ── защита от дурака ─────────────────────────────────────────────────────

    /**
     * Готовые темы проходят собственную защиту — и это не мелочь.
     *
     * Кнопка «Вернуть светлую» кладёт светлую палитру в свою тему. Если бы светлая не
     * проходила проверку, кнопка спасения приводила бы в состояние, из которого не
     * выпускают, — то есть ровно в ловушку, от которой защита и заводилась.
     *
     * Запас у светлой тонкий: плашка даёт 2,08 при пороге 1,8. Число здесь названо
     * нарочно — правка палитры, которая его съест, покраснеет тестом.
     */
    @Test
    fun готовые_темы_проходят_собственную_защиту() {
        for ((name, theme) in listOf("светлая" to TimaColors.light, "тёмная" to TimaColors.dark)) {
            assertTrue(
                theme.merged().isEmpty(),
                "$name: готовая тема не проходит защиту — кнопка возврата вела бы в ловушку. " +
                    VitalPair.entries.joinToString { "${it.name} ${theme.contrastOf(it)}" },
            )
        }
        assertTrue(
            TimaColors.light.contrastOf(VitalPair.PLATE) > MERGE_LIMIT,
            "запас светлой плашки съеден: ${TimaColors.light.contrastOf(VitalPair.PLATE)} против $MERGE_LIMIT",
        )
    }

    /**
     * Порог слияния — **не** порог читаемости, и подменять его нельзя.
     *
     * Читаемость — 4,5. Светлая плашка её не берёт (2,08) и не должна: решение принято
     * 2026-09-02 с названной ценой. Поднять здесь порог до 4,5 значило бы объявить
     * незаконной собственную светлую тему.
     */
    @Test
    fun порог_слияния_ниже_порога_читаемости() {
        assertTrue(MERGE_LIMIT < TimaContrast.TEXT_THRESHOLD)
        assertTrue(MERGE_LIMIT < TimaContrast.ratio(TimaColors.light.onAccent, TimaColors.light.navigation))
    }

    /** Слияние ловится на каждой из пар по отдельности. */
    @Test
    fun слияние_ловится_на_каждой_паре() {
        for (pair in VitalPair.entries) {
            val wrecked = TimaColors.light.with(pair.front, TimaColors.light.slot(pair.back))
            assertEquals(
                listOf(pair),
                wrecked.merged(),
                "«${pair.where}»: одинаковые цвета не признаны слившимися",
            )
        }
    }

    /**
     * Прозрачность не ломает проверку.
     *
     * Контраст полупрозрачного цвета не считается вовсе — `TimaContrast.ratio` на такой
     * паре бросает исключение. Половина токенов полупрозрачна, и человеку никто не
     * мешает сделать полупрозрачным текст: проверка обязана это пережить, а не уронить
     * экран настроек.
     */
    @Test
    fun прозрачный_цвет_не_роняет_проверку() {
        val seeThrough = TimaColors.light.with(ColorSlot.TEXT, Color(0x20000000))
        assertEquals(listOf(VitalPair.CONTENT), seeThrough.merged())

        val stillFine = TimaColors.light.with(ColorSlot.TEXT, Color(0xE0000000))
        assertTrue(stillFine.merged().isEmpty(), "почти непрозрачный чёрный на белом не слился")
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
