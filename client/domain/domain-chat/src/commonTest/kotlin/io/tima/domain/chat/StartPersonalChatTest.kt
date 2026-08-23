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

    private var найденное: UserLookup = UserLookup.Found("u-аня", "Аня Борисова")
    private val записано = mutableListOf<Запись>()

    private val случай = StartPersonalChat(
        directory = UserDirectory { найденное },
        chats = ChatBook { chatId, kind, title, peerId ->
            записано += Запись(chatId, kind, title, peerId)
        },
        // Порядок пары не важен — свойство самого вычисления; здесь достаточно
        // предсказуемости.
        ids = PersonalChatIds { a, b -> "чат:" + listOf(a, b).sorted().joinToString("+") },
    )

    @Test
    fun найденный_человек_превращается_в_переписку() = runTest {
        val исход = случай.byPhone("u-я", "+79990000001")

        assertIs<StartChatResult.Started>(исход)
        assertEquals("чат:u-аня+u-я", исход.chatId, "идентификатор считается из пары, а не даётся сервером")
        val запись = записано.single()
        assertEquals("чат:u-аня+u-я", запись.chatId)
        assertEquals(ChatKind.Personal, запись.kind)
        assertEquals("Аня Борисова", запись.title)
        assertEquals("u-аня", запись.peerId, "собеседник нужен, чтобы взять его ключи")
    }

    /**
     * Имени нет — берётся номер.
     *
     * Сервер отдаёт имена не всегда: посторонних он не раскрывает. Пустая строка в списке
     * выглядела бы поломкой, а номер человек знает — он его и вводил.
     */
    @Test
    fun без_имени_переписка_называется_номером() = runTest {
        найденное = UserLookup.Found("u-аня", name = null)

        случай.byPhone("u-я", "+79990000001")

        assertEquals("+79990000001", записано.single().title)
    }

    @Test
    fun пустое_имя_считается_отсутствующим() = runTest {
        найденное = UserLookup.Found("u-аня", name = "   ")

        случай.byPhone("u-я", "+79990000001")

        assertEquals("+79990000001", записано.single().title)
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
        найденное = UserLookup.Found("u-я", "Я сам")

        val исход = случай.byPhone("u-я", "+79990000001")

        assertEquals(StartChatResult.Myself, исход)
        assertTrue(записано.isEmpty(), "записывать такую переписку нельзя")
    }

    /** Нет в TIMA — это не отказ: такого человека звать надо, а не сообщать об ошибке. */
    @Test
    fun ненайденный_номер_даёт_отдельный_исход() = runTest {
        найденное = UserLookup.NotFound

        val исход = случай.byPhone("u-я", "+79990000009")

        assertEquals(StartChatResult.NotFound, исход)
        assertTrue(записано.isEmpty())
    }

    @Test
    fun при_отказе_сети_ничего_не_записывается() = runTest {
        найденное = UserLookup.Offline(retryAfterMs = 5_000)

        val исход = случай.byPhone("u-я", "+79990000001")

        assertIs<StartChatResult.Offline>(исход)
        assertEquals(5_000, исход.retryAfterMs, "срок повтора доносится до экрана: без него он бесполезен")
        assertTrue(записано.isEmpty(), "переписка с непроверенным собеседником не заводится")
    }

    @Test
    fun плохой_номер_доносит_причину() = runTest {
        найденное = UserLookup.BadPhone("нужен E.164")

        val исход = случай.byPhone("u-я", "89990000001")

        assertIs<StartChatResult.BadPhone>(исход)
        assertEquals("нужен E.164", исход.reason)
    }

    /** Пустой номер отсекается до сети: спрашивать сервер о пустоте незачем. */
    @Test
    fun пустой_номер_до_сети_не_доходит() = runTest {
        var спрошено = 0
        val свой = StartPersonalChat(
            directory = UserDirectory { спрошено++; найденное },
            chats = ChatBook { _, _, _, _ -> },
            ids = PersonalChatIds { a, b -> "$a+$b" },
        )

        assertIs<StartChatResult.BadPhone>(свой.byPhone("u-я", "   "))
        assertEquals(0, спрошено)
    }

    @Test
    fun пустой_я_отвергается_как_ошибка_кода() = runTest {
        // Не исход, а исключение: «кто я» приходит из сессии, и пустое значение означает
        // ошибку сборки приложения, а не действие человека.
        assertNull(runCatching { случай.byPhone("", "+79990000001") }.getOrNull())
    }

    private class Запись(
        val chatId: String,
        val kind: ChatKind,
        val title: String?,
        val peerId: String?,
    )
}
