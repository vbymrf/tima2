package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText

/**
 * Живой канал: обвязка сокета вокруг [EventStreamProtocol].
 *
 * **Здесь намеренно нет ни одного решения.** Всё, в чём можно ошибиться — порядок
 * кадров, подтверждение после записи, промежуток в истории, — разобрано в протоколе и
 * проверено без сети. Тут только чтение, запись и перевод обрыва в исход: `MockEngine`
 * в Ktor 3 вебсокеты не изображает, а поднимать сервер ради проверки этих двадцати
 * строк значило бы проверять сервер.
 *
 * Ping/pong не наша забота: сервер пингует сам (`wsPingInterval`), клиент Ktor
 * отвечает автоматически. Своего пинга мы не добавляем — два пинга навстречу друг
 * другу только жгут батарею.
 */
class EventStream(
    private val route: ServerRoute,
    /** Клиент **с установленным** плагином `WebSockets`. */
    private val client: HttpClient,
    private val token: () -> String,
    private val protocol: EventStreamProtocol = EventStreamProtocol(),
) {

    /**
     * Держит канал, пока он держится.
     *
     * @param cursor курсор устройства; `null` — взять серверную копию.
     * @param persist запись события. **Обязана вернуться только после того, как
     *   событие записано**: подтверждение уходит сразу после неё, а подтверждённое
     *   сервер больше не пришлёт.
     * @return чем канал закончился. Решение «переподключаться или нет» принимает
     *   вызывающий: у него есть состояние связи и политика повторов.
     */
    suspend fun run(
        cursor: Long?,
        /**
         * Кадры про групповые ключи: ротация, приезд обёрток, просьба поделиться.
         *
         * Отдаются наружу, а не выполняются здесь: канал занимается доставкой, а
         * ротация ключа требует escrow, крипты, сети и хранилища разом — то есть
         * ровно того, чего в транспорте быть не должно. По умолчанию ничего: канал
         * обязан работать и там, где ключами никто не занимается (проверки).
         *
         * Стоит ПЕРЕД [persist] намеренно: хвостовая лямбда вызова обязана означать
         * запись сообщения, как и раньше. Поставь эту ручку последней — и каждый
         * существующий вызов молча сменил бы смысл.
         */
        onGroupKeys: suspend (EventStreamProtocol.Decision) -> Unit = {},
        /**
         * Сужение круга у сообщения. По умолчанию ничего: канал обязан работать и там,
         * где переписки нет вовсе (проверки транспорта).
         */
        onLevelNarrowed: suspend (EventStreamProtocol.Decision.LevelNarrowed) -> Unit = {},
        persist: suspend (EventStreamProtocol.IncomingEvent) -> Unit,
    ): StreamOutcome {
        var last = cursor
        // Локальная переменная, а не поле: `webSocket` возвращает Unit, и вынести исход
        // иначе нельзя. Поле переживало бы вызов и отдало бы прошлый исход следующему.
        var decided: StreamOutcome? = null
        return try {
            client.webSocket(route.wsUrl) {
                send(Frame.Text(protocol.authFrame(token())))
                send(Frame.Text(protocol.pullFrame(last)))

                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    when (val decision = protocol.decide(text)) {
                        is EventStreamProtocol.Decision.Deliver -> {
                            // Порядок обязателен: сначала запись, потом подтверждение.
                            persist(decision.event)
                            last = decision.event.eventId
                            send(Frame.Text(protocol.ackFrame(decision.event.eventId)))
                        }

                        is EventStreamProtocol.Decision.Skip -> decision.eventId?.let {
                            last = it
                            send(Frame.Text(protocol.ackFrame(it)))
                        }

                        is EventStreamProtocol.Decision.SyncDone -> if (decision.more) {
                            send(Frame.Text(protocol.pullFrame(decision.nextCursor)))
                        }

                        // Подтверждаем и отдаём наружу: событие обработано каналом в
                        // том смысле, что доставлено. Не подтвердить — значит получать
                        // его снова при каждом подключении.
                        is EventStreamProtocol.Decision.KeysArrived,
                        is EventStreamProtocol.Decision.ShareKeys,
                        is EventStreamProtocol.Decision.RotationNeeded,
                        -> {
                            onGroupKeys(decision)
                            val id = when (decision) {
                                is EventStreamProtocol.Decision.KeysArrived -> decision.eventId
                                is EventStreamProtocol.Decision.ShareKeys -> decision.eventId
                                is EventStreamProtocol.Decision.RotationNeeded -> decision.eventId
                                else -> null
                            }
                            id?.let {
                                last = it
                                send(Frame.Text(protocol.ackFrame(it)))
                            }
                        }

                        // Подтверждаем так же, как кадры про ключи: работа сделана в том
                        // смысле, что событие доставлено тому, кто им занимается.
                        is EventStreamProtocol.Decision.LevelNarrowed -> {
                            onLevelNarrowed(decision)
                            decision.eventId?.let {
                                last = it
                                send(Frame.Text(protocol.ackFrame(it)))
                            }
                        }

                        is EventStreamProtocol.Decision.NeedHistory -> {
                            decided = StreamOutcome.NeedHistory(decision.fromCursor)
                            return@webSocket
                        }

                        is EventStreamProtocol.Decision.ServerTrouble -> {
                            decided = StreamOutcome.ServerTrouble(decision.code)
                            return@webSocket
                        }

                        is EventStreamProtocol.Decision.Ready -> Unit
                    }
                }
            }
            decided ?: StreamOutcome.Closed(last)
        } catch (e: Throwable) {
            // Обрыв, TLS, разорванный сокет, отказ авторизации при рукопожатии.
            StreamOutcome.Disconnected(classifyFailure(e), last)
        }
    }
}

/** Чем закончился живой канал. */
sealed interface StreamOutcome {
    /**
     * Промежуток невосстановим по каналу: нужен догон историей через REST, и только
     * потом продолжать с [fromCursor].
     */
    data class NeedHistory(val fromCursor: Long) : StreamOutcome

    /** Сервер закрыл канал сам. Обычный путь: обновление сервера, перезапуск. */
    data class Closed(val lastCursor: Long?) : StreamOutcome

    /** Обрыв связи. Пауза берётся из состояния связи, снятого в живой сети v1. */
    data class Disconnected(val link: LinkState, val lastCursor: Long?) : StreamOutcome

    /** Беда на сервере: повторить позже. */
    data class ServerTrouble(val code: String) : StreamOutcome
}
