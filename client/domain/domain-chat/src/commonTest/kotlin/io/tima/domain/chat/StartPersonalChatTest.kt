package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Начало личной переписки: порядок шагов и то, чего записывать нельзя.
 */
class StartPersonalChatTest {

    private var found: UserLookup = UserLookup.Found("u-аня", "Аня Борисова")
    private val written = mutableListOf<Entry>()

    private val case = StartPersonalChat(
        directory = UserDirectory { found },
        chats = ChatBook { chatId, kind, title, peerId ->
            written += Entry(chatId, kind, title, peerId)
        },
        // Порядок пары не важен — свойство самого вычисления; здесь достаточно
        // предсказуемости.
        ids = PersonalChatIds { a, b -> "чат:" + listOf(a, b).sorted().joinToString("+") },
    )

    @Test
    fun найденный_человек_превращается_в_переписку() = runTest {
        val outcome = case.byPhone("u-я", "+79990000001")

        assertIs<StartChatResult.Started>(outcome)
        assertEquals("чат:u-аня+u-я", outcome.chatId, "идентификатор считается из пары, а не даётся сервером")
        val entry = written.single()
        assertEquals("чат:u-аня+u-я", entry.chatId)
        assertEquals(ChatKind.Personal, entry.kind)
        assertEquals("Аня Борисова", entry.title)
        assertEquals("u-аня", entry.peerId, "собеседник нужен, чтобы взять его ключи")
    }

    /**
     * Имени нет — берётся номер.
     *
     * Сервер отдаёт имена не всегда: посторонних он не раскрывает. Пустая строка в списке
     * выглядела бы поломкой, а номер человек знает — он его и вводил.
     */
    @Test
    fun без_имени_переписка_называется_номером() = runTest {
        found = UserLookup.Found("u-аня", name = null)

        case.byPhone("u-я", "+79990000001")

        assertEquals("+79990000001", written.single().title)
    }

    @Test
    fun пустое_имя_считается_отсутствующим() = runTest {
        found = UserLookup.Found("u-аня", name = "   ")

        case.byPhone("u-я", "+79990000001")

        assertEquals("+79990000001", written.single().title)
    }

    /**
     * Свой собственный номер — **отдельный случай**, а не обычная переписка.
     *
     * У переписки с собой другие правила восстановления ключей (у self-чата нет живых
     * источников для peer-восстановления, ADR-0010). Записать её как личную значит однажды
     * применить к ней чужие правила.
     */
    @Test
    fun свой_номер_не_становится_личной_перепиской() = runTest {
        found = UserLookup.Found("u-я", "Я сам")

        val outcome = case.byPhone("u-я", "+79990000001")

        assertEquals(StartChatResult.Myself, outcome)
        assertTrue(written.isEmpty(), "записывать такую переписку нельзя")
    }

    /** Нет в TIMA — это не отказ: такого человека звать надо, а не сообщать об ошибке. */
    @Test
    fun ненайденный_номер_даёт_отдельный_исход() = runTest {
        found = UserLookup.NotFound

        val outcome = case.byPhone("u-я", "+79990000009")

        assertEquals(StartChatResult.NotFound, outcome)
        assertTrue(written.isEmpty())
    }

    @Test
    fun при_отказе_сети_ничего_не_записывается() = runTest {
        found = UserLookup.Offline(retryAfterMs = 5_000)

        val outcome = case.byPhone("u-я", "+79990000001")

        assertIs<StartChatResult.Offline>(outcome)
        assertEquals(5_000, outcome.retryAfterMs, "срок повтора доносится до экрана: без него он бесполезен")
        assertTrue(written.isEmpty(), "переписка с непроверенным собеседником не заводится")
    }

    @Test
    fun плохой_номер_доносит_причину() = runTest {
        found = UserLookup.BadPhone("нужен E.164")

        val outcome = case.byPhone("u-я", "89990000001")

        assertIs<StartChatResult.BadPhone>(outcome)
        assertEquals("нужен E.164", outcome.reason)
    }

    /** Пустой номер отсекается до сети: спрашивать сервер о пустоте незачем. */
    @Test
    fun пустой_номер_до_сети_не_доходит() = runTest {
        var asked = 0
        val own = StartPersonalChat(
            directory = UserDirectory { asked++; found },
            chats = ChatBook { _, _, _, _ -> },
            ids = PersonalChatIds { a, b -> "$a+$b" },
        )

        assertIs<StartChatResult.BadPhone>(own.byPhone("u-я", "   "))
        assertEquals(0, asked)
    }

    @Test
    fun пустой_я_отвергается_как_ошибка_кода() = runTest {
        // Не исход, а исключение: «кто я» приходит из сессии, и пустое значение означает
        // ошибку сборки приложения, а не действие человека.
        assertNull(runCatching { case.byPhone("", "+79990000001") }.getOrNull())
    }

    private class Entry(
        val chatId: String,
        val kind: ChatKind,
        val title: String?,
        val peerId: String?,
    )
}
