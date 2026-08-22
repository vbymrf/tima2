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
        persist: suspend (EventStreamProtocol.IncomingEvent) -> Unit,
    ): StreamOutcome {
        var последний = cursor
        // Локальная переменная, а не поле: `webSocket` возвращает Unit, и вынести исход
        // иначе нельзя. Поле переживало бы вызов и отдало бы прошлый исход следующему.
        var решённое: StreamOutcome? = null
        return try {
            client.webSocket(route.wsUrl) {
                send(Frame.Text(protocol.authFrame(token())))
                send(Frame.Text(protocol.pullFrame(последний)))

                for (frame in incoming) {
                    val текст = (frame as? Frame.Text)?.readText() ?: continue
                    when (val решение = protocol.decide(текст)) {
                        is EventStreamProtocol.Decision.Deliver -> {
                            // Порядок обязателен: сначала запись, потом подтверждение.
                            persist(решение.event)
                            последний = решение.event.eventId
                            send(Frame.Text(protocol.ackFrame(решение.event.eventId)))
                        }

                        is EventStreamProtocol.Decision.Skip -> решение.eventId?.let {
                            последний = it
                            send(Frame.Text(protocol.ackFrame(it)))
                        }

                        is EventStreamProtocol.Decision.SyncDone -> if (решение.more) {
                            send(Frame.Text(protocol.pullFrame(решение.nextCursor)))
                        }

                        is EventStreamProtocol.Decision.NeedHistory -> {
                            решённое = StreamOutcome.NeedHistory(решение.fromCursor)
                            return@webSocket
                        }

                        is EventStreamProtocol.Decision.ServerTrouble -> {
                            решённое = StreamOutcome.ServerTrouble(решение.code)
                            return@webSocket
                        }

                        is EventStreamProtocol.Decision.Ready -> Unit
                    }
                }
            }
            решённое ?: StreamOutcome.Closed(последний)
        } catch (e: Throwable) {
            // Обрыв, TLS, разорванный сокет, отказ авторизации при рукопожатии.
            StreamOutcome.Disconnected(classifyFailure(e), последний)
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
