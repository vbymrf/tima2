package io.tima.shared

import io.tima.core.notify.Notice
import io.tima.core.notify.NoticeKind
import io.tima.core.notify.Notifier
import io.tima.core.words.RussianWords
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.BookKey
import io.tima.domain.chat.BookList
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.PersonLook
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Правила уведомлений — У5…У10.
 *
 * Проверяется не «показалось ли», а **шесть решений**, каждое из которых легко отменить
 * одной строкой, и экран при этом останется на вид работающим.
 */
class NoticesTest {

    private class Показ : Notifier {
        val строки = mutableListOf<Notice>()
        val снято = mutableListOf<String>()
        override fun show(notice: Notice) {
            строки += notice
        }

        override fun hide(key: String) {
            снято += key
        }

        override fun hideAll() = Unit
    }

    private val показ = Показ()

    private val борис = BookEntry(
        id = BookKey.ofPhone("+79160001122"),
        phone = "+79160001122",
        namePhone = "Борис",
        userId = "u-борис",
    )

    private fun уведомления(
        книга: Map<String, BookEntry> = mapOf("u-борис" to борис),
        карточки: Map<String, ChatPerson> = emptyMap(),
    ) = Notices(
        notifier = показ,
        me = "u-я",
        entryOf = { id -> книга[id] },
        cardOf = { id -> карточки[id] },
        look = { PersonLook() },
        words = { RussianWords },
    )

    // ── две стадии одной строки (У6) ────────────────────────────────────────

    @Test
    fun до_проверки_подписи_имя_не_называется() = runTest {
        // Открытая часть конверта подписью не покрыта: «Мама написала» подделал бы
        // всякий, кто знает её `user_id`, — а он виден по группе и по поиску.
        уведомления().arrived("chat-1", "u-борис")

        val строка = показ.строки.single()
        assertNull(строка.who, "имя названо до проверки подписи")
        assertEquals(RussianWords.notices.newMessage, строка.what)
    }

    @Test
    fun подпись_сошлась_и_та_же_строка_получает_имя() = runTest {
        val уведомления = уведомления()
        уведомления.arrived("chat-1", "u-борис")
        уведомления.opened("chat-1", "u-борис")

        // Ключ один — значит строка одна: два уведомления об одном сообщении человек
        // читает как два сообщения.
        assertEquals(listOf("chat-1", "chat-1"), показ.строки.map { it.key })
        assertEquals("Борис", показ.строки.last().who)
    }

    @Test
    fun ключ_не_доехал_и_строка_остаётся_безымянной() = runTest {
        // Ровно тот случай, ради которого первая стадия и нужна: разбора не было, а
        // узнать, что пришло, человеку надо.
        уведомления().arrived("chat-1", "u-борис")
        assertEquals(1, показ.строки.size)
        assertNull(показ.строки.single().who)
    }

    // ── кого не уведомляем (У8) ─────────────────────────────────────────────

    @Test
    fun заблокированный_молчит() = runTest {
        // Явной проверкой, а не само собой. Пока строка ставилась после разбора,
        // молчание выходило бесплатно — конверт заблокированного мы не открываем (Л9).
        val блок = борис.copy(list = BookList.Blocked, known = true)
        val уведомления = уведомления(книга = mapOf("u-борис" to блок))

        assertTrue(!уведомления.arrived("chat-1", "u-борис"))
        уведомления.opened("chat-1", "u-борис")
        уведомления.calling("call-1", "u-борис")

        assertTrue(показ.строки.isEmpty(), "заблокированный уведомил о себе")
    }

    @Test
    fun своё_с_другого_устройства_молчит() = runTest {
        assertTrue(!уведомления().arrived("chat-1", "u-я"))
        assertTrue(показ.строки.isEmpty(), "человек уведомлён о собственном сообщении")
    }

    @Test
    fun открытая_переписка_не_уведомляет() = runTest {
        // Человек читает её глазами. Строка в шторке была бы уведомлением о том, что он
        // и так видит.
        val уведомления = уведомления()
        уведомления.watching("chat-1")

        assertTrue(!уведомления.arrived("chat-1", "u-борис"))
        assertTrue(уведомления.arrived("chat-2", "u-борис"), "молчат и чужие переписки")

        // Ушли с экрана — переписка снова уведомляет.
        уведомления.watching(null)
        assertTrue(уведомления.arrived("chat-1", "u-борис"))
    }

    @Test
    fun убранное_окно_возвращает_уведомления_открытой_переписке() = runTest {
        // Человек свернул приложение (или закрыл окно в трей), не выходя из переписки.
        // Она числится открытой — но глазами он её не видит, и молчать не за что.
        // Без этого «у меня не приходят сообщения» появляется у того, кто просто нажал
        // «Домой» на чужой реплике.
        val уведомления = уведомления()
        уведомления.watching("chat-1")
        assertTrue(!уведомления.arrived("chat-1", "u-борис"))

        уведомления.windowVisible(false)
        assertTrue(уведомления.arrived("chat-1", "u-борис"), "свёрнутое окно продолжает глушить")

        уведомления.windowVisible(true)
        assertTrue(!уведомления.arrived("chat-1", "u-борис"))
    }

    // ── как назван человек (У9) ─────────────────────────────────────────────

    @Test
    fun незнакомца_зовём_ником() = runTest {
        val уведомления = уведомления(
            книга = emptyMap(),
            карточки = mapOf("u-чужой" to ChatPerson(nick = "anna_kovaleva")),
        )
        уведомления.opened("chat-1", "u-чужой")

        assertEquals("@anna_kovaleva", показ.строки.single().who)
    }

    @Test
    fun ника_нет_и_имени_нет_вовсе() = runTest {
        // Решение заказчика 2026-09-24: выдуманное имя хуже отсутствующего.
        val уведомления = уведомления(книга = emptyMap(), карточки = emptyMap())
        уведомления.opened("chat-1", "u-чужой")

        val строка = показ.строки.single()
        assertNull(строка.who)
        assertEquals(RussianWords.notices.newMessage, строка.what, "строка без имени обещает имя")
    }

    // ── звонок (У7) ─────────────────────────────────────────────────────────

    @Test
    fun звонок_называет_имя_сразу_и_снимается_по_концу() = runTest {
        // Кто звонит, утверждает СЕРВЕР: строку в `calls` заводит он, а не звонящий.
        // Мы ему в этом уже верим каждый раз, когда рисуем журнал (ADR-0026).
        val уведомления = уведомления()
        уведомления.calling("call-1", "u-борис")

        val строка = показ.строки.single()
        assertEquals("Борис", строка.who)
        assertEquals(NoticeKind.Call, строка.kind, "звонок неотличим от сообщения")

        уведомления.callOver("call-1")
        assertEquals(listOf(строка.key), показ.снято)
    }

    @Test
    fun ключи_звонка_и_переписки_не_сталкиваются() = runTest {
        val уведомления = уведомления()
        уведомления.arrived("call-1", "u-борис")
        уведомления.calling("call-1", "u-борис")

        // Совпади ключи — конец звонка снял бы уведомление о сообщении.
        assertEquals(2, показ.строки.map { it.key }.toSet().size, "звонок и переписка делят ключ")
    }
}
