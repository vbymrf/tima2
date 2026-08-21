package io.tima.domain.chat

/**
 * Отправка текста — К4.1.
 *
 * **Что здесь есть и чего нет.** Есть три правила продукта: ключ идемпотентности
 * назначается **до первой попытки**, тело собирается один раз и одним кодеком, пустое
 * сообщение не отправляется. Нет ни сети, ни базы, ни шифрования: очередь,
 * упаковка и ключи приходят портами.
 *
 * Почему это не «лишняя обёртка над `enqueue`». Потому что `enqueue` не знает, откуда
 * берётся `dedup_key`, а от этого зависит, увидит ли собеседник дубль после обрыва.
 * Правило «ключ назначает клиент до первой попытки» — ровно то, чего не было в v1
 * (инвентарь, пункт 8), и жить оно должно в одном месте, а не в каждом вызывающем.
 */
class SendMessage(
    private val queue: OutgoingQueue,
    private val codec: MessageBodyCodec,
    private val keys: DedupKeys,
    /**
     * Ранняя отсечка по размеру тела. Точный запас неизвестен: конверт это тело плюс
     * обёртка ключа на каждое устройство получателя, а их число заранее не знает никто.
     * Поэтому здесь дешёвая проверка, а последнее слово — у транспорта, который знает
     * предел сервера (`maxEnvelopeBytes`) и отвечает на его превышение окончательным
     * отказом.
     */
    private val maxBodyBytes: Int = 4 shl 20,
) {

    /**
     * @param chatId переписка, в которую уходит сообщение.
     * @param text то, что набрал человек.
     */
    fun send(chatId: String, text: String): SendMessageResult {
        require(chatId.isNotBlank()) { "chatId пустой" }

        // Пустое отсекаем до всего остального: очередь, кодек и ключ на нём тратить
        // незачем, а «отправилось ничего» — это ещё и строка в переписке.
        if (text.isBlank()) return SendMessageResult.Empty

        val body = codec.encodeText(text)
        if (body.size > maxBodyBytes) {
            return SendMessageResult.TooLarge(body.size, maxBodyBytes)
        }

        // Ключ назначается ЗДЕСЬ, до первой попытки. Назначь его при отправке — и
        // повтор после обрыва уйдёт с новым ключом, то есть собеседник получит два
        // одинаковых сообщения, а сервер не сможет их склеить.
        val dedupKey = keys.newKey()
        require(dedupKey.isNotBlank()) { "ключ идемпотентности пустой" }

        return if (queue.enqueue(dedupKey, chatId, body)) {
            SendMessageResult.Queued(dedupKey)
        } else {
            // Такой ключ уже в очереди. Не ошибка: так выглядит повторное нажатие,
            // пересечение живого канала с догоном, восстановление после перезапуска.
            SendMessageResult.AlreadyQueued(dedupKey)
        }
    }
}

/** Чем закончилась постановка сообщения в очередь. */
sealed interface SendMessageResult {
    /** Принято. Дальше — дело очереди: доставка не мгновенна и не обязана быть. */
    data class Queued(val dedupKey: String) : SendMessageResult

    /** Такое сообщение уже в очереди. Не ошибка. */
    data class AlreadyQueued(val dedupKey: String) : SendMessageResult

    /** Пустой текст. Отдельный исход, а не исключение: это обычное нажатие мимо. */
    data object Empty : SendMessageResult

    /** Тело больше предела. Медиа ходит объектным хранилищем, а не этим путём. */
    data class TooLarge(val bytes: Int, val limit: Int) : SendMessageResult
}

/**
 * Порт к очереди исходящих. Реализуется `core-outbox`.
 *
 * @return `false`, если такой `dedupKey` уже есть.
 */
fun interface OutgoingQueue {
    fun enqueue(dedupKey: String, chatId: String, body: ByteArray): Boolean
}

/**
 * Порт к упаковке тела. Реализуется `core-encryption`: `zstd(protobuf(MessageBody))`.
 *
 * **Один кодек на провод и на диск** — решение плана §3.4: в v1 тело склеивалось
 * разделителем `\n\0`, и разметка через такую склейку не проходила вовсе.
 */
fun interface MessageBodyCodec {
    fun encodeText(text: String): ByteArray
}

/**
 * Порт к ключам идемпотентности. Отдельный порт, а не `Random` внутри: тест обязан
 * уметь задать ключ, иначе проверить «ключ назначен до попытки» нечем.
 */
fun interface DedupKeys {
    fun newKey(): String
}
