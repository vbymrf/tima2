package io.tima.feature.chat

import io.tima.domain.chat.CallHistory
import io.tima.domain.chat.CallHistoryPage
import io.tima.domain.chat.CallLog
import io.tima.domain.chat.CallRecord
import io.tima.domain.chat.CallStates
import io.tima.domain.chat.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Журнал звонков: что происходит, когда сервер молчит, и что — когда отвечает.
 *
 * Главная проверка здесь одна и она про метро: **отсутствие ответа не то же, что пустой
 * ответ**. Спутать их значит стереть человеку журнал при первом же походе под землю.
 */
class CallsStoreTest {

    private val я = "me"

    /** Местный журнал в памяти: ровно то, что делает база, и ничего сверх. */
    private class ЖурналВПамяти : CallLog {
        val rows = MutableStateFlow(emptyList<CallRecord>())
        var прополото = 0
        var отмечено = 0

        override fun page(limit: Int): Flow<List<CallRecord>> = rows
        override fun missed(me: String): Flow<Int> = rows.map { list ->
            list.count { !it.seen && it.state == CallStates.MISSED && it.initiatorId != me }
        }

        override fun count(): Flow<Int> = rows.map { it.size }

        override suspend fun remember(records: List<CallRecord>) {
            // Серверное побеждает, местное `seen` остаётся — как в базе.
            val было = rows.value.associateBy { it.callId }
            val стало = (было + records.associateBy { it.callId }).values
                .map { новое -> новое.copy(seen = было[новое.callId]?.seen ?: новое.seen) }
            rows.value = стало.sortedByDescending { it.createdAt }
        }

        override suspend fun markSeen() {
            отмечено++
            rows.value = rows.value.map { it.copy(seen = true) }
        }

        override suspend fun prune(olderThanMs: Long, keepRows: Int) {
            прополото++
            // Как в `CallLog.sq`: по возрасту — отбором, по числу — `ORDER BY created_at
            // DESC LIMIT`, то есть остаются САМЫЕ НОВЫЕ. Подделка, берущая первые
            // попавшиеся, проверяла бы не то, что работает на телефоне.
            rows.value = rows.value
                .filter { olderThanMs <= 0 || it.createdAt >= olderThanMs }
                .sortedByDescending { it.createdAt }
                .take(keepRows)
        }
    }

    private class НастройкиВПамяти : Settings {
        val values = MutableStateFlow(emptyMap<String, String>())
        override fun all(): Flow<Map<String, String>> = values
        override suspend fun put(name: String, value: String) {
            values.value = values.value + (name to value)
        }
    }

    private fun запись(id: String, at: Long, state: String = CallStates.MISSED, from: String = "peer") =
        CallRecord(
            callId = id,
            video = false,
            state = state,
            initiatorId = from,
            peerId = if (from == я) "peer" else я,
            createdAt = at,
        )

    private fun магазин(
        scope: CoroutineScope,
        log: CallLog,
        history: CallHistory,
        settings: Settings = НастройкиВПамяти(),
        now: Long = 10_000_000,
    ) = CallsStore(log, history, scope, я, settings, now = { now })

    @Test
    fun сервер_не_ответил_местный_журнал_остаётся_на_месте() = runTest {
        val log = ЖурналВПамяти()
        log.rows.value = listOf(запись("старый", 1_000))
        // `null` — до сервера не дошли. Не «звонков нет».
        val store = магазин(backgroundScope, log, CallHistory { _, _ -> null })

        store.opened()
        store.state.first { it.offline }

        assertEquals(1, log.rows.value.size, "журнал стёрли из-за отсутствия связи")
        assertEquals(0, log.прополото, "пропололи журнал по неответу сервера")
    }

    @Test
    fun сервер_ответил_журнал_записан_и_прополот() = runTest {
        val log = ЖурналВПамяти()
        val страница = CallHistoryPage(listOf(запись("новый", 5_000)))
        val store = магазин(backgroundScope, log, CallHistory { _, _ -> страница })

        store.opened()
        store.state.first { it.records.any { row -> row.callId == "новый" } }

        assertTrue(log.прополото > 0, "уборка по настройке не звалась")
    }

    @Test
    fun счётчик_считает_только_непросмотренные_и_только_мне() = runTest {
        val log = ЖурналВПамяти()
        log.rows.value = listOf(
            запись("мне", 3_000, from = "peer"),
            // У звонившего `missed` означает «не дозвонился». В счётчик пропущенных это
            // не идёт: он отвечает на «кому я не ответил», а не «кому не дозвонился».
            запись("я-звонил", 2_000, from = я),
            запись("уже смотрел", 1_000, from = "peer").copy(seen = true),
        )
        val store = магазин(backgroundScope, log, CallHistory { _, _ -> null })

        assertEquals(1, store.state.first { it.missed > 0 }.missed)
    }

    @Test
    fun открыли_вкладку_счётчик_гаснет() = runTest {
        val log = ЖурналВПамяти()
        log.rows.value = listOf(запись("мне", 3_000, from = "peer"))
        val store = магазин(backgroundScope, log, CallHistory { _, _ -> null })
        store.state.first { it.missed == 1 }

        store.opened()

        assertEquals(0, store.state.first { it.missed == 0 }.missed)
    }

    @Test
    fun настройка_памяти_читается_и_записывается() = runTest {
        val log = ЖурналВПамяти()
        log.rows.value = listOf(запись("давний", 1_000), запись("свежий", 99_999_999))
        val settings = НастройкиВПамяти()
        // «Сейчас» заведомо больше суток от начала эпохи: иначе предел по возрасту ушёл
        // бы в минус и не отобрал бы ничего, а проверка молча мерила бы только число.
        val store = магазин(
            backgroundScope, log, CallHistory { _, _ -> null }, settings, now = 100_000_000,
        )

        // Умолчания, пока в настройках пусто.
        assertEquals(CallsStore.KEEP_DAYS_DEFAULT, store.state.value.keepDays)
        assertEquals(CallsStore.KEEP_ROWS_DEFAULT, store.state.value.keepRows)

        // Сутки и одна строка: уборка обязана сработать СРАЗУ, а не при следующем
        // обновлении, — иначе человек не увидит освободившегося места.
        store.keep(days = 1, rows = 1)
        store.state.first { it.keepDays == 1 && it.keepRows == 1 }

        assertEquals(1, log.rows.value.size, "уборка по новой настройке не сработала")
        assertEquals("свежий", log.rows.value.single().callId)
    }

    @Test
    fun долистали_до_низа_и_просим_продолжение_от_самой_старой() = runTest {
        val log = ЖурналВПамяти()
        log.rows.value = listOf(запись("новее", 5_000), запись("старее", 2_000))
        var спрошено = -1L
        val store = магазин(
            backgroundScope,
            log,
            CallHistory { before, _ ->
                спрошено = before
                CallHistoryPage(listOf(запись("ещё старее", 1_000)))
            },
        )
        store.state.first { it.records.size == 2 }

        store.more()
        store.state.first { it.records.size == 3 }

        assertEquals(2_000, спрошено, "продолжение просим не от самой старой строки")
    }
}

/** Журнал сервера одним выражением: тестам нужен только ответ на страницу. */
private fun CallHistory(page: suspend (Long, Int) -> CallHistoryPage?): CallHistory =
    object : CallHistory {
        override suspend fun page(beforeMs: Long, limit: Int): CallHistoryPage? = page(beforeMs, limit)
    }
