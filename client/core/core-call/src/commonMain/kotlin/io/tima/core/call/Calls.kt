package io.tima.core.call

/**
 * Сигналинг звонка — порт (ПЛАН-СТЕНДА-ЗВОНКОВ С1).
 *
 * **Отдельно от [CallEngine], и это главное решение модуля.** Кто кому звонит, какая
 * комната и какой токен — решает наш сервер, и решает одинаково на всех платформах. Медиа
 * же платформенное целиком. Сложи их в один интерфейс — и наш протокол придётся повторять
 * в каждом `actual`, то есть однажды повторить неодинаково.
 *
 * Ручки на сервере есть все (`server/internal/api/calls_registrar.go`), реализация —
 * `core-network`.
 */
interface Calls {

    /** Позвонить. `kind` — `audio` или `video`; сервер запомнит его в истории. */
    suspend fun start(peerId: String, video: Boolean): CallStep

    /** Ответить на входящий: сервер выдаёт токен той же комнаты. */
    suspend fun answer(callId: String): CallStep

    /**
     * Завершить. Итог сервер запишет сам по вебхуку от SFU; этот вызов — про наше
     * намерение, а не про факт разрыва.
     */
    suspend fun end(callId: String): Boolean
}

/** Чем кончилась попытка начать или принять звонок. */
sealed interface CallStep {
    /** Дверь открыта: адрес, комната, токен. */
    data class Door(val door: CallDoor) : CallStep

    /**
     * Звонки не настроены на сервере — `503 no_livekit`.
     *
     * Отдельным случаем, а не общим отказом: это не «не получилось», это «у нас звонков
     * нет вовсе», и человеку надо сказать именно так.
     */
    data object NotConfigured : CallStep

    /** Сети нет. `retryAfterMs` — через сколько имеет смысл повторить. */
    data class Offline(val retryAfterMs: Long) : CallStep

    /** Сервер отказал: код причины как есть, без выдуманных слов. */
    data class Refused(val code: String) : CallStep
}
