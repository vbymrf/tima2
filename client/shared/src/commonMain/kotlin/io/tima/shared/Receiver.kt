package io.tima.shared

import io.tima.core.database.SqlChatBook
import io.tima.core.encryption.GroupMessages
import io.tima.core.network.EventStreamProtocol
import io.tima.core.network.GroupFrame
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.PersonalMessages
import io.tima.core.network.DeviceKeysResult
import io.tima.core.network.EventStream
import io.tima.core.outbox.IncomingEntry
import io.tima.core.outbox.OpenOutcome
import io.tima.domain.account.Session
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.ChatKind
import kotlinx.coroutines.delay

/**
 * Приём по живому каналу.
 *
 * ── ПОРЯДОК, КОТОРЫЙ ВАЖНЕЕ КОДА ────────────────────────────────────────────
 *
 * 1. **Конверт записывается ДО попытки разбора.** Разбор падает по любой причине — нет
 *    ключа, повреждённые байты, ошибка в нашем коде, — и если сначала разбирать, а
 *    записывать потом, каждое такое падение теряет сообщение безвозвратно: живой канал его
 *    больше не пришлёт. Этим занимается [io.tima.core.outbox.Inbox], здесь только вызовы в
 *    правильном порядке.
 * 2. **Имя отправителя из конверта — подсказка, а не утверждение.** Оно лежит в открытой
 *    части конверта, и до проверки подписи ему верить нельзя. Пользуемся им ровно для
 *    одного: какой ключ спрашивать у сервера. Соври отправитель чужим именем — подпись не
 *    сойдётся, и сообщение станет нечитаемым, а не «чужим».
 * 3. **Переписка от незнакомого номера всё равно появляется.** Строка `chats` заводится по
 *    отправителю: без неё в списке была бы переписка без имени — а человеку надо видеть,
 *    кто написал.
 *
 * **Переподключение решает вызывающий, а не канал.** [EventStream] возвращает исход и
 * заканчивается; политика повторов — здесь, потому что здесь известно, сколько ждать.
 */
class Receiver(
    private val environment: Environment,
    private val network: Network,
    private val session: Session,
    private val identity: DeviceIdentity,
    private val keyOrchestrator: GroupKeyOrchestrator,
) {

    /** Что случилось с каналом в последний раз. Для диагностики, не для решений. */
    var lastOutcome: String? = null
        private set

    /** Ключи подписи по устройству отправителя: спрашиваются один раз на устройство. */
    private val senderKeys = HashMap<String, ByteArray>()

    private val book = SqlChatBook(environment.db, environment.cipher)

    // ── Групповые ключи ──────────────────────────────────────────────────────
    //
    // Собираются НЕ здесь: канал только приносит кадры, а выполняет их работа, которой
    // нужны escrow, крипта, сеть и хранилище разом. Приёмник получает готовый оркестр.
    private val groupKeys get() = keyOrchestrator.keys

    /**
     * Держать канал, пока приложение живо.
     *
     * Бесконечный цикл здесь на месте: канал — это и есть «пока живо». Пауза между
     * попытками берётся из состояния связи, а не из общего «подождём пять секунд».
     */
    suspend fun hold() {
        while (true) {
            val outcome = runCatching {
                network.eventChannel()
                    .run(
                        cursor = null,
                        onGroupKeys = { decision -> aboutKeys(decision) },
                        onLevelNarrowed = { decision -> aboutLevel(decision) },
                    ) { event -> accept(event.chatId, event.messageId, event.envelope) }
            }
            lastOutcome = outcome.fold(
                onSuccess = { it.toString() },
                onFailure = { "канал упал: ${it::class.simpleName}: ${it.message}" },
            )
            delay(ПАУЗА_ПЕРЕД_ПОВТОРОМ_МС)
        }
    }

    /**
     * Сообщение группы: записать кадр, потом попытаться открыть.
     *
     * **Нет ключа этой версии — не поломка.** Сообщение отправлено до нашего прихода в
     * группу либо ключ ещё не доехал; строка остаётся нечитаемой, и человек видит на
     * экране «сообщение недоступно» с предложением запросить ключ. Именно поэтому здесь
     * `NoKey`, а не `Rejected`: первое означает «попробуем ещё», второе — «никогда».
     */
    private suspend fun acceptGroup(groupId: String, messageId: Long, frame: ByteArray) {
        val parsed = GroupFrame.parse(frame)
        environment.incoming.receive(groupId, messageId, frame)

        // Ключ подписи спрашивается до разбора: сам разбор синхронный, и ходить за ним
        // изнутри нельзя. Промах кэша означает лишь, что сообщение откроется следующей
        // попыткой — оно уже записано и не потеряется.
        if (parsed != null) captionKey(parsed.senderId, parsed.senderDevice)

        environment.incoming.openNext { entry ->
            if (!GroupFrame.isGroupFrame(entry.envelope)) {
                // Очередь отдала не групповую запись: её откроет свой путь. Причина
                // называется словами, чтобы это не выглядело потерей ключа.
                OpenOutcome.NoKey("запись не групповая — ждёт своего разбора")
            } else {
                openGroup(entry)
            }
        }

        // Группа в списке переписок: без строки человек не увидит, куда пришло сообщение.
        if (!environment.chatFacts.knows(groupId)) {
            book.remember(chatId = groupId, kind = ChatKind.Group, title = "Группа", peerId = null)
        }
    }

    private fun openGroup(entry: IncomingEntry): OpenOutcome {
        val frame = GroupFrame.parse(entry.envelope)
            ?: return OpenOutcome.Rejected("кадр группы не разбирается")
        // Открытому сообщению (уровни 0…3) ключ не нужен: его читает тот, кому ключа не
        // дадут, — описание личной группы, лента публичной. Подпись при этом проверяется
        // так же строго.
        val plain = frame.gkVersion == 0
        val groupKey = if (plain) ByteArray(0) else {
            groupKeys.key(frame.groupId, frame.gkVersion)
                ?: return OpenOutcome.NoKey("нет ключа версии ${frame.gkVersion}")
        }
        val captionKey = senderKeys[frame.senderDevice]
            ?: return OpenOutcome.NoKey("ключ подписи отправителя не получен")

        // Метаданные собирает фасад: их раскладка входит в подписываемые байты, и
        // собирать её здесь значило бы держать копию правила вдали от него самого.
        return GroupMessages.open(
            groupId = frame.groupId,
            senderId = frame.senderId,
            senderDevice = frame.senderDevice,
            kind = frame.kind,
            createdAtUnixMs = frame.createdAtUnixMs,
            threadRoot = frame.threadRoot,
            replyTo = frame.replyTo,
            gkVersion = frame.gkVersion,
            payload = frame.payload,
            signature = frame.signature,
            senderSigningPublic = captionKey,
            groupKey = groupKey,
        ).fold(
            onSuccess = { OpenOutcome.Opened(it.body, it.meta.senderId, frame.level) },
            onFailure = { OpenOutcome.Rejected("сообщение группы не открылось: ${it.message}") },
        )
    }

    /** Кадр про групповые ключи: исход остаётся в диагностике, канал не роняется. */
    private suspend fun aboutKeys(decision: EventStreamProtocol.Decision) {
        keyOrchestrator.handle(decision)?.let { lastOutcome = it }
    }

    /**
     * Круг сообщения сузили: метку у реплики поменять, и сказать словами почему.
     *
     * **Оба действия обязательны, и второе важнее.** Одна метка объясняет только тому, кто
     * включил их показ; всем остальным реплика просто пропадает из чужих лент, и это
     * выглядит как поломка. Поэтому в группу ложится строка — там же, где живёт само
     * сообщение, и там же, где админ может объяснить причину.
     *
     * Ключ строки собран из идентификатора события: тот же кадр приезжает и живым каналом,
     * и догоном истории, а строк об одном сужении должно остаться ровно одна.
     */
    private fun aboutLevel(decision: EventStreamProtocol.Decision.LevelNarrowed) {
        environment.journal.levelChanged(decision.groupId, decision.messageId, decision.level)
        val circle = MessageCircle.of(decision.level)
        environment.journal.note(
            chatId = decision.groupId,
            key = "level/${decision.groupId}/${decision.messageId}/${decision.level}",
            text = "Круг сообщения сузили: теперь «${circle.title}». ${circle.about}",
            atMs = msNow(),
        )
    }

    /**
     * Одно событие: записать конверт, потом попытаться разобрать.
     *
     * Возвращается **только после записи**: подтверждение уходит сразу после нас, а
     * подтверждённое сервер больше не пришлёт.
     */
    private suspend fun accept(chatId: String, messageId: Long, envelope: ByteArray) {
        // Групповое сообщение приходит тем же путём, но открывается иначе: у него нет
        // конверта, а подпись считается по метаданным вместе с payload.
        if (GroupFrame.isGroupFrame(envelope)) {
            acceptGroup(chatId, messageId, envelope)
            return
        }

        val sender = envelopeSender(envelope)

        // Своя копия с ЭТОГО ЖЕ устройства — не входящее сообщение, а эхо: сервер
        // рассылает конверт по всем обёрткам ключа, включая нашу собственную. Записать её
        // значит показать человеку своё сообщение дважды — один раз своим, второй чужим.
        // Именно так и выглядело на живом прогоне, на обоих устройствах сразу.
        //
        // Подтверждение серверу при этом уходит: событие обработано, повторять его не
        // надо. Молча пропустить и не подтвердить значило бы получать его вечно.
        if (ownCopy(sender?.deviceId)) {
            lastOutcome = "эхо своего сообщения пропущено"
            return
        }

        environment.incoming.receive(chatId, messageId, envelope)

        // Разбор — уже после записи. Упадёт — сообщение останется на повтор.
        val key = sender?.let { captionKey(it.userId, it.deviceId) }

        environment.incoming.openNext { entry ->
            when {
                sender == null -> OpenOutcome.Rejected("конверт не разбирается")
                key == null -> OpenOutcome.NoKey("ключ подписи отправителя не получен")
                else -> open(entry, key)
            }
        }

        // Переписка от незнакомого — со своим именем: иначе в списке появится строка без
        // имени, и человек не узнает, кто написал.
        if (sender != null && !environment.chatFacts.knows(chatId)) {
            book.remember(
                chatId = chatId,
                kind = ChatKind.Personal,
                title = network.directory.nameOrNumber(sender.userId),
                peerId = sender.userId,
            )
        }
    }

    private fun open(entry: IncomingEntry, captionKey: ByteArray): OpenOutcome =
        PersonalMessages.open(
            envelopeBytes = entry.envelope,
            myDeviceId = session.deviceId,
            me = identity,
            senderSigningPublic = captionKey,
        ).fold(
            // Записываются БАЙТЫ ТЕЛА, как пришли, а не текст: столбец читается кодеком,
            // и запись текстом означала бы «расшифровано и не читается» — состояние, в
            // котором это и нашлось на живом прогоне.
            onSuccess = { OpenOutcome.Opened(it.body, it.meta.senderId) },
            // Подпись не сошлась или обёртки для нас нет — разные беды, и причина
            // доносится дословно: человеку видно «не читается», нам — почему.
            onFailure = { OpenOutcome.NoKey(it.message ?: "не открылось") },
        )

    /**
     * Своё ли это эхо — конверт, отправленный **с этого самого устройства**.
     *
     * Отдельная функция с именем, а не условие в потоке: правило продукта, и его надо
     * читать. Копия с ДРУГОГО своего устройства — не эхо, а настоящее сообщение, которое
     * человек написал сам с телефона и хочет видеть на ПК; показывать его надо своим, а не
     * чужим, и это отдельная работа (привязка второго устройства, К5.1).
     */
    internal fun ownCopy(senderDeviceId: String?): Boolean = senderDeviceId == session.deviceId

    /** Кто прислал — по открытой части конверта. Доверенным станет после проверки подписи. */
    private fun envelopeSender(envelope: ByteArray): SentBy? =
        PersonalMessages.peekSender(envelope)?.let {
            SentBy(userId = it.userId, deviceId = it.deviceId)
        }

    private suspend fun captionKey(userId: String, deviceId: String): ByteArray? {
        senderKeys[deviceId]?.let { return it }
        val outcome = network.keys.devicesOf(userId)
        if (outcome !is DeviceKeysResult.Devices) return null
        for (device in outcome.devices) {
            senderKeys[device.deviceId] = device.signingPub
        }
        return senderKeys[deviceId]
    }

    private class SentBy(val userId: String, val deviceId: String)

    private companion object {
        /**
         * Пауза перед новой попыткой канала.
         *
         * Две секунды: канал рвётся в мобильной сети постоянно, и возвращается быстро.
         * Дольше — человек видит задержку доставки, короче — молотим сервер на каждом
         * переключении вышки.
         */
        const val ПАУЗА_ПЕРЕД_ПОВТОРОМ_МС = 2_000L
    }
}
