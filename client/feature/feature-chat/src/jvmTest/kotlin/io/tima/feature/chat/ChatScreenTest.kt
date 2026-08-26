package io.tima.feature.chat

import androidx.compose.runtime.Composable
import io.tima.core.ui.TimaColors
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageDisplay
import io.tima.testui.Snapshot
import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.bothThemes
import io.tima.testui.capture
import io.tima.testui.theme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Экран переписки в снимках (К5.2).
 *
 * Признак готовности К5 — «каждый экран есть чистый рендер `State`». Проверяется он
 * буквально: экрану дают состояние и смотрят, что нарисовалось. Никаких заглушек,
 * никаких вызовов — только [ChatState].
 *
 * Проверяются, как и в дизайн-системе, **утверждения макета**, а не картинка: своё
 * справа, чужое слева, шапка сверху, ввод снизу, нечитаемое остаётся строкой. Такой тест
 * говорит, что именно сломалось, и не краснеет от смены шрифта.
 */
class ChatScreenTest {

    /**
     * **Экран заливает свой фон** (находка 29).
     *
     * Снимается на краске, которой в палитре нет: видно её — значит экран показывает то,
     * что под ним. Подложка цветом темы такого не поймала бы никогда, потому что красит
     * ровно то же, что покрасил бы экран.
     */
    @Test
    fun экран_заливает_свой_фон() {
        val snapshots = bothThemes("чат-фон", WIDTH, HEIGHT, backdrop = FOREIGN_BACKGROUND) { screen(chat()) }
        for ((name, snapshot) in snapshots) {
            assertTrue(!snapshot.has(FOREIGN_BACKGROUND), "$name: сквозь экран видна подложка")
        }
    }

    @Test
    fun экран_рисуется_в_обеих_темах() {
        val snapshots = bothThemes("чат", WIDTH, HEIGHT) { screen(chat()) }
        val difference = snapshots.getValue("светлая").difference(snapshots.getValue("тёмная"))
        assertTrue(
            difference > 0.10,
            "темы расходятся лишь на ${(difference * 100).toInt()}% — цвет взят мимо темы",
        )
    }

    /**
     * Шапка сверху, зона ввода снизу — и обе на месте.
     *
     * Салатовая круглая кнопка есть и там, и там: «назад» в шапке, «отправить» в зоне
     * ввода. Если пропала одна из них, из окна либо не выйти, либо не отправить.
     */
    @Test
    fun header_top_and_zone_input_bottom() {
        for ((name, snapshot) in bothThemes("чат-полосы", WIDTH, HEIGHT) { screen(chat()) }) {
            val navigation = theme(name).navigation
            assertTrue(
                has(snapshot, navigation, y = 0 until ZONE_1),
                "$name: в шапке нет кнопки «назад» — из окна не выйти",
            )
            assertTrue(
                has(snapshot, navigation, y = (HEIGHT - ZONE_4) until HEIGHT),
                "$name: в зоне ввода нет кнопки отправки",
            )
        }
    }

    /**
     * Своё справа, чужое слева — и своё никогда не касается левого края.
     *
     * Это не украшение: направление реплики человек читает по стороне, а не по цвету.
     * Пузырь во всю ширину лишает переписку этого признака вовсе.
     */
    @Test
    fun own_right_foreign_left() {
        for ((name, snapshot) in bothThemes("чат-стороны", WIDTH, HEIGHT) { screen(chat()) }) {
            val colors = theme(name)
            val feed = ZONE_1 until (HEIGHT - ZONE_4)
            assertTrue(
                has(snapshot, colors.navigation, y = feed, x = 0 until 30),
                "$name: у чужого сообщения нет полосы автора у левого края",
            )
            assertTrue(
                has(snapshot, colors.my, y = feed, x = (WIDTH - 30) until WIDTH),
                "$name: своё сообщение не доходит до правого края",
            )
            assertTrue(
                !has(snapshot, colors.my, y = feed, x = 0 until 20),
                "$name: своё сообщение достаёт до левого края — сторона перестала различать",
            )
        }
    }

    /**
     * Короткая переписка прижата к вводу, а не к шапке.
     *
     * Это поймал снимок, и на глаз в коде такого не увидеть: `reverseLayout` переворачивает
     * порядок, но при содержимом короче окна раскладку решает выравнивание — по умолчанию
     * оно оставляло пять реплик под шапкой, а пустое место у поля ввода. Человек открывает
     * чат, чтобы видеть последнее сказанное рядом с тем местом, где отвечают.
     */
    @Test
    fun короткая_переписка_прижата_к_вводу() {
        for ((name, snapshot) in bothThemes("чат-низ", WIDTH, HEIGHT) { screen(chat()) }) {
            val atInput = (HEIGHT - ZONE_4 - 40) until (HEIGHT - ZONE_4)
            assertTrue(
                has(snapshot, theme(name).my, y = atInput),
                "$name: последней реплики нет у поля ввода — список прижался к шапке",
            )
        }
    }

    /**
     * Нечитаемое сообщение остаётся строкой.
     *
     * Спрятать его нельзя: человек должен видеть, что сообщение было, иначе он ждёт
     * ответа на то, чего собеседник, по его сведениям, не присылал.
     */
    @Test
    fun unreadable_stays_line() {
        val только_нечитаемое = ChatState(
            lines = listOf(line("d-1", MessageDisplay.UNREADABLE, outgoing = false, text = null)),
        )
        val empty = capture("чат-пусто", WIDTH, HEIGHT, dark = false) { screen(ChatState()) }
        val snapshot = capture("чат-нечитаемое", WIDTH, HEIGHT, dark = false) { screen(только_нечитаемое) }

        val feed = ZONE_1 until (HEIGHT - ZONE_4)
        assertTrue(
            snapshot.difference(empty) > 0.0,
            "нечитаемое сообщение не нарисовалось вовсе",
        )
        assertTrue(
            has(snapshot, TimaColors.light.navigation, y = feed, x = 0 until 30),
            "у нечитаемого нет даже полосы автора — сообщение исчезло из переписки",
        )
    }

    /**
     * «Не читается» и «ещё не разобрано» — **разные надписи**.
     *
     * Первое окончательно: ключа нет, подпись не сошлась. Второе — секунда между записью
     * конверта и его расшифровкой. Сказать в этот миг «не читается» значит соврать:
     * сообщение сейчас появится.
     *
     * Нашлось на живом прогоне приложения: экран говорил «не читается» о сообщении,
     * которое в базе лежало разобранным, — а список переписок в том же приложении
     * показывал «новое сообщение». Два места говорили человеку разное.
     */
    @Test
    fun неразобранное_и_нечитаемое_выглядят_по_разному() {
        val unreadable = ChatState(
            lines = listOf(line("d-1", MessageDisplay.UNREADABLE, outgoing = false, text = null)),
        )
        val notParsedYet = ChatState(
            lines = listOf(line("d-1", MessageDisplay.RECEIVED, outgoing = false, text = null)),
        )

        val first = capture("чат-нечитаемое-надпись", WIDTH, HEIGHT, dark = false) { screen(unreadable) }
        val second = capture("чат-разбирается", WIDTH, HEIGHT, dark = false) { screen(notParsedYet) }

        assertTrue(
            first.difference(second) > 0.0,
            "оба состояния нарисованы одинаково — человеку сказали «не читается» о том, " +
                "что сейчас появится",
        )
    }

    /**
     * Сообщение о беде видно, и **набранное при этом остаётся в поле**.
     *
     * Главное правило Store здесь становится видимым: отвергнутое по размеру сообщение не
     * забирает у человека написанное. Экран обязан показать и жалобу, и текст.
     */
    @Test
    fun беда_видна_и_набранное_остаётся() {
        val withTrouble = ChatState(
            lines = chat().lines,
            draft = "очень длинное сообщение",
            notice = ChatNotice.TooLarge(bytes = 5000, limit = 4096),
        )
        val troubleWithout = ChatState(lines = chat().lines, draft = "очень длинное сообщение")

        val withComplaint = capture("чат-беда", WIDTH, HEIGHT, dark = false) { screen(withTrouble) }
        val without = capture("чат-без-беды", WIDTH, HEIGHT, dark = false) { screen(troubleWithout) }
        val emptyField = capture("чат-пустое-поле", WIDTH, HEIGHT, dark = false) { screen(chat()) }

        assertTrue(withComplaint.difference(without) > 0.0, "сообщение о беде не нарисовалось")
        assertTrue(
            without.difference(emptyField) > 0.0,
            "набранного не видно в поле — а именно оно и не должно теряться",
        )
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800

        /** Зоны из макета: шапка и строка ввода. */
        const val ZONE_1 = 56
        const val ZONE_4 = 62

        /**
         * Пятно цвета в области — а не одиночный пиксель.
         *
         * Одиночный пиксель однажды уже соврал: сглаженная граница чужого пузыря на белом
         * дала ровно тот серый, каким залито своё сообщение, и проверка «своё не касается
         * левого края» покраснела на четырёх пикселях сглаживания.
         */
        fun has(
            snapshot: Snapshot,
            color: androidx.compose.ui.graphics.Color,
            y: IntRange,
            x: IntRange = 0 until snapshot.width,
        ): Boolean = snapshot.patchHas(color, x = x, y = y)

        fun line(
            dedupKey: String,
            display: MessageDisplay,
            outgoing: Boolean,
            text: String? = "проба",
            atMs: Long = 1_700_000_000_000,
        ) = ChatLine(
            dedupKey = dedupKey,
            chatId = "chat-1",
            display = display,
            text = text,
            outgoing = outgoing,
            atMs = atMs,
            localId = 1,
        )

        /** Смешанная переписка: чужое, своё во всех трёх состояниях, нечитаемое. */
        fun chat() = ChatState(
            lines = listOf(
                line("d-5", MessageDisplay.PENDING, outgoing = true, text = "ещё не ушло"),
                line("d-4", MessageDisplay.FAILED, outgoing = true, text = "не дошло совсем"),
                line("d-3", MessageDisplay.SENT, outgoing = true, text = "моё сообщение"),
                line("d-2", MessageDisplay.UNREADABLE, outgoing = false, text = null),
                line("d-1", MessageDisplay.RECEIVED, outgoing = false, text = "чужое сообщение"),
            ),
        )

        @Composable
        fun screen(state: ChatState) = ChatScreen(
            state = state,
            peer = "Аня",
            caption = "в сети",
            onSet = {},
            onSend = {},
            onBack = {},
        )
    }
}
