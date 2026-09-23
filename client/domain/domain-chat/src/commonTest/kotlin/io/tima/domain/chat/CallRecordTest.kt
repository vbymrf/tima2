package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Одна запись — два разных рассказа.
 *
 * Это главное в журнале и есть: строка в базе одна на обоих участников, а читается
 * по-разному. Ошибка здесь не видна ни в сборке, ни в вёрстке — она видна человеку,
 * которому сказали «пропущенный» про звонок, который он сам и делал.
 */
class CallRecordTest {

    private val я = "me"
    private val он = "peer"

    private fun запись(
        state: String,
        initiator: String = я,
        endedBy: String = "",
        answeredAt: Long = 0,
        endedAt: Long = 0,
    ) = CallRecord(
        callId = "c1",
        video = true,
        state = state,
        initiatorId = initiator,
        peerId = if (initiator == я) он else я,
        endedBy = endedBy,
        createdAt = 1_000,
        answeredAt = answeredAt,
        endedAt = endedAt,
    )

    @Test
    fun не_дозвонился_и_пропущенный_это_одна_строка() {
        val звонил_я = запись(CallStates.MISSED, initiator = я)
        assertEquals(CallOutcome.NotAnswered, звонил_я.outcome(я))
        // Та же запись у второго. Скажи ему «не дозвонился» — и он пойдёт перезванивать
        // человеку, который звонил ему.
        assertEquals(CallOutcome.Missed, звонил_я.outcome(он))
    }

    @Test
    fun занято_у_звонившего_и_пропущенный_у_того_кто_говорил() {
        val запись = запись(CallStates.BUSY, initiator = я)
        assertEquals(CallOutcome.Busy, запись.outcome(я))
        // Ему в это время звонили, пока он говорил, — то есть вызов он пропустил.
        assertEquals(CallOutcome.Missed, запись.outcome(он))
    }

    @Test
    fun отменил_и_отклонил_различаются_только_по_ended_by() {
        val отменил_звонивший = запись(CallStates.ENDED, initiator = я, endedBy = я)
        val отклонил_вызываемый = запись(CallStates.ENDED, initiator = я, endedBy = он)
        // Слово одно на обоих: кто нажал первым — факт, а не догадка от лица смотрящего.
        assertEquals(CallOutcome.Cancelled, отменил_звонивший.outcome(я))
        assertEquals(CallOutcome.Cancelled, отменил_звонивший.outcome(он))
        assertEquals(CallOutcome.Declined, отклонил_вызываемый.outcome(я))
        assertEquals(CallOutcome.Declined, отклонил_вызываемый.outcome(он))
    }

    @Test
    fun без_ended_by_отменил_от_отклонил_не_отличить_и_мы_не_выдумываем() {
        // Строки старше поля `ended_by`, и закрытые SFU. Сказать, кто нажал, нечем —
        // и тогда честнее «оборвался», чем угаданное «отклонён».
        val старая = запись(CallStates.ENDED, initiator = я, endedBy = "")
        assertEquals(CallOutcome.Lost, старая.outcome(я))
    }

    @Test
    fun у_оборванного_длительности_нет_даже_когда_времена_есть() {
        // `ended_at` у брошенного звонка поставил уборщик сервера в момент уборки, а
        // ходит он раз в час. Разговор на минуту дал бы здесь час с лишним.
        val час = 60L * 60 * 1000
        val оборвался = запись(
            CallStates.LOST,
            answeredAt = 1_000,
            endedAt = 1_000 + час,
        )
        assertEquals(0, оборвался.durationMs)

        // А у обычного законченного длительность считается как есть.
        val поговорили = запись(
            CallStates.ENDED,
            endedBy = я,
            answeredAt = 1_000,
            endedAt = 1_000 + 185_000,
        )
        assertEquals(185_000, поговорили.durationMs)
    }

    @Test
    fun собеседник_это_всегда_тот_другой() {
        assertEquals(он, запись(CallStates.ANSWERED, initiator = я).other(я))
        assertEquals(он, запись(CallStates.ANSWERED, initiator = он).other(я))
    }

    @Test
    fun незнакомое_состояние_сервера_не_роняет_строку() {
        // Сервер вправе завести новое состояние раньше, чем клиент о нём узнает. Строка
        // при этом обязана показаться — пусть и без точного слова.
        assertEquals(CallOutcome.Lost, запись("что-то новое").outcome(я))
    }
}
