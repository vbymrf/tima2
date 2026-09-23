package io.tima.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Разбор кадров живого канала — К4.5. **Без сокета**, намеренно.
 *
 * Здесь живут решения, в которых легко ошибиться; в обвязке сокета их нет вовсе.
 * Проверить их можно только отдельно от сети: `MockEngine` в Ktor 3 вебсокеты не
 * изображает, а поднимать настоящий сервер ради проверки правила «подтверждать после
 * записи» — значит проверять сервер.
 *
 * Контракт снят с `internal/api/ws.go` (каталог API описывает не всё — сверка Д3):
 *
 * ```
 * → {"token":"…"}                              первый кадр, иначе разрыв
 * ← {"event":"ok","device_id":"…"}
 * → {"event":"sync.pull","cursor":N|null,"limit":N}
 * ← {"event":"message.new","event_id":N,"chat_id":"…","message_id":N,"envelope":"…"}
 * ← {"event":"sync.done","count":N,"next_cursor":N,"more":bool}
 * ← {"event":"sync.gap","next_cursor":N,"pts":N,"qts":N,"seq":N}
 * → {"event":"ack","event_id":N}
 * ← {"event":"sync.poke","event_id":N,"pts":N,"qts":N,"seq":N}
 * ← {"event":"call.poke","call_id":"…"}
 * ```
 *
 * **По шине едет подсказка, а не тело** (П4). `sync.poke` означает «приходи и забери»:
 * номер больше нашего курсора — значит есть что тянуть, и клиент сам зовёт `sync.pull`.
 * Вершины трёх полос при этом говорят, **что именно** потерялось и где: «в `qts`
 * пропущено одно» — это сразу «жди нечитаемых сообщений», а не «что-то потерялось».
 *
 * `call.poke` — «посмотри этот звонок ручкой». Тела у него нет и быть не может: вход в
 * комнату адресный, а состояние протухает за минуты.
 *
 * **Три правила, каждое закрывает свой способ потерять сообщение.**
 *
 * 1. **Подтверждать после записи, никогда до.** `ack` двигает серверный курсор:
 *    подтвердил и умер — сообщение не придёт больше никогда. Поэтому [ackFrame]
 *    отправляет тот, кто писал, и только после записи: разбор кадров сам его никогда
 *    не предлагает — среди решений подтверждения нет вовсе.
 *
 * 2. **`sync.gap` — не «продолжаем с этого места».** Он означает, что события до
 *    `next_cursor` сервер уже удалил по сроку хранения, и живой канал их не принесёт.
 *    Молча продолжить — значит навсегда потерять переписку за этот промежуток.
 *    Единственный верный ход — догон историей через REST, и он приходит наружу
 *    отдельным решением.
 *
 * 3. **Незнакомый кадр двигает курсор.** Соблазн — не подтверждать непонятное, «чтобы
 *    не потерять». Но тогда курсор не двигается никогда, и та же партия приходит
 *    вечно: канал встаёт целиком из-за одного кадра, которого мы всё равно не умеем
 *    прочитать.
 */
class EventStreamProtocol {

    /** Что делать по разобранному кадру. */
    sealed interface Decision {
        /** Кадр требует записи: сообщение отдаётся вызывающему. */
        data class Deliver(val event: IncomingEvent) : Decision

        /**
         * Догон закончен. `more = true` означает «есть ещё», и вызывающий обязан
         * попросить следующую страницу — иначе остаток истории не приедет.
         */
        data class SyncDone(val count: Int, val nextCursor: Long, val more: Boolean) : Decision

        /**
         * Промежуток невосстановим по каналу: нужен догон историей через REST.
         *
         * Полосы тоже обязаны сброситься на присланные: их номера остались от журнала,
         * которого больше нет. Не сбросить — и клиент навсегда считает, что у него
         * разрыв, то есть зовёт `sync.pull` на каждую подсказку.
         */
        data class NeedHistory(val fromCursor: Long, val lanes: LaneTops = LaneTops()) : Decision

        /**
         * Соединение установлено: сервер подтвердил токен.
         *
         * Вершины полос приходят **этим же кадром**, а не отдельным. Клиент обязан
         * узнать, где сейчас каждая полоса, до первой подсказки: устройство, молчавшее
         * неделю, иначе сверяло бы свой давний номер с новым и объявляло разрыв там,
         * где его нет.
         */
        data class Ready(val deviceId: String, val lanes: LaneTops = LaneTops()) : Decision

        /**
         * «Приходи и забери» — сервер записал событие и говорит об этом, не пересылая
         * тела (П4, П5).
         *
         * [eventId] — **спусковой крючок**: больше нашего курсора, значит есть что
         * тянуть. Он один обеспечивает правильность: потеряйся подсказка, следующая
         * всё равно будет с бóльшим номером.
         *
         * [lanes] — **диагноз**: по ним видно, что именно потерялось и в какой полосе.
         */
        data class Poke(val eventId: Long, val lanes: LaneTops) : Decision

        /**
         * «Посмотри этот звонок» — состояние берётся ручкой `GET /calls/{id}`.
         *
         * Подтверждать нечего: подсказка не кадр журнала, у неё нет `event_id`. Тем она
         * и хороша — **протухнуть не может**. Вызов недельной давности, доехавший до
         * телефона, приводит не к звонку, а к запросу, который честно отвечает
         * «кончился».
         */
        data class CallPoke(val callId: String) : Decision

        /** Сервер сообщил о своей беде. Не наша: повторить позже. */
        data class ServerTrouble(val code: String) : Decision

        /**
         * Сервер отказался работать с этой сборкой: она ниже порога совместимости.
         *
         * **Не ошибка связи и не наша беда — конец разговора.** Повторять
         * подключение бессмысленно: ответ будет тот же, а телефон тем временем
         * выглядит работающим.
         */
        data class AppOutdated(val minClient: Int, val versionName: String) : Decision

        /**
         * Ключи группы изменились или приехали: `key.rotated`, `recovery.gk_ready`.
         *
         * Оба события означают для нас одно — сходить за обёртками. Различать их
         * незачем: работа одна, а два пути к ней разошлись бы при первой же правке.
         */
        data class KeysArrived(val groupId: String, val eventId: Long?) : Decision

        /**
         * Кто-то прокомментировал нашу запись (ADR-0024, следствие 5).
         *
         * Отдельный вид, а не «сообщение»: у комментария нет конверта, он не ложится в
         * переписку и не требует ключа. Всё, что с ним делает клиент, — показывает автору,
         * что под его записью ответили.
         *
         * О переносе к себе такого кадра нет и не будет (ADR-0019 §7): там автору нечего
         * делать, а на комментарий отвечают.
         */
        data class CommentArrived(
            val channelId: String,
            val postId: Long,
            val commentId: Long,
            val authorId: String,
            val eventId: Long?,
        ) : Decision

        /**
         * Участник просит недостающие версии ключа (`recovery.gk_request`).
         *
         * Просьба адресована нам, потому что сервер знает: эти версии у нас есть.
         * Отвечать или нет — не вопрос вежливости: просящий имеет право на историю
         * группы, и молчание оставит его ждать вечно.
         */
        data class ShareKeys(
            val groupId: String,
            val requesterDevice: String,
            val requesterEncryptionPub: ByteArray,
            val versions: List<Int>,
            val eventId: Long?,
        ) : Decision

        /**
         * Сервер просит ротировать ключ (`group.rotation_needed`): сменилась эпоха
         * escrow либо отозвано устройство участника.
         *
         * Сервер сделать этого не может — ключа он не видит (ADR-0017 §3).
         */
        data class RotationNeeded(val groupId: String, val reason: String, val eventId: Long?) : Decision

        /**
         * Круг сообщения сузили (ADR-0019 §6): админ спрятал реплику от части группы.
         *
         * Кадр не несёт ни тела, ни подписи — менять по нему можно ровно одно поле, и
         * только в сторону сужения. Слово об этом идёт человеку строкой в саму группу:
         * пропавшая из чужих лент реплика без объяснения выглядит как поломка.
         */
        data class LevelNarrowed(
            val groupId: String,
            val messageId: Long,
            val level: Int,
            /** Кто сузил. Пусто — сервер не назвал, и врать про «админа» мы не станем. */
            val by: String,
            val eventId: Long?,
        ) : Decision

        /**
         * Нам звонят (`call.incoming`).
         *
         * **Звонок — не сообщение, и курсор он не двигает.** Событие живое: через минуту
         * оно бессмысленно, и складывать его в историю незачем. Поэтому [eventId] здесь
         * есть только ради подтверждения кадра, а записывать нечего.
         *
         * @param kind `audio` или `video` — от этого зависит, что показать на экране.
         */
        data class CallIncoming(
            val callId: String,
            val room: String,
            val kind: String,
            val from: String,
            val eventId: Long?,
        ) : Decision

        /**
         * Со звонком что-то стало (`call.state`): приняли, отклонили, положили трубку.
         *
         * Состояние приходит словом сервера, а не перечнем: сервер знает его случаи
         * лучше, и заводить свой перечень значило бы обещать, что мы их все перечислили.
         */
        data class CallState(val callId: String, val state: String, val eventId: Long?) : Decision

        /**
         * Участник вышел из комнаты — по вебхуку LiveKit.
         *
         * **Второе слово о конце, и оно приходит раньше первого.** `call.state ended`
         * сервер шлёт, когда звонок завершил человек нажатием; `call.participant_left` —
         * когда сторона просто исчезла: приложение убили, телефон уснул, сеть пропала
         * насовсем. Нажатия в этих случаях не было и не будет.
         *
         * До 2026-09-20 кадр не разбирался вовсе и уходил в «незнакомый». Это было второе
         * место, где сервер говорит, а клиент не слушает; первое такое стоило дня разбора
         * (`missed` в `Assembly`).
         */
        data class CallLeft(val callId: String, val userId: String, val eventId: Long?) : Decision

        /**
         * Вызов не забрало ни одно устройство собеседника (`call.unreachable`).
         *
         * **Это не конец звонка и не состояние человека.** Сервер говорит ровно то, что
         * видит: через пять секунд ни одно устройство не подтвердило кадр вызова, то
         * есть ни одно сейчас не на связи. Вернётся — заберёт вызов из журнала само, и
         * телефон зазвонит, если сорок пять секунд ещё не вышли.
         *
         * Поэтому кадр отдельный, а не слово в `call.state`: тот по правилу «от
         * обратного» кончил бы звонок (ADR-0025, решение 2), и вернувшееся устройство
         * звонило бы в пустоту.
         */
        data class CallUnreachable(val callId: String, val eventId: Long?) : Decision

        /** Кадр не наш или испорчен — пропускаем, но курсор двигаем (правило 3). */
        data class Skip(val reason: String, val eventId: Long?) : Decision

        /**
         * Кадр уже проходил: его `event_id` не больше подтверждённого.
         *
         * **Доставка «хотя бы один раз» означает, что повторы будут** — не как сбой, а
         * как устройство работы. Сервер дошлёт кадр, который шина потеряла; потерялось
         * при этом не событие, а наше подтверждение — и тогда придёт то, что мы уже
         * записали. Различить эти два случая на кадре нельзя, и не нужно: отбросить
         * повтор дешевле, чем каждому получателю быть идемпотентным по-своему.
         *
         * Подтверждать его всё равно надо: повтор мог прийти именно потому, что
         * прошлый `ack` не доехал, и промолчать — значит получать его вечно.
         */
        data class Seen(val eventId: Long) : Decision
    }

    /** Событие, которое надо записать. */
    data class IncomingEvent(
        val eventId: Long,
        val chatId: String,
        /** Идентификатор, назначенный **отправителем**: по нему опознаётся повтор. */
        val messageId: Long,
        val envelope: ByteArray,
        /**
         * Штамп отправителя из обёртки события (сервер 0052/0053): счётчик смен его
         * профиля и его цвет полосы в этой группе. Не часть подписи, в равенство не входит:
         * это подсказка получателю, а не содержимое сообщения. `null` — сервер старше.
         */
        val senderProfileRev: Int? = null,
        val senderHue: Int? = null,
    ) {
        override fun equals(other: Any?): Boolean = other is IncomingEvent &&
            eventId == other.eventId && chatId == other.chatId &&
            messageId == other.messageId && envelope.contentEquals(other.envelope)

        override fun hashCode(): Int {
            var h = eventId.hashCode()
            h = 31 * h + chatId.hashCode()
            h = 31 * h + messageId.hashCode()
            h = 31 * h + envelope.contentHashCode()
            return h
        }
    }

    /** Первый кадр: токен устройства. */
    /**
     * Первый кадр соединения.
     *
     * ── ЗАЧЕМ СЮДА ПОЛОЖЕНА ВЕРСИЯ ──────────────────────────────────────────
     *
     * Чтобы сервер мог сказать «это приложение устарело и работать не будет», а не
     * оставить телефон молча глухим.
     *
     * Такой телефон сегодня ничем не отличим от телефона в плохой сети: соединение
     * поднимается, `auth` проходит, события не приходят. Ровно это и случилось на
     * realme — двое суток глухоты при живом соединении, и ни строки о причине.
     *
     * Ручка `GET /app/version` про устаревание знает, но спрашивают её при запуске и
     * по кнопке; соединение живёт сутками. Сказать об этом обязан тот канал, по
     * которому беда и проявляется.
     *
     * **Поток обязателен вместе с номером.** Номера версий сравнимы только внутри
     * одного ряда сборок: у v1 сейчас 24, у v2 — 2, и порог чужого ряда выключил бы
     * приложение по числу из соседней вселенной.
     *
     * Сервер постарше лишние поля просто не заметит — API только расширяется.
     */
    fun authFrame(token: String, appCode: Int = 0, stream: String = ""): String {
        require(token.isNotBlank()) { "токен пустой" }
        if (appCode <= 0 || stream.isBlank()) return """{"token":"$token"}"""
        return """{"token":"$token","app":$appCode,"stream":"$stream"}"""
    }

    /**
     * Запрос догона.
     *
     * @param cursor `null` — взять серверную копию курсора. Так и надо на первом
     *   подключении: своя копия может быть старше, и тогда часть событий приедет
     *   дважды. Дубли безвредны (входящая машина идемпотентна), но платить трафиком
     *   незачем.
     */
    fun pullFrame(cursor: Long?, limit: Int = DEFAULT_LIMIT): String {
        require(limit in 1..MAX_LIMIT) { "предел страницы вне 1..$MAX_LIMIT: $limit" }
        val cursor = cursor?.toString() ?: "null"
        return """{"event":"sync.pull","cursor":$cursor,"limit":$limit}"""
    }

    /** Подтверждение. Вызывать **после** записи. */
    fun ackFrame(eventId: Long): String {
        require(eventId > 0) { "event_id обязан быть положительным: $eventId" }
        return """{"event":"ack","event_id":$eventId}"""
    }

    /**
     * Разбирает кадр сервера. Исключений не бросает: вход недоверенный.
     *
     * @param last последний подтверждённый `event_id`. Кадр не новее него — повтор, и
     *   наружу он не выходит ([Decision.Seen]). `null` — отбирать не по чему: так на
     *   первом кадре соединения, пока курсор не известен.
     *
     * Отбор стоит **здесь, а не у получателей**. Кадры разбирают шесть разных мест —
     * переписка, ключи, комментарии, звонки, — и требовать идемпотентности от каждого
     * значит однажды её где-то не потребовать. Звонок это уже показал: повтор
     * `call.incoming` мы удержали в `CallHost`, а что будет с повтором `key.rotated`,
     * не знал никто.
     */
    fun decide(frame: String, last: Long? = null): Decision {
        val json = runCatching { Json.parseToJsonElement(frame) as JsonObject }.getOrNull()
            ?: return Decision.Skip("кадр не разобран", null)

        val eventId = json["event_id"]?.jsonPrimitive?.longOrNull
        val event = json.string("event")
        // ── ПОДСКАЗКА ПОД ОТБОР НЕ ПОПАДАЕТ, И ЭТО НЕ МЕЛОЧЬ ────────────────
        //
        // `event_id` в подсказке — номер события **на сервере**, а не номер кадра,
        // который нам отдали. Пройди она общий отбор «уже видели», случилось бы худшее
        // из возможного: клиент отправил бы `ack` на событие, которого не получал, —
        // то есть сам сдвинул бы серверный курсор через непрочитанное.
        //
        // Поймано проверкой `подсказка_не_подтверждается_и_не_отбирается_по_курсору`
        // при первом же прогоне.
        if (event != "sync.poke" &&
            last != null && eventId != null && eventId <= last
        ) {
            return Decision.Seen(eventId)
        }
        return when (event) {
            "ok" -> Decision.Ready(json.string("device_id") ?: "", json.laneTops())

            "app.outdated" -> Decision.AppOutdated(
                minClient = json["min_client"]?.jsonPrimitive?.intOrNull ?: 0,
                versionName = json.string("version_name").orEmpty(),
            )

            // Подсказка не кадр журнала: `event_id` в ней — чужой номер, номер события
            // на сервере, а не нашего. Поэтому она НЕ проходит отбор по `last` выше и
            // не подтверждается — подтверждать будет то, что мы по ней заберём.
            "sync.poke" -> Decision.Poke(
                eventId = json["event_id"]?.jsonPrimitive?.longOrNull ?: 0,
                lanes = json.laneTops(),
            )

            "call.poke" -> json.string("call_id")
                ?.let { Decision.CallPoke(it) }
                ?: Decision.Skip("call.poke без call_id", null)

            "message.new" -> {
                val chatId = json.string("chat_id")
                val messageId = json["message_id"]?.jsonPrimitive?.longOrNull
                val envelope = json.string("envelope")?.let { decodeBase64Url(it) }
                if (eventId == null || chatId == null || messageId == null || envelope == null) {
                    // Кадр нашего типа, но неполный: записывать нечего, а курсор двигать
                    // надо — иначе он застрянет на испорченном событии навсегда.
                    Decision.Skip("message.new без обязательных полей", eventId)
                } else {
                    Decision.Deliver(
                        IncomingEvent(
                            eventId, chatId, messageId, envelope,
                            senderProfileRev = json["sender_profile_rev"]?.jsonPrimitive?.intOrNull,
                        ),
                    )
                }
            }

            "sync.done" -> Decision.SyncDone(
                count = json["count"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                nextCursor = json["next_cursor"]?.jsonPrimitive?.longOrNull ?: 0,
                more = json["more"]?.jsonPrimitive?.content == "true",
            )

            "sync.gap" -> Decision.NeedHistory(
                fromCursor = json["next_cursor"]?.jsonPrimitive?.longOrNull ?: 0,
                lanes = json.laneTops(),
            )

            // Сообщение группы кладётся тем же путём, что личное: хранилище принимает
            // непрозрачные байты, и различать их — работа разбора, а не канала. Кадр
            // сохраняется целиком: подпись группового сообщения считается по метаданным
            // вместе с payload, и без них его не открыть и не проверить.
            "message.group" -> {
                val frame = GroupFrame.fromJson(json)
                if (eventId == null || frame == null) {
                    Decision.Skip("message.group без обязательных полей", eventId)
                } else {
                    Decision.Deliver(
                        IncomingEvent(
                            eventId = eventId,
                            chatId = frame.groupId,
                            messageId = frame.messageId,
                            // Сохраняем ИСХОДНЫЙ json сервера, а не пересобранный из полей:
                            // подпись считается по тем значениям, что пришли, и наша
                            // пересборка могла бы их незаметно нормализовать.
                            envelope = GroupFrame.toStored(json.toString()),
                            senderProfileRev = json["sender_profile_rev"]?.jsonPrimitive?.intOrNull,
                            senderHue = json["sender_hue"]?.jsonPrimitive?.intOrNull,
                        ),
                    )
                }
            }

            // Ключ группы сменился или приехали недостающие обёртки — идём за ними.
            "key.rotated", "recovery.gk_ready" ->
                json.string("group_id")?.let { Decision.KeysArrived(it, eventId) }
                    ?: Decision.Skip("$event без group_id", eventId)

            "recovery.gk_request" -> {
                val groupId = json.string("group_id")
                val requester = json.string("requester_device")
                val encPub = json.string("requester_enc_pub")?.let { decodeBase64Url(it) }
                val versions = runCatching {
                    (json["versions"] as JsonArray).mapNotNull { it.jsonPrimitive.intOrNull }
                }.getOrNull()
                if (groupId == null || requester == null || encPub == null || versions.isNullOrEmpty()) {
                    Decision.Skip("recovery.gk_request без обязательных полей", eventId)
                } else {
                    Decision.ShareKeys(groupId, requester, encPub, versions, eventId)
                }
            }

            "group.rotation_needed" ->
                json.string("group_id")?.let {
                    Decision.RotationNeeded(it, json.string("reason") ?: "epoch", eventId)
                } ?: Decision.Skip("group.rotation_needed без group_id", eventId)

            "message.level_narrowed" -> {
                val groupId = json.string("group_id")
                val messageId = json["message_id"]?.jsonPrimitive?.longOrNull
                val level = json["level"]?.jsonPrimitive?.intOrNull
                if (groupId == null || messageId == null || level == null) {
                    Decision.Skip("message.level_narrowed без обязательных полей", eventId)
                } else {
                    Decision.LevelNarrowed(groupId, messageId, level, json.string("by") ?: "", eventId)
                }
            }

            "call.incoming" -> {
                val callId = json.string("call_id")
                val room = json.string("room")
                if (callId == null || room == null) {
                    Decision.Skip("call.incoming без call_id или room", eventId)
                } else {
                    Decision.CallIncoming(
                        callId = callId,
                        room = room,
                        // Вид по умолчанию — звук: он дешевле и безопаснее ошибки в другую
                        // сторону. Включить камеру человек успеет, выключить внезапно
                        // включившуюся — уже нет.
                        kind = json.string("kind") ?: "audio",
                        from = json.string("from") ?: "",
                        eventId = eventId,
                    )
                }
            }

            "call.participant_left" -> {
                val callId = json.string("call_id")
                val userId = json.string("user_id")
                if (callId == null || userId == null) {
                    Decision.Skip("call.participant_left без call_id или user_id", eventId)
                } else {
                    Decision.CallLeft(callId = callId, userId = userId, eventId = eventId)
                }
            }

            "call.state" -> {
                val callId = json.string("call_id")
                val state = json.string("state")
                if (callId == null || state == null) {
                    Decision.Skip("call.state без call_id или state", eventId)
                } else {
                    Decision.CallState(callId = callId, state = state, eventId = eventId)
                }
            }

            "call.unreachable" -> {
                val callId = json.string("call_id")
                if (callId == null) {
                    Decision.Skip("call.unreachable без call_id", eventId)
                } else {
                    Decision.CallUnreachable(callId = callId, eventId = eventId)
                }
            }

            "channel.comment" -> {
                val channelId = json.string("channel_id")
                val postId = json["post_id"]?.jsonPrimitive?.longOrNull
                if (channelId == null || postId == null) {
                    Decision.Skip("channel.comment без обязательных полей", eventId)
                } else {
                    Decision.CommentArrived(
                        channelId = channelId,
                        postId = postId,
                        commentId = json["comment_id"]?.jsonPrimitive?.longOrNull ?: 0,
                        authorId = json.string("author_id") ?: "",
                        eventId = eventId,
                    )
                }
            }

            "error" -> Decision.ServerTrouble(json.string("code") ?: "без кода")

            else -> Decision.Skip("незнакомый кадр «$event»", eventId)
        }
    }

    private fun JsonObject.string(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        /** Как у сервера: `0` он трактует как 100. Пишем явно, чтобы не гадать. */
        const val DEFAULT_LIMIT: Int = 100

        /** Предел сервера. Больше он всё равно урежет до 100 — молча. */
        const val MAX_LIMIT: Int = 500

        /**
         * Полоса и номер в ней из сырого кадра — `null`, если номера нет.
         *
         * Отдельной функцией, а не полем каждого решения. Решений полтора десятка, и
         * дописать полосу в каждое значило бы протащить её через все места, которым
         * она не нужна вовсе, — а забыть в одном месте значило бы тихо получить
         * «пропущено» на полосе, которая на самом деле цела.
         *
         * **Ноль означает «номера нет»**: кадр звонка, живое «печатает» либо запись,
         * сделанная до перехода на полосы. Такой кадр клиент просто применяет.
         */
        fun laneMark(frame: String): LaneMark? {
            val json = runCatching { Json.parseToJsonElement(frame) as JsonObject }.getOrNull() ?: return null
            val lane = json["lane"]?.jsonPrimitive?.intOrNull ?: return null
            val seq = json["lane_seq"]?.jsonPrimitive?.longOrNull ?: return null
            if (lane <= 0 || seq <= 0) return null
            return LaneMark(lane, seq)
        }
    }
}

/**
 * Вершины трёх полос: докуда доехал каждый счётчик у сервера.
 *
 * Критерий разделения — **не объём, а опасность разрыва**:
 *
 * | Полоса | Что в ней | Чем опасен пропуск |
 * |---|---|---|
 * | [pts] | переписка | потерянное сообщение |
 * | [qts] | ключи | **нечитаемая история**: это не «сообщение опоздало», а «не откроется никогда» |
 * | [seq] | фон | важен факт, не порядок: досада, а не беда |
 *
 * Полос сегодня хватило бы и одной. Но номер полосы живёт на устройстве, в его курсоре:
 * завести третий курсор на девятнадцати устройствах — правка, на тысяче — переход с
 * окном совместимости. Решение заказчика 2026-09-23: платить сейчас.
 */
data class LaneTops(val pts: Long = 0, val qts: Long = 0, val seq: Long = 0) {

    /** Номер полосы по её ключу, как их нумерует сервер (`store.Lane*`). */
    fun of(lane: Int): Long = when (lane) {
        LANE_PTS -> pts
        LANE_QTS -> qts
        LANE_SEQ -> seq
        else -> 0
    }

    /** Поднять номер одной полосы до применённого кадра. */
    fun with(lane: Int, seq: Long): LaneTops = when (lane) {
        LANE_PTS -> copy(pts = maxOf(pts, seq))
        LANE_QTS -> copy(qts = maxOf(qts, seq))
        LANE_SEQ -> copy(seq = maxOf(this.seq, seq))
        else -> this
    }

    companion object {
        const val LANE_PTS = 1
        const val LANE_QTS = 2
        const val LANE_SEQ = 3

        /** Имя полосы для журнала. Число в отчёте о проблеме ничего не объясняет. */
        fun name(lane: Int): String = when (lane) {
            LANE_PTS -> "переписка"
            LANE_QTS -> "ключи"
            LANE_SEQ -> "фон"
            else -> "вне полос"
        }
    }
}

/** Полоса кадра и его номер в ней. */
data class LaneMark(val lane: Int, val seq: Long)

/** Вершины полос из кадра: их шлёт `ok`, `sync.gap` и каждая подсказка. */
private fun JsonObject.laneTops(): LaneTops = LaneTops(
    pts = this["pts"]?.jsonPrimitive?.longOrNull ?: 0,
    qts = this["qts"]?.jsonPrimitive?.longOrNull ?: 0,
    seq = this["seq"]?.jsonPrimitive?.longOrNull ?: 0,
)
