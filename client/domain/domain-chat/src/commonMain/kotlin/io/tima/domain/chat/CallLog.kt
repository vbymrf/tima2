package io.tima.domain.chat

import kotlinx.coroutines.flow.Flow

/**
 * Журнал звонков — ПЛАН-ЖУРНАЛА-ЗВОНКОВ.md, Ж2.
 *
 * **Источник правды — сервер, местная база — копия.** Этим журнал отличается от
 * переписки, хотя экран читает и то и другое одинаково: историю сообщений сервер
 * прочитать не может вовсе, а строку звонка заводит он сам. Потерянная переписка —
 * потеря навсегда, потерянный журнал — один запрос.
 */
interface CallLog {

    /** Страница журнала, новые сверху. Поток: экран показывает что есть, не ожидая сети. */
    fun page(limit: Int): Flow<List<CallRecord>>

    /** Сколько пропущенных **мне** ещё не просмотрено — счётчик на вкладке. */
    fun missed(me: String): Flow<Int>

    /** Сколько строк лежит на устройстве — строка «Занимает» в «Памяти и трафике». */
    fun count(): Flow<Int>

    /** Записать то, что пришло с сервера. Местная отметка «просмотрено» не трогается. */
    suspend fun remember(records: List<CallRecord>)

    /** Открыли вкладку — всё на ней просмотрено. */
    suspend fun markSeen()

    /**
     * Убрать лишнее по настройке «Память и трафик»: срок и число, что наступит раньше.
     *
     * Убирается **копия**, а не журнал: сервер не удаляет строки звонков вовсе.
     */
    suspend fun prune(olderThanMs: Long, keepRows: Int)
}

/**
 * Строка журнала, как её отдаёт сервер, плюс одна местная отметка.
 *
 * **Сырая, без посчитанных «направления» и «исхода».** Их считает [outcome], и считает
 * от лица спрашивающего: одна и та же запись у двоих читается по-разному.
 */
data class CallRecord(
    val callId: String,
    /** Та самая кнопка, которой звонили: повтор из журнала звонит тем же видом. */
    val video: Boolean,
    /** `ringing` · `answered` · `ended` · `missed` · `busy` · `lost`. */
    val state: String,
    val initiatorId: String,
    val peerId: String,
    /** Кто положил трубку. Пусто — не клал никто: закрыл уборщик или SFU. */
    val endedBy: String = "",
    val createdAt: Long,
    /** 0 — трубку не брали. */
    val answeredAt: Long = 0,
    /** 0 — звонок ещё числится идущим. */
    val endedAt: Long = 0,
    val seen: Boolean = false,
) {
    /** Собеседник — всегда «тот, другой», кем бы я ни был в этой записи. */
    fun other(me: String): String = if (initiatorId == me) peerId else initiatorId

    /** Звонил я. */
    fun outgoing(me: String): Boolean = initiatorId == me

    /**
     * Сколько длился разговор, или `0` — если длительности нет или она была бы ложью.
     *
     * **`lost` отсекается намеренно, и это не осторожность.** У брошенного звонка
     * `ended_at` поставил уборщик в момент уборки, а ходит он раз в час: разговор на
     * минуту дал бы здесь час с лишним. Состояние `lost` для того и заведено, чтобы это
     * число не считалось.
     */
    val durationMs: Long
        get() = if (state == CallStates.LOST || answeredAt == 0L || endedAt == 0L) 0
        else (endedAt - answeredAt).coerceAtLeast(0)

    /**
     * Чем звонок кончился **для того, кто смотрит**.
     *
     * Главное решение экрана, и оно не в вёрстке: запись в базе одна на обоих, а читается
     * по-разному. Без этого журнал говорил бы обоим «пропущенный» — одному неправду.
     */
    fun outcome(me: String): CallOutcome = when (state) {
        CallStates.RINGING -> CallOutcome.Ringing
        CallStates.LOST -> CallOutcome.Lost
        CallStates.MISSED ->
            // У звонившего это «не дозвонился», у вызываемого «пропущенный». Разные
            // слова и разные поступки: первый перезвонит, второй перезвонит извинившись.
            if (outgoing(me)) CallOutcome.NotAnswered else CallOutcome.Missed
        CallStates.BUSY ->
            // Звонившему сказали «занят». Вызываемому в это время звонили, пока он
            // говорил, — то есть он этот вызов пропустил, и знать о нём должен.
            //
            // План писал здесь «ничего: у него в это время шёл разговор». Строку всё же
            // показываем: пропущенный вызов, которого нет в журнале, — потерянное
            // сведение, а не избавление от шума.
            if (outgoing(me)) CallOutcome.Busy else CallOutcome.Missed
        // Разговор состоялся — трубку брали.
        CallStates.ANSWERED -> if (outgoing(me)) CallOutcome.Outgoing else CallOutcome.Incoming
        CallStates.ENDED ->
            when {
                answeredAt != 0L -> if (outgoing(me)) CallOutcome.Outgoing else CallOutcome.Incoming
                // Трубку не брали, а звонок закрыт: кто-то нажал отбой. Кто именно —
                // ровно то, ради чего на сервере завели `ended_by`.
                endedBy.isNotEmpty() && endedBy == initiatorId -> CallOutcome.Cancelled
                endedBy.isNotEmpty() -> CallOutcome.Declined
                // Никто не нажимал — закрыл SFU. Сказать, отменили или отклонили, нечем.
                else -> CallOutcome.Lost
            }
        else -> CallOutcome.Lost
    }
}

/** Состояния звонка на проводе. Строки сервера, собранные в одно место. */
object CallStates {
    const val RINGING = "ringing"
    const val ANSWERED = "answered"
    const val ENDED = "ended"
    const val MISSED = "missed"
    const val BUSY = "busy"

    /**
     * Звонок оборвался, конец неизвестен: его закрыл уборщик сервера.
     *
     * Заведено 2026-09-23, потому что `ended` здесь лгал: время конца ставится в момент
     * уборки, а уборщик ходит раз в час.
     */
    const val LOST = "lost"
}

/** Как строка читается человеком, который на неё смотрит. */
enum class CallOutcome {
    /** Звонит прямо сейчас — строка свежая, сервер ещё не закрыл её. */
    Ringing,

    /** Состоялся: я звонил. */
    Outgoing,

    /** Состоялся: звонили мне. */
    Incoming,

    /** Я звонил — не взяли трубку. */
    NotAnswered,

    /** Звонили мне — я не ответил. */
    Missed,

    /**
     * Отменён звонившим: он положил трубку раньше, чем взяли.
     *
     * Слово одно на обоих, а не два разных, — и это выигрыш от `ended_by`. Пока его не
     * было, «отменил» и «отклонил» приходилось выводить из того, кто смотрит, и выходила
     * догадка. Кто нажал первым — теперь факт, а направление говорит стрелка.
     */
    Cancelled,

    /** Отклонён вызываемым: отбой нажал он, не дождавшись собственного ответа. */
    Declined,

    /** Я звонил — собеседник говорил с кем-то другим. */
    Busy,

    /** Оборвался: конец неизвестен, длительности нет. */
    Lost,
}
