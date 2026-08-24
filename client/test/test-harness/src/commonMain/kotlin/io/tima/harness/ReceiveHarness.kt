package io.tima.harness

import io.tima.core.network.EventStreamProtocol
import io.tima.core.outbox.Inbox
import io.tima.core.outbox.IncomingEntry
import io.tima.core.outbox.OpenOutcome

/**
 * Приём **на кадрах сервера** — К4.5, без сокета и без сервера.
 *
 * Сценарий пишется в том виде, в каком приём и бывает: пришёл кадр, его надо разобрать,
 * записать и подтвердить. Разбирает настоящий [EventStreamProtocol], записывает
 * настоящая входящая машина ([Inbox]) над настоящей базой. Нет только сокета — и в нём
 * решений нет.
 *
 * **Что здесь проверяется и чего не проверить иначе.** Дедупликация догона истории с
 * живым каналом. У одного и того же сообщения, пришедшего дважды, **разные
 * `event_id`** (у догона свой, у живого кадра свой) и **один `message_id`** — тот,
 * что назначил отправитель и который входит в подпись. Значит опознавать повтор надо
 * по второму, а не по первому. Ошибка здесь даёт человеку два одинаковых сообщения, и
 * поймать её можно только на кадрах: внутри машины состояний `event_id` не существует
 * вовсе.
 */
class ReceiveHarness(private val inbox: Inbox) {

    private val protocol = EventStreamProtocol()

    /** Что мы отправили серверу в ответ. По этому списку видно, что и когда подтвердили. */
    val sent = mutableListOf<String>()

    /** Кадры, которые пропустили, и почему. */
    val skipped = mutableListOf<String>()

    /** Кадры про групповые ключи: что пришло. Выполнение — не дело стенда. */
    val проКлючи = mutableListOf<String>()

    /**
     * Обрабатывает кадр сервера так, как это делает живой канал.
     *
     * Порядок здесь и есть предмет проверки: **запись, потом подтверждение**.
     * Подтвердить раньше — значит сдвинуть серверный курсор до того, как сообщение
     * оказалось у нас; умри процесс в этот момент, и сообщение не придёт никогда.
     */
    fun onFrame(frame: String) {
        when (val решение = protocol.decide(frame)) {
            is EventStreamProtocol.Decision.Deliver -> {
                inbox.receive(
                    chatId = решение.event.chatId,
                    messageId = решение.event.messageId,
                    envelope = решение.event.envelope,
                )
                sent += protocol.ackFrame(решение.event.eventId)
            }

            is EventStreamProtocol.Decision.Skip -> {
                skipped += решение.reason
                решение.eventId?.let { sent += protocol.ackFrame(it) }
            }

            is EventStreamProtocol.Decision.SyncDone ->
                if (решение.more) sent += protocol.pullFrame(решение.nextCursor)

            // Кадры про групповые ключи стенд подтверждает и запоминает, но не
            // выполняет: ротация требует escrow, крипты и сети, а предмет этой проверки —
            // порядок «запись, потом подтверждение». Подтвердить всё же обязаны: иначе
            // курсор застрянет, и следующие сообщения не приедут.
            is EventStreamProtocol.Decision.KeysArrived -> {
                проКлючи += "keys:${решение.groupId}"
                решение.eventId?.let { sent += protocol.ackFrame(it) }
            }

            is EventStreamProtocol.Decision.ShareKeys -> {
                проКлючи += "share:${решение.groupId}:${решение.versions.joinToString(",")}"
                решение.eventId?.let { sent += protocol.ackFrame(it) }
            }

            is EventStreamProtocol.Decision.RotationNeeded -> {
                проКлючи += "rotate:${решение.groupId}:${решение.reason}"
                решение.eventId?.let { sent += protocol.ackFrame(it) }
            }

            is EventStreamProtocol.Decision.NeedHistory,
            is EventStreamProtocol.Decision.ServerTrouble,
            is EventStreamProtocol.Decision.Ready,
            -> Unit
        }
    }

    /**
     * Разбирает всё принятое.
     *
     * @param open расшифровка. В сценариях подменяется, потому что предмет проверки —
     *   поведение при удаче и неудаче, а не сама криптография: она проверена своим
     *   кругом и векторами на двух платформах.
     */
    fun openAll(open: (IncomingEntry) -> OpenOutcome): Int {
        var n = 0
        while (inbox.openNext(open) != null) n++
        return n
    }

    /** Сколько записей у переписки — по этому числу видны дубли. */
    fun count(): Int = inbox.pending().size
}
