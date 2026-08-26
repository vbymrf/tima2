package io.tima.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.tima.testui.Snapshot
import io.tima.testui.bothThemes
import io.tima.testui.capture
import io.tima.testui.theme
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Снимки компонентов в обеих темах (У.3).
 *
 * Проверяются не картинки, а **утверждения макета** — те, которые иначе ловятся глазами
 * и на чужом устройстве. Поэтому каждый тест назван утверждением и падает с текстом,
 * говорящим, что именно перестало быть правдой.
 */
class SnapshotsTest {

    // ── Пояснение переносится, а не обрезается ──────────────────────────────────

    /**
     * **Пояснение под заголовком переносится.**
     *
     * Найдено на живом телефоне: `Второстепенное` и `Третьестепенное` были жёстко
     * одностроч­ными — их писали под строку списка. Потом ими набрали объяснения на экранах
     * входа и привязки, и предупреждение «подключённое устройство сможет читать новые
     * сообщения…» обрезалось многоточием на первой строке. Текст был написан, а человеку не
     * сообщался.
     *
     * Проверка смотрит ниже первой строки: если там нет ничего темнее фона — значит текст
     * снова обрезан. Одностроч­ный вариант в том же месте пуст, и это доказывает, что
     * проверка смотрит куда надо.
     */
    @Test
    fun пояснение_переносится_а_не_обрезается() {
        val long = "Подключённое устройство сможет читать новые сообщения этого аккаунта " +
            "и писать от вашего имени. Отключить его можно в списке устройств."

        val wrap = capture("пояснение-перенос", 300, 200, dark = false) {
            Secondary(long)
        }
        val clip = capture("пояснение-обрез", 300, 200, dark = false) {
            Secondary(long, lineOne = true)
        }

        val background = TimaColors.light.surface
        val underFirstLine = 30 until 120
        val underWrap = wrap.darkMost(x = 0 until 300, y = underFirstLine)
        val underClip = clip.darkMost(x = 0 until 300, y = underFirstLine)

        assertTrue(
            !Snapshot.close(underWrap, background),
            "ниже первой строки пусто: пояснение обрезано, а не перенесено",
        )
        assertTrue(
            Snapshot.close(underClip, background),
            "одностроч­ный вариант тоже что-то нарисовал ниже — проверка смотрит не туда",
        )
    }

    // ── Счётчик: янтарь и чёрный текст в обеих темах ────────────────────────────

    /**
     * Третья нестыковка источника, зафиксированная числом на реальных пикселях: `стиль.css`
     * задаёт счётчику `var(--текст)`, то есть в тёмной теме белый по янтарю (≈1,7 : 1), а
     * README требует чёрный (12,32 : 1). Взят README, и теперь это видно в пикселях: если
     * кто-то вернёт «текст по теме», в тёмной теме тест покраснеет.
     */
    @Test
    fun счётчик_янтарный_и_текст_на_нём_чёрный_в_обеих_темах() {
        for ((name, snapshot) in bothThemes("счётчик", 40, 40) { Box(Modifier.padding(9.dp)) { Counter(7) } }) {
            val colors = theme(name)
            val backdrop = snapshot.color(12, 20)
            assertTrue(
                Snapshot.close(backdrop, colors.activity),
                "$name: подложка счётчика обязана быть янтарной, а не $backdrop",
            )
            val glyph = snapshot.darkMost(x = 14..26, y = 12..28)
            val ratio = TimaContrast.ratio(glyph, colors.activity)
            assertTrue(
                ratio >= TimaContrast.TEXT_THRESHOLD,
                "$name: текст счётчика даёт $ratio на янтаре — README требует 12,32 : 1",
            )
        }
    }

    // ── Пузырь: полоса автора и аватар ─────────────────────────────────────────

    /**
     * Полоса автора — **левая граница пузыря**, а не подложка под ним. Разница видна ровно
     * в скруглении: граница его огибает, подложка выпирает углом. Первая моя версия рисовала
     * подложку, и на глаз это выглядело почти так же.
     */
    @Test
    fun полоса_автора_огибает_скругление() {
        for ((name, snapshot) in bothThemes("пузырь-полоса", 320, 120) { avatarWithoutBubble() }) {
            val colors = theme(name)
            val middle = snapshot.color(ЛЕВЫЙ_КРАЙ + 2, TOP + 20)
            assertTrue(
                Snapshot.close(middle, colors.navigation),
                "$name: полосы автора нет там, где она обязана быть: $middle",
            )
            // Радиус 16: в самой верхней строке пузыря левее x = 16 нет ничего.
            for (x in ЛЕВЫЙ_КРАЙ..(ЛЕВЫЙ_КРАЙ + 4)) {
                val corner = snapshot.color(x, TOP)
                assertTrue(
                    !Snapshot.close(corner, colors.navigation),
                    "$name: полоса выпирает в угол на x=$x — значит нарисована подложкой, а не границей",
                )
            }
        }
    }

    /**
     * Аватар живёт **внутри** пузыря, под рамкой, и **непрозрачен**: он перекрывает и полосу
     * автора, и угол рамки. Полупрозрачный аватар там читается как брак, а «аватар рядом с
     * пузырём» — это другой макет.
     */
    @Test
    fun аватар_перекрывает_полосу_автора() {
        val withAvatar = bothThemes("пузырь-аватар", 320, 140) { bubbleWithAvatar() }
        val avatarWithout = bothThemes("пузырь-полоса", 320, 140) { avatarWithoutBubble() }
        for (name in listOf("светлая", "тёмная")) {
            val colors = theme(name)
            // Сравниваются две сцены, а не координата с числом: у пузыря без аватара полоса
            // начинается сразу за скруглением, у пузыря с аватаром — только под аватаром.
            // Разница и есть «перекрыл», и её не надо угадывать по вёрстке.
            val top = firstGreen(avatarWithout.getValue(name), colors.navigation)
            val underAvatar = firstGreen(withAvatar.getValue(name), colors.navigation)
            assertTrue(
                // Аватар опускает начало полосы на 17 px: без него она видна с y=34 (там
                // скругление уже кончилось), с ним — только с y=51, из-под аватара. Порог 10
                // отделяет «перекрыл» от «не перекрыл» с запасом и не привязан к вёрстке.
                underAvatar - top >= 10,
                "$name: полоса начинается на y=$underAvatar против y=$top без аватара — " +
                    "аватар её не перекрыл: он либо полупрозрачен, либо стоит рядом с пузырём",
            )
        }
    }

    /** У своего сообщения ни полосы, ни аватара, ни имени: кто говорит — и так понятно. */
    @Test
    fun my_message_without_strips_author() {
        for ((name, snapshot) in bothThemes("пузырь-мой", 320, 120) { myBubble() }) {
            assertTrue(
                !snapshot.has(theme(name).navigation),
                "$name: в своём сообщении нашлась салатовая полоса",
            )
        }
    }

    // ── Тема ───────────────────────────────────────────────────────────────────

    /**
     * Признак готовности У.2 — «компонент рисуется в двух темах без правки кода экрана».
     * Здесь он проверяется единственным честным способом: снимки двух тем обязаны
     * расходиться. Компонент, забравший цвет мимо темы, даст одинаковые пиксели.
     */
    @Test
    fun тема_меняет_пиксели_каждого_компонента() {
        for ((name, size, content) in ВСЕ_КОМПОНЕНТЫ) {
            val snapshots = bothThemes("тема-$name", size.first, size.second, content = content)
            val difference = snapshots.getValue("светлая").difference(snapshots.getValue("тёмная"))
            assertTrue(
                difference > 0.10,
                "$name: темы расходятся лишь на ${(difference * 100).toInt()}% пикселей — " +
                    "похоже, цвет взят мимо темы",
            )
        }
    }

    // ── Контраст на реальных пикселях ──────────────────────────────────────────

    /**
     * Решение заказчика от 2026-08-23, записанное пикселями, а не словами: на светлой теме
     * текст на салатовом — чёрный (10,08 : 1), на тёмной остаётся белым (2,08 : 1, ниже
     * порога, принято осознанно — там весь текст белый).
     *
     * Тест закрепляет обе половины. Если однажды тёмная половина станет читаемой, он
     * покраснеет — и это правильно: решение принимал человек, менять его молча нельзя.
     */
    @Test
    fun текст_на_салатовой_кнопке_чёрный_в_светлой_и_белый_в_тёмной() {
        val snapshots = bothThemes("кнопка", 160, 60) {
            Box(Modifier.padding(9.dp)) { Button("Отправить", onClick = {}) }
        }
        val lightGlyph = snapshots.getValue("светлая").darkMost(x = 30..130, y = 20..40)
        val light = TimaContrast.ratio(lightGlyph, TimaColors.light.navigation)
        assertTrue(
            light >= TimaContrast.TEXT_THRESHOLD,
            "светлая: текст на салатовой кнопке даёт $light — решение заказчика было «чёрный»",
        )

        val darkGlyph = snapshots.getValue("тёмная").lightMost(x = 30..130, y = 20..40)
        val dark = TimaContrast.ratio(darkGlyph, TimaColors.dark.navigation)
        assertTrue(
            dark < TimaContrast.TEXT_THRESHOLD,
            "тёмная: текст на салатовом стал читаемым ($dark) — решение было «белый, 2,08», " +
                "и менять его молча нельзя",
        )
    }

    // ── Палитра ────────────────────────────────────────────────────────────────

    /**
     * Ни одного цвета мимо палитры.
     *
     * Архитектурное правило запрещает `Color(0x…)` в тексте дизайн-системы, но текстом всё
     * не поймать: цвет можно посчитать, смешать, взять у чужого компонента. Здесь проверяются
     * пиксели: каждый обязан быть смесью двух цветов темы — так выглядит и сглаживание, и
     * полупрозрачный токен на подложке. Чужой цвет смесью двух своих не окажется.
     */
    @Test
    fun в_снимках_нет_цветов_вне_палитры() {
        for ((name, size, content) in ВСЕ_КОМПОНЕНТЫ) {
            for ((nameTheme, snapshot) in bothThemes("палитра-$name", size.first, size.second, content = content)) {
                val palette = themePalette(theme(nameTheme))
                val memory = HashMap<ULong, Boolean>()
                val foreign = snapshot.pixels().firstOrNull { pixel ->
                    !memory.getOrPut(pixel.value) { blend(pixel, palette) }
                }
                if (foreign != null) {
                    fail("$name/$nameTheme: пиксель $foreign не является смесью цветов темы")
                }
            }
        }
    }

    /**
     * У проверки палитры есть зубы.
     *
     * Проверка «пиксель есть смесь двух цветов темы» тем шире, чем больше в палитре цветов,
     * и однажды могла бы принять любой пиксель — тогда предыдущий тест зеленел бы на пустом
     * месте. Поэтому проверяется и обратное: чужой цвет обязан быть отвергнут.
     *
     * Взят красный — и это не случайный выбор. **Красного в палитре нет вовсе**: макет
     * отличает опасное действие словом, незаполненной кнопкой и последним местом в списке,
     * а не цветом. Если красный однажды окажется смесью цветов темы, значит палитра
     * расползлась.
     */
    @Test
    fun чужой_цвет_палитрой_не_принимается() {
        for (name in listOf("светлая", "тёмная")) {
            val palette = themePalette(theme(name))
            for (foreign in listOf(Color(0xFFFF0000), Color(0xFF7F00FF), Color(0xFF00A0A0))) {
                assertTrue(
                    !blend(foreign, palette),
                    "$name: $foreign сошёл за смесь цветов темы — проверка палитры потеряла смысл",
                )
            }
        }
    }

    // ── Сцены ──────────────────────────────────────────────────────────────────

    @Composable
    private fun avatarWithoutBubble() = Box(Modifier.padding(start = ЛЕВЫЙ_КРАЙ.dp, top = TOP.dp)) {
        // продолжение = true: имени и аватара нет, остаётся ровно полоса и рамка.
        Bubble(my = false, author = "Аня", avatar = "А", continuation = true) {
            Caption("Полоса обязана огибать скругление")
        }
    }

    @Composable
    private fun bubbleWithAvatar() = Box(Modifier.padding(start = ЛЕВЫЙ_КРАЙ.dp, top = TOP.dp)) {
        Bubble(my = false, author = "Аня", avatar = "А") { Caption("Аватар внутри пузыря") }
    }

    @Composable
    private fun myBubble() = Box(Modifier.padding(start = ЛЕВЫЙ_КРАЙ.dp, top = TOP.dp)) {
        Bubble(my = true) { Caption("Своё сообщение") }
    }

    private companion object {
        /** Отступы сцены: аватар выступает вверх, и без верхнего поля он обрезался бы. */
        const val ЛЕВЫЙ_КРАЙ = 8
        const val TOP = 24

        /** Смесь считается по каналам; допуск шире пиксельного — это сглаживание. */
        const val ДОПУСК_СМЕСИ = 8.0 / 255

        val ВСЕ_КОМПОНЕНТЫ: List<Triple<String, Pair<Int, Int>, @Composable () -> Unit>> = listOf(
            Triple("счётчик", 40 to 40) { Box(Modifier.padding(9.dp)) { Counter(7) } },
            Triple("аватар", 60 to 60) { Box(Modifier.padding(9.dp)) { Avatar("АБ") } },
            Triple("чип", 90 to 40) { Box(Modifier.padding(9.dp)) { Chip("E2E", kind = ChipKind.Confirmed) } },
            Triple("кнопка", 160 to 60) { Box(Modifier.padding(9.dp)) { Button("Отправить", onClick = {}) } },
            Triple("кнопка-опасная", 160 to 60) {
                Box(Modifier.padding(9.dp)) { Button("Удалить", onClick = {}, kind = ButtonKind.Dangerous) }
            },
            Triple("строка-списка", 300 to 76) {
                Box(Modifier.padding(top = 6.dp)) {
                    ListLine(
                        left = { Avatar("АБ") },
                        right = { Counter(3) },
                        middle = {
                            Name("Аня Борисова")
                            Secondary("Полоса обязана огибать скругление")
                        },
                    )
                }
            },
            Triple("шапка-окна", 320 to 76) {
                WindowHeader(
                    title = "Переписки",
                    logo = "Т",
                    onSwitchWindows = {},
                    right = { IconButton(glyph = "+", onClick = {}, live = false) },
                )
            },
            Triple("пузырь", 320 to 140) {
                Box(Modifier.padding(start = 8.dp, top = 24.dp).width(300.dp)) {
                    Bubble(my = false, author = "Аня", avatar = "А") { Caption("Аватар внутри пузыря") }
                }
            },
        )

        /**
         * Палитра темы — **отражением**, а не списком.
         *
         * Списком её пришлось бы дописывать при каждом новом токене, и однажды не дописали
         * бы: тест позеленел бы сам, потому что «чужого цвета» стало меньше. Отражение
         * берёт все цвета темы, включая те, которых ещё нет.
         *
         * Цвета приводятся к непрозрачным: полупрозрачный токен на подложке и есть смесь
         * своего цвета с ней, а смесь проверяется отрезком.
         */
        fun themePalette(colors: TimaColors): List<Color> {
            val all = TimaColors::class.java.methods
                .filter { it.name.startsWith("get") && it.returnType == Long::class.javaPrimitiveType }
                .map { Color((it.invoke(colors) as Long).toULong()) }
            val opaque = all.filter { it.alpha == 1f }
            // Полупрозрачный токен на непрозрачной подложке — это тоже цвет темы, просто
            // составной: рамка на белом, «мягкий акцент» на поверхности. Без них смесью
            // двух пришлось бы объявлять любой пиксель текста, лежащего на таком фоне.
            val overlaid = all.flatMap { token -> opaque.map { TimaContrast.overlay(token, it) } }
            return (opaque + overlaid)
                .map { Color(it.red, it.green, it.blue, 1f) }
                .distinctBy { it.value }
        }

        /** Первая строка, где в столбце полосы виден салатовый. Полосы нет — тест валится. */
        fun firstGreen(snapshot: Snapshot, navigation: Color): Int =
            (0 until snapshot.height).firstOrNull {
                Snapshot.close(snapshot.color(ЛЕВЫЙ_КРАЙ + 2, it), navigation)
            } ?: fail("полосы автора нет вовсе")

        /** Лежит ли цвет на отрезке между какими-нибудь двумя цветами палитры. */
        fun blend(color: Color, palette: List<Color>): Boolean {
            for (i in palette.indices) {
                for (j in i until palette.size) {
                    if (distanceUntilSegment(color, palette[i], palette[j]) <= ДОПУСК_СМЕСИ) return true
                }
            }
            return false
        }

        /** Насколько цвет далёк от отрезка между двумя цветами палитры — по худшему каналу. */
        fun distanceUntilSegment(color: Color, a: Color, b: Color): Double {
            val ab = floatArrayOf(b.red - a.red, b.green - a.green, b.blue - a.blue)
            val ap = floatArrayOf(color.red - a.red, color.green - a.green, color.blue - a.blue)
            val length = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2]
            val t = if (length == 0f) 0f else ((ap[0] * ab[0] + ap[1] * ab[1] + ap[2] * ab[2]) / length).coerceIn(0f, 1f)
            var worst = 0.0
            for (k in 0..2) worst = max(worst, abs(ap[k] - t * ab[k]).toDouble())
            return worst
        }
    }
}
