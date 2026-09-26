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
    /**
     * Своя версия и ряд сборок — чтобы сервер мог сказать «устарело» (О5).
     *
     * Умолчание «не называем себя» оставлено ради проверок и старых сборщиков: сервер
     * без этих полей ведёт себя как прежде, то есть пускает.
     */
    private val appCode: Int = 0,
    private val appStream: String = "",
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
        /**
         * Кто-то прокомментировал нашу запись (ADR-0024). По умолчанию ничего: канал
         * обязан работать и там, где страницы нет вовсе.
         */
        onComment: suspend (EventStreamProtocol.Decision.CommentArrived) -> Unit = {},
        /**
         * Нам звонят или со звонком что-то стало.
         *
         * По умолчанию ничего: канал обязан работать и там, где звонков нет вовсе — на
         * ПК, в харнессе, в проверках. Звонок — событие **живое**: через минуту оно
         * бессмысленно, и складывать его в историю незачем.
         */
        onCall: suspend (EventStreamProtocol.Decision) -> Unit = {},
        /**
         * В полосе пропущено больше одного: `(имя полосы, сколько)`.
         *
         * Не беда сама по себе — пропущенное сейчас же и заберут. Но **называется** оно
         * полосой, и в этом весь смысл: «в ключах пропущено три» — это сразу «жди
         * нечитаемых сообщений», а не «что-то потерялось». Разбор начинается с ответа.
         */
        onLaneGap: suspend (String, Long) -> Unit = { _, _ -> },
        /**
         * Вершина ленты звонков (ВЗ0а): из приветствия и из подсказки `call.poke {cts}`.
         *
         * Возвращает номер, до которого лента **применена**, — его канал и подтверждает;
         * `null` — подтверждать нечего (не дошли до сервера, ничего нового). Зовётся прямо
         * в цикле канала, а не отдельно: догрузки ленты идут строго по одной, и изменения
         * применяются по номеру (ПЛАН-ВХОДЯЩЕГО-ЗВОНКА §6.7, п. 6).
         */
        onCallsTop: suspend (Long) -> Long? = { null },
        persist: suspend (EventStreamProtocol.IncomingEvent) -> Unit,
    ): StreamOutcome {
        var last = cursor
        // Локальная переменная, а не поле: `webSocket` возвращает Unit, и вынести исход
        // иначе нельзя. Поле переживало бы вызов и отдало бы прошлый исход следующему.
        var decided: StreamOutcome? = null
        // Докуда мы применили каждую полосу. Сбрасывается на серверные вершины при
        // `ok` и `sync.gap` — там они авторитетны, а наши остались от журнала, которого
        // может уже не быть.
        var lanes = LaneTops()
        return try {
            client.webSocket(route.wsUrl) {
                send(Frame.Text(protocol.authFrame(token(), appCode, appStream)))
                send(Frame.Text(protocol.pullFrame(last)))

                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    // Номер полосы поднимается ПО ФАКТУ прихода кадра, а не по факту его
                    // применения. Кадр, который мы пропустили как непонятный, всё равно
                    // доехал: считать его потерянным значило бы звать `sync.pull` на
                    // каждую подсказку до конца жизни соединения.
                    EventStreamProtocol.laneMark(text)?.let { lanes = lanes.with(it.lane, it.seq) }
                    when (val decision = protocol.decide(text, last)) {
                        is EventStreamProtocol.Decision.Deliver -> {
                            // Порядок обязателен: сначала запись, потом подтверждение.
                            persist(decision.event)
                            last = decision.event.eventId
                            send(Frame.Text(protocol.ackFrame(decision.event.eventId)))
                        }

                        // Кадр уже проходил (сервер дослал потерянное шиной, либо
                        // не доехал наш ack). Наружу не отдаём — только подтверждаем
                        // заново, иначе он будет приходить бесконечно.
                        is EventStreamProtocol.Decision.Seen ->
                            send(Frame.Text(protocol.ackFrame(decision.eventId)))

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

                        // Комментарий подтверждается так же: доставлено — значит
                        // обработано. Не подтвердить — значит получать его снова при
                        // каждом подключении.
                        is EventStreamProtocol.Decision.CommentArrived -> {
                            onComment(decision)
                            decision.eventId?.let {
                                last = it
                                send(Frame.Text(protocol.ackFrame(it)))
                            }
                        }

                        // Звонок подтверждается так же, как всё живое: доставлено —
                        // значит обработано. Не подтвердить — значит получить входящий
                        // заново при каждом переподключении, то есть звонить человеку
                        // второй раз о том, чего уже нет.
                        is EventStreamProtocol.Decision.CallIncoming,
                        is EventStreamProtocol.Decision.CallState,
                        is EventStreamProtocol.Decision.CallLeft,
                        is EventStreamProtocol.Decision.CallUnreachable,
                        -> {
                            onCall(decision)
                            val id = when (decision) {
                                is EventStreamProtocol.Decision.CallIncoming -> decision.eventId
                                is EventStreamProtocol.Decision.CallState -> decision.eventId
                                is EventStreamProtocol.Decision.CallLeft -> decision.eventId
                                is EventStreamProtocol.Decision.CallUnreachable -> decision.eventId
                                else -> null
                            }
                            id?.let {
                                last = it
                                send(Frame.Text(protocol.ackFrame(it)))
                            }
                        }

                        // ── «ПРИХОДИ И ЗАБЕРИ» ──────────────────────────────
                        //
                        // Правильность держится на одном: номер подсказки больше нашего
                        // курсора — значит есть что тянуть. Потеряйся подсказка,
                        // следующая всё равно будет с бóльшим номером, а если их не
                        // будет вовсе — досылку сделает сверка на сервере.
                        //
                        // Вершины полос отвечают на другой вопрос: **что именно**
                        // потерялось. Разница больше единицы означает, что пропала сама
                        // подсказка, и полоса называет цену этой пропажи.
                        is EventStreamProtocol.Decision.Poke -> {
                            for (lane in LANES) {
                                val missed = decision.lanes.of(lane) - lanes.of(lane)
                                if (missed > 1) onLaneGap(LaneTops.name(lane), missed)
                            }
                            // `last == null` — курсор ещё не известен (первое
                            // соединение): тянуть надо в любом случае, и с нуля.
                            if (last == null || decision.eventId > last!!) {
                                send(Frame.Text(protocol.pullFrame(last)))
                            }
                        }

                        // Подсказка про звонок подтверждения не требует: это не кадр
                        // журнала. Состояние возьмёт ручкой тот, кто владеет звонком.
                        is EventStreamProtocol.Decision.CallPoke -> onCall(decision)

                        is EventStreamProtocol.Decision.NeedHistory -> {
                            decided = StreamOutcome.NeedHistory(decision.fromCursor)
                            return@webSocket
                        }

                        is EventStreamProtocol.Decision.ServerTrouble -> {
                            decided = StreamOutcome.ServerTrouble(decision.code)
                            return@webSocket
                        }

                        // Повторять нечего: ответ будет тот же. Наружу уходит не
                        // «обрыв», а названная причина — иначе телефон продолжил бы
                        // выглядеть работающим, а это и есть та самая беда.
                        is EventStreamProtocol.Decision.AppOutdated -> {
                            decided = StreamOutcome.AppOutdated(decision.minClient, decision.versionName)
                            return@webSocket
                        }

                        // Вершины полос приходят приветствием: до них сверять нечего,
                        // и устройство, молчавшее неделю, иначе объявило бы разрыв там,
                        // где его нет.
                        is EventStreamProtocol.Decision.Ready -> {
                            lanes = decision.lanes
                            decision.cts?.let { top ->
                                onCallsTop(top)?.let { send(Frame.Text(protocol.callAckFrame(it))) }
                            }
                        }

                        is EventStreamProtocol.Decision.CallsPoke ->
                            onCallsTop(decision.cts)?.let { send(Frame.Text(protocol.callAckFrame(it))) }
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
     * Сервер не работает с этой сборкой: она ниже объявленного порога.
     *
     * Отдельный исход, а не разрыв: разрыв повторяют, а это повторять бессмысленно.
     * Показать человека надо экран «нужно обновиться», а не крутить переподключение.
     */
    data class AppOutdated(val minClient: Int, val versionName: String) : StreamOutcome

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

/** Полосы, которые клиент сверяет. Вне полос сверять нечего — номера там нет. */
private val LANES = listOf(LaneTops.LANE_PTS, LaneTops.LANE_QTS, LaneTops.LANE_SEQ)
