package io.tima.shared

import io.tima.core.notify.Notice
import io.tima.core.notify.NoticeKind
import io.tima.core.notify.Notifier
import io.tima.core.words.RussianWords
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.BookKey
import io.tima.domain.chat.BookList
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.NoticeFrom
import io.tima.domain.chat.NoticeWhat
import io.tima.domain.chat.PersonLook
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Правила уведомлений — У5…У10 и ПЛАН-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ0…ЖУ6.
 *
 * Проверяется не «показалось ли», а **решения**, каждое из которых легко отменить одной
 * строкой, и экран при этом останется на вид работающим.
 */
class NoticesTest {

    private class Показ : Notifier {
        val строки = mutableListOf<Notice>()
        val снято = mutableListOf<String>()
        val значок = mutableListOf<Int>()
        override fun show(notice: Notice): Long {
            строки += notice
            return if (notice.alert) 300 else 0
        }

        override fun hide(key: String) {
            снято += key
        }

        override fun hideAll() = Unit

        override fun badge(total: Int) {
            значок += total
        }

        /** Сколько раз прозвучало. */
        val сигналов get() = строки.count { it.alert }
    }

    private val показ = Показ()
    private val журнал = MemoryNoticeJournal()

    /** Часы сервера в проверке: двигаются руками. */
    private var сейчас = 1_000_000L

    private val борис = BookEntry(
        id = BookKey.ofPhone("+79160001122"),
        phone = "+79160001122",
        namePhone = "Борис",
        userId = "u-борис",
    )

    private fun TestScope.уведомления(
        книга: Map<String, BookEntry> = mapOf("u-борис" to борис),
        карточки: Map<String, ChatPerson> = emptyMap(),
        звонок: Boolean = false,
    ) = Notices(
        notifier = показ,
        me = "u-я",
        entryOf = { id -> книга[id] },
        cardOf = { id -> карточки[id] },
        look = { PersonLook() },
        words = { RussianWords },
        journal = журнал,
        now = { сейчас },
        scope = this,
        inCall = { звонок },
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
    fun подпись_сошлась_та_же_строка_получает_имя_молча() = runTest {
        val уведомления = уведомления()
        уведомления.arrived("chat-1", "u-борис")
        уведомления.opened("chat-1", "u-борис")

        // Ключ один — значит строка одна: два уведомления об одном сообщении человек
        // читает как два сообщения.
        assertEquals(1, показ.строки.map { it.key }.toSet().size)
        assertEquals("Борис", показ.строки.last().who)
        // До 2026-09-30 вторая стадия звучала второй раз — два сигнала на сообщение.
        assertEquals(1, показ.сигналов, "имя появилось — второй сигнал")
    }

    @Test
    fun ключ_не_доехал_и_строка_остаётся_безымянной() = runTest {
        уведомления().arrived("chat-1", "u-борис")
        assertEquals(1, показ.строки.size)
        assertNull(показ.строки.single().who)
    }

    // ── кого не уведомляем (У8) ─────────────────────────────────────────────

    @Test
    fun заблокированный_молчит() = runTest {
        val блок = борис.copy(list = BookList.Blocked, known = true)
        val уведомления = уведомления(книга = mapOf("u-борис" to блок))

        assertTrue(!уведомления.arrived("chat-1", "u-борис"))
        уведомления.opened("chat-1", "u-борис")
        уведомления.calling("call-1", "u-борис")
        уведомления.missed("call-1", "u-борис")

        assertTrue(показ.строки.isEmpty(), "заблокированный уведомил о себе")
    }

    @Test
    fun своё_с_другого_устройства_молчит() = runTest {
        assertTrue(!уведомления().arrived("chat-1", "u-я"))
        assertTrue(показ.строки.isEmpty(), "человек уведомлён о собственном сообщении")
    }

    @Test
    fun открытая_переписка_не_уведомляет() = runTest {
        val уведомления = уведомления()
        уведомления.watching("chat-1")

        assertTrue(!уведомления.arrived("chat-1", "u-борис"))
        assertTrue(уведомления.arrived("chat-2", "u-борис"), "молчат и чужие переписки")

        уведомления.watching(null)
        assertTrue(уведомления.arrived("chat-1", "u-борис"))
    }

    @Test
    fun убранное_окно_возвращает_уведомления_открытой_переписке() = runTest {
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
        уведомления.arrived("chat-1", "u-чужой")
        уведомления.opened("chat-1", "u-чужой")

        assertEquals("@anna_kovaleva", показ.строки.last().who)
    }

    @Test
    fun ника_нет_и_имени_нет_вовсе() = runTest {
        // Решение заказчика 2026-09-24: выдуманное имя хуже отсутствующего.
        val уведомления = уведомления(книга = emptyMap(), карточки = emptyMap())
        уведомления.arrived("chat-1", "u-чужой")
        уведомления.opened("chat-1", "u-чужой")

        val строка = показ.строки.last()
        assertNull(строка.who)
        assertEquals(RussianWords.notices.newMessage, строка.what, "строка без имени обещает имя")
    }

    // ── звонок (У7) ─────────────────────────────────────────────────────────

    @Test
    fun звонок_называет_имя_сразу_и_снимается_по_концу() = runTest {
        val уведомления = уведомления()
        уведомления.calling("call-1", "u-борис")

        val строка = показ.строки.single()
        assertEquals("Борис", строка.who)
        assertEquals(NoticeKind.Call, строка.kind, "звонок неотличим от сообщения")

        уведомления.callOver("call-1")
        assertEquals(listOf(строка.key), показ.снято)
    }

    @Test
    fun ключи_звонка_и_вкладки_не_сталкиваются() = runTest {
        val уведомления = уведомления()
        уведомления.arrived("call-1", "u-борис")
        уведомления.calling("call-1", "u-борис")

        assertEquals(2, показ.строки.map { it.key }.toSet().size, "звонок и переписка делят ключ")
    }

    // ── звук — один на пачку (ЖУ3) ──────────────────────────────────────────

    @Test
    fun во_время_звонка_строка_есть_а_звука_нет() = runTest {
        // Заказчик 2026-10-02: при личном, видео или групповом звонке сообщения не звенят.
        val уведомления = уведомления(звонок = true)
        уведомления.arrived("chat-1", "u-борис")
        assertTrue(показ.строки.isNotEmpty(), "строка о сообщении должна быть")
        assertEquals(0, показ.сигналов, "во время звонка — без звука")
    }

    @Test
    fun пачка_сообщений_от_троих_один_сигнал() = runTest {
        // ПК 2026-09-30: «прилетело 4 уведомления — друг за другом пиликало».
        val уведомления = уведомления(книга = mapOf("u-борис" to борис, "u-аня" to борис.copy(userId = "u-аня"), "u-лёша" to борис.copy(userId = "u-лёша")))
        for (i in 0 until 20) {
            val кто = listOf("u-борис", "u-аня", "u-лёша")[i % 3]
            уведомления.arrived("chat-$кто", кто, ref = "chat-$кто/$i")
            сейчас += 50
        }

        assertEquals(1, показ.сигналов, "двадцать сообщений за секунду — один сигнал")
    }

    @Test
    fun второе_сообщение_той_же_сущности_молчит() = runTest {
        val уведомления = уведомления()
        уведомления.arrived("chat-1", "u-борис", ref = "chat-1/1")
        сейчас += 60_000
        уведомления.arrived("chat-1", "u-борис", ref = "chat-1/2")

        assertEquals(1, показ.сигналов, "у сущности уже есть уведомление — второе не звучит")
        assertEquals("тишина: у сущности уже есть", журнал.doneOf(NoticeWhat.Message, "chat-1/2"))
    }

    @Test
    fun догонка_молчит() = runTest {
        // Пришедшее при запуске и после разрыва канала — старое: без звука.
        val уведомления = уведомления()
        уведомления.arrived("chat-1", "u-борис", ref = "chat-1/1", sentAtMs = сейчас - 10 * 60_000)

        assertEquals(0, показ.сигналов)
        assertEquals(1, показ.строки.size, "строка есть — молча")
        assertEquals("тишина: догонка", журнал.doneOf(NoticeWhat.Message, "chat-1/1"))
    }

    // ── один звонок — одно уведомление (ЖУ0) ────────────────────────────────

    @Test
    fun тот_же_пропущенный_второй_раз_не_уведомляет() = runTest {
        // ПК 2026-09-30: четыре пропущенных — шестнадцать уведомлений: разрыв ленты и два
        // прохода разом поднимали те же звонки снова.
        val уведомления = уведомления()
        repeat(4) { уведомления.missed("call-1", "u-борис", from = NoticeFrom.CatchUp) }

        assertEquals(1, показ.строки.size)
    }

    @Test
    fun пропущенные_из_догонки_молчат() = runTest {
        val уведомления = уведомления()
        уведомления.missed("call-1", "u-борис", from = NoticeFrom.CatchUp)

        assertEquals(0, показ.сигналов)
    }

    // ── строка на вкладку, значок — сумма вкладок (ЖУ4) ──────────────────────

    @Test
    fun строка_вкладки_с_числом_и_значок_суммой() = runTest {
        // Пример заказчика: redmi 3 сообщения и 2 пропущенных, Moi 1 сообщение —
        // «Чаты» 2, «Звонки» 1, значок 3.
        val redmi = борис.copy(userId = "u-redmi", namePhone = "redmi")
        val moi = борис.copy(userId = "u-moi", namePhone = "Moi")
        val уведомления = уведомления(книга = mapOf("u-redmi" to redmi, "u-moi" to moi))
        repeat(3) { уведомления.arrived("chat-redmi", "u-redmi", ref = "chat-redmi/$it") }
        уведомления.missed("c1", "u-redmi")
        уведомления.missed("c2", "u-redmi")
        уведомления.arrived("chat-moi", "u-moi", ref = "chat-moi/1")

        val чаты = показ.строки.last { it.key == "tab:chats" }
        assertEquals(2, чаты.number)
        assertEquals(RussianWords.notices.messagesFrom(2), чаты.what)
        assertEquals(1, показ.строки.last { it.key == "tab:calls" }.number)
        assertEquals(3, показ.значок.last())
    }

    // ── что снимает (ЖУ6) ───────────────────────────────────────────────────

    @Test
    fun просмотр_сущности_минус_один_у_вкладки() = runTest {
        val уведомления = уведомления()
        уведомления.arrived("chat-1", "u-борис", ref = "chat-1/1")
        уведомления.arrived("chat-2", "u-борис", ref = "chat-2/1")

        уведомления.watching("chat-1")
        advanceUntilIdle()
        assertEquals(1, показ.строки.last { it.key == "tab:chats" }.number)

        уведомления.watching("chat-2")
        advanceUntilIdle()
        assertTrue("tab:chats" in показ.снято, "ноль — строки нет")
        assertEquals(0, показ.значок.last())
    }

    @Test
    fun открыли_звонки_число_обнулилось() = runTest {
        val уведомления = уведомления()
        уведомления.missed("c1", "u-борис")
        уведомления.missed("c2", "u-аня")

        уведомления.callsViewed()
        advanceUntilIdle()

        assertTrue("tab:calls" in показ.снято)
        assertEquals(0, показ.значок.last())
    }

    @Test
    fun seen_снимает_только_свой_звонок() = runTest {
        val уведомления = уведомления()
        уведомления.missed("c1", "u-борис")
        уведомления.missed("c2", "u-аня")

        уведомления.missedSeen("c1")
        advanceUntilIdle()

        assertEquals(1, показ.строки.last { it.key == "tab:calls" }.number)
    }

    @Test
    fun сверка_не_снимает_пропущенный_которого_нет_в_местном_журнале() = runTest {
        // Живая проверка 2026-09-30, Redmi: пропущенный пришёл лентой, а местный журнал
        // звонков его ещё не знал — сверка сняла, значок ушёл в ноль.
        val уведомления = уведомления()
        уведомления.missed("c1", "u-борис")

        уведомления.reconcile(unread = emptyMap(), missed = emptyMap())

        assertEquals(1, показ.значок.last(), "пропущенный остался")
    }

    @Test
    fun сверка_при_запуске_заводит_без_звука_и_снимает_прочитанное() = runTest {
        val уведомления = уведомления()
        уведомления.arrived("chat-старый", "u-борис", ref = "chat-старый/1")
        val былоСигналов = показ.сигналов

        уведомления.reconcile(unread = mapOf("chat-1" to false, "g-1" to true), missed = mapOf("c1" to "u-борис"))

        assertEquals(былоСигналов, показ.сигналов, "сверка не звучит")
        assertEquals(3, показ.значок.last(), "«Чаты» 1, «Группы» 1, «Звонки» 1 — прочитанный снят")
    }
}
