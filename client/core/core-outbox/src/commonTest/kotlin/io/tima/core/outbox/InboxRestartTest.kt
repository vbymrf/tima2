package io.tima.core.outbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * К3.7 для входящих: убийство процесса в каждом состоянии.
 *
 * **Почему у входящей машины нет `recoverOnStart`, а у исходящей есть.** У исходящей
 * есть состояние «взято в работу» — `SENDING`: между «забрал» и «получил ответ»
 * процесс может умереть, и запись останется занятой навсегда. У входящей такого
 * состояния **нет ни одного**: разбор не «занимает» запись, он либо доводит её до
 * `STORED`, либо оставляет ровно там, где взял. Значит восстанавливать нечего — и это
 * свойство, которое стоит проверять, а не помнить.
 *
 * Перезапуск изображается новой машиной над тем же хранилищем: именно это и происходит
 * при запуске приложения.
 */
class InboxRestartTest {

    private val store = InMemoryInboxStore()
    private val конверт = byteArrayOf(9, 9, 9)
    private val тело = byteArrayOf(1, 2)

    /** Новая машина над тем же хранилищем — то же, что запуск приложения. */
    private fun послеПерезапуска() = Inbox(store, nowMs = { 5_000 })

    private val положить: (IncomingEntry, ByteArray) -> Unit = { _, _ -> }

    @Test
    fun убитое_до_разбора_разбирается_после_перезапуска() {
        // Самый частый случай: конверт записан, процесс умер до расшифровки. Сообщение
        // обязано дойти до человека без участия сервера — живой канал его не повторит.
        Inbox(store, nowMs = { 1_000 }).receive("chat-1", 5, конверт)

        val после = послеПерезапуска()
        val разобрано = после.openNext({ OpenOutcome.Opened(тело) }, положить)

        assertNotNull(разобрано)
        assertEquals(IncomingState.STORED, разобрано.state)
        assertEquals(0, после.pending().count { it.state == IncomingState.RECEIVED })
    }

    @Test
    fun убитое_посреди_записи_содержимого_остаётся_на_разбор() {
        // Запись содержимого идёт ДО смены состояния — ровно чтобы падение здесь не
        // теряло сообщение. Изображаем падение исключением из persist.
        val inbox = Inbox(store, nowMs = { 1_000 })
        inbox.receive("chat-1", 5, конверт)

        runCatching {
            inbox.openNext({ OpenOutcome.Opened(тело) }) { _, _ -> error("диск отказал") }
        }

        assertEquals(IncomingState.RECEIVED, store.byKey("chat-1", 5)?.state)
        // И после перезапуска разбирается заново, без потерь.
        assertEquals(
            IncomingState.STORED,
            послеПерезапуска().openNext({ OpenOutcome.Opened(тело) }, положить)?.state,
        )
    }

    @Test
    fun нечитаемое_переживает_перезапуск_и_не_разбирается_вслепую() {
        // UNDECRYPTABLE не терминальное, но и не «в работе»: пока ключа нет, повторять
        // разбор при каждом запуске значит жечь батарею впустую.
        val inbox = Inbox(store, nowMs = { 1_000 })
        inbox.receive("chat-1", 5, конверт)
        inbox.openNext({ OpenOutcome.NoKey("ключ эпохи не пришёл") }, положить)

        val после = послеПерезапуска()

        assertNull(после.openNext({ OpenOutcome.Opened(тело) }, положить), "разбирать нечего")
        val запись = store.byKey("chat-1", 5)
        assertEquals(IncomingState.UNDECRYPTABLE, запись?.state)
        assertEquals("ключ эпохи не пришёл", запись?.undecryptableReason, "причина не теряется")
        assertEquals(1, запись?.attempts, "перезапуск не обнуляет счётчик попыток")

        // Появился ключ — и только тогда разбор повторяется.
        assertEquals(1, после.retryUndecryptable())
        assertEquals(
            IncomingState.STORED,
            после.openNext({ OpenOutcome.Opened(тело) }, положить)?.state,
        )
    }

    @Test
    fun разобранное_и_прочитанное_перезапуск_не_трогает() {
        val inbox = Inbox(store, nowMs = { 1_000 })
        inbox.receive("chat-1", 5, конверт)
        inbox.receive("chat-1", 6, конверт)
        inbox.openNext({ OpenOutcome.Opened(тело) }, положить)
        inbox.openNext({ OpenOutcome.Opened(тело) }, положить)
        inbox.markRead("chat-1", 5)

        val после = послеПерезапуска()

        assertNull(после.openNext({ OpenOutcome.Opened(тело) }, положить), "заново разбирать нечего")
        assertEquals(IncomingState.READ, store.byKey("chat-1", 5)?.state)
        assertEquals(IncomingState.STORED, store.byKey("chat-1", 6)?.state)
    }

    @Test
    fun повторный_приём_после_перезапуска_не_размножает_запись() {
        // После перезапуска идёт догон истории, и он приносит то же самое. Ключ
        // уникальности назначен отправителем и входит в подпись — по нему и опознаём.
        Inbox(store, nowMs = { 1_000 }).receive("chat-1", 5, конверт)

        repeat(5) {
            послеПерезапуска().receive("chat-1", 5, конверт)
        }

        assertEquals(1, store.all().size, "перезапуски и догон не должны размножать запись")
    }

    @Test
    fun ни_одно_состояние_не_остаётся_занятым() {
        // Проверка того самого свойства: у входящей машины нет состояния «взято в
        // работу», поэтому и восстановления не нужно. Если такое состояние однажды
        // появится, этот тест обязан упасть — и заставить завести recoverOnStart.
        val занятые = IncomingState.entries.filter { it.name.endsWith("ING") }
        assertTrue(
            занятые.isEmpty(),
            "появилось состояние «в работе» ($занятые) — значит нужно восстановление при старте",
        )

        val inbox = Inbox(store, nowMs = { 1_000 })
        inbox.receive("chat-1", 5, конверт)
        inbox.receive("chat-1", 6, конверт)
        inbox.openNext({ OpenOutcome.NoKey("нет ключа") }, положить)

        // Всё незавершённое остаётся видимым человеку и после перезапуска.
        assertEquals(2, послеПерезапуска().pending().size)
    }
}
