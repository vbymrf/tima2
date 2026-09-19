package io.tima.feature.chat

import androidx.compose.runtime.Composable
import io.tima.core.ui.Stage
import io.tima.core.ui.TimaColors
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.MessageDisplay
import io.tima.testui.Snapshot
import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.bothThemes
import io.tima.testui.capture
import io.tima.testui.theme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Окно переписок в снимках (К5.2).
 *
 * Проверяются утверждения, а не картинка: непрочитанное видно янтарём, переписка без
 * имени всё равно в списке, «переписок нет» не показывается до первого ответа базы, и
 * гроздь создания стоит там, где ей положено в этом формате.
 */
class ChatsScreenTest {

    /**
     * Пустой список до ответа базы — **не** «переписок нет».
     *
     * Единственное настоящее решение этого окна, и его легко потерять: `chats.isEmpty()`
     * верно в обоих случаях, а человеку в первую секунду после запуска показали бы, что у
     * него нет переписок.
     */
    /**
     * **Экран заливает свой фон** (находка 29).
     *
     * Снимается на краске, которой в палитре нет: видно её — значит экран показывает то,
     * что под ним. Подложка цветом темы такого не поймала бы никогда, потому что красит
     * ровно то же, что покрасил бы экран.
     */
    @Test
    fun экран_заливает_свой_фон() {
        val snapshots = bothThemes("переписки-фон", WIDTH, HEIGHT, backdrop = FOREIGN_BACKGROUND) { screen(list()) }
        for ((name, snapshot) in snapshots) {
            assertTrue(!snapshot.has(FOREIGN_BACKGROUND), "$name: сквозь экран видна подложка")
        }
    }

    @Test
    fun до_ответа_базы_не_написано_что_переписок_нет() {
        val untilAnswer = capture("переписки-до-ответа", WIDTH, HEIGHT, dark = false) {
            screen(ChatsState(read = false))
        }
        val empty = capture("переписки-пусто", WIDTH, HEIGHT, dark = false) {
            screen(ChatsState(read = true))
        }

        assertTrue(
            untilAnswer.difference(empty) > 0.0,
            "«переписок пока нет» показано до того, как база ответила",
        )
    }

    @Test
    fun непрочитанное_видно_янтарём() {
        for ((name, snapshot) in bothThemes("переписки", WIDTH, HEIGHT) { screen(list()) }) {
            assertTrue(
                snapshot.patchHas(theme(name).activity, y = FEED),
                "$name: счётчика непрочитанного не видно",
            )
        }
    }

    /**
     * Переписка без имени остаётся в списке.
     *
     * Имя приезжает с профилем, и его может не быть. Спрятать строку значило бы спрятать
     * сообщение — то есть человек не узнает, что ему написали.
     */
    @Test
    fun переписка_без_имени_остаётся_в_списке() {
        val withName = capture("переписки-с-именем", WIDTH, HEIGHT, dark = false) {
            screen(ChatsState(chats = listOf(line("chat-1", title = "Аня")), read = true))
        }
        val nameWithout = capture("переписки-без-имени", WIDTH, HEIGHT, dark = false) {
            screen(ChatsState(chats = listOf(line("chat-1", title = null)), read = true))
        }
        val empty = capture("переписки-ничего", WIDTH, HEIGHT, dark = false) {
            screen(ChatsState(read = true))
        }

        assertTrue(nameWithout.difference(empty) > 0.0, "строки без имени не нарисовалось вовсе")
        assertTrue(
            nameWithout.difference(withName) > 0.0,
            "строка без имени нарисовалась так же, как с именем — значит имя не показано",
        )
    }

    /**
     * Гроздь создания на телефоне висит над списком, а на широком опускается.
     *
     * Проверяется через [ЭкранПереписок] целиком, а не через компонент: экран не должен
     * ничего знать про формат, и именно это здесь и подтверждается — тот же вызов, другая
     * ширина, другое место кнопки.
     */
    @Test
    fun гроздь_создания_переезжает_вместе_с_форматом() {
        val phone = capture("переписки-телефон", WIDTH, HEIGHT, dark = false) { screen(list()) }
        val wide = capture("переписки-широкий", 1000, HEIGHT, dark = false) { screen(list()) }

        val bottomPhone = (HEIGHT - 30) until HEIGHT
        assertTrue(
            phone.patchHas(TimaColors.light.navigation, y = bottomPhone),
            "на телефоне кнопка создания обязана висеть над списком у нижнего края",
        )
        assertTrue(
            wide.patchHas(TimaColors.light.functional, y = bottomPhone),
            "на широком формате гроздь опускается в полосу на функциональной подложке",
        )
    }

    /**
     * Имя в списке переписок подчиняется «Виду» — решение заказчика 2026-09-19 («отображение
     * единое для окна „Телефон“»).
     *
     * Ловится подменой одной галки: тот же человек, тот же экран, другой выбор в «Виде» —
     * и строка обязана нарисоваться иначе. До этой правки строка показывала `title`
     * переписки и на «Вид» не смотрела вовсе, то есть оба снимка совпали бы.
     */
    @Test
    fun имя_в_списке_переписок_подчиняется_виду() {
        val who = ChatPerson(name = "Аня Борисова", nick = "anna_p", userName = "anna")
        val byName = capture("переписки-вид-имя", WIDTH, HEIGHT, dark = false) {
            screen(one(), who, PersonLook(checked = setOf(PersonField.Name)))
        }
        val byNick = capture("переписки-вид-ник", WIDTH, HEIGHT, dark = false) {
            screen(one(), who, PersonLook(checked = setOf(PersonField.Nick)))
        }
        assertTrue(
            byName.difference(byNick) > 0.0,
            "«Вид» не влияет на строку переписки: имя и ник нарисовались одинаково",
        )
    }

    /**
     * Человека нет (группа) — остаётся название переписки.
     *
     * Иначе правка забрала бы у групп имя: у них собеседника не бывает.
     */
    @Test
    fun у_группы_остаётся_название_переписки() {
        val withoutPerson = capture("переписки-без-человека", WIDTH, HEIGHT, dark = false) {
            screen(one(), who = null, look = PersonLook.DEFAULT)
        }
        val empty = capture("переписки-ничего-2", WIDTH, HEIGHT, dark = false) {
            screen(ChatsState(read = true), who = null, look = PersonLook.DEFAULT)
        }
        assertTrue(withoutPerson.difference(empty) > 0.0, "строка без человека не нарисовалась вовсе")
    }

    // Два правила шапки — плашка и логотип только на телефоне — переехали в
    // КаркасОкнаTest вместе с самой шапкой: экран стал содержимым вкладки, а шапку
    // рисует общий каркас. Держать их здесь значило бы проверять чужое.

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800
        const val ZONE_1 = 56
        val FEED = ZONE_1 until HEIGHT

        fun line(
            chatId: String,
            title: String? = "Аня Борисова",
            preview: String? = "чужое сообщение",
            unread: Int = 3,
            lastOutgoing: Boolean = false,
            display: MessageDisplay = MessageDisplay.RECEIVED,
        ) = ChatSummary(
            chatId = chatId,
            title = title,
            kind = ChatKind.Personal,
            peerId = "u-1",
            preview = preview,
            lastOutgoing = lastOutgoing,
            lastDisplay = display,
            atMs = 1_700_000_000_000,
            unread = unread,
        )

        fun list() = ChatsState(
            chats = listOf(
                line("chat-1"),
                line("chat-2", title = "Поход", preview = "моё сообщение", unread = 0, lastOutgoing = true, display = MessageDisplay.SENT),
                line("chat-3", title = null, preview = null, unread = 1, display = MessageDisplay.UNREADABLE),
            ),
            read = true,
        )

        /**
         * Экран **внутри стана**, как в приложении.
         *
         * Иначе раскладки нет вовсе, и `LocalРаскладка` отказывает — намеренно: молчаливое
         * «считаем телефоном» однажды дало телефонную кнопку на тысяче точек ширины.
         */
        fun one() = ChatsState(chats = listOf(line("chat-1", unread = 0)), read = true)

        @Composable
        fun screen(state: ChatsState) = Stage(
            column = { ChatsScreen(state = state, onOpen = {}) },
        )

        /** Тот же экран, но с собеседником и «Видом» — как его зовёт окно «Телефон». */
        @Composable
        fun screen(state: ChatsState, who: ChatPerson?, look: PersonLook) = Stage(
            column = {
                ChatsScreen(state = state, onOpen = {}, personOf = { who }, look = look)
            },
        )
    }
}
