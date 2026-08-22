package io.tima.core.encryption

import io.tima.crypto.MessageContent
import io.tima.crypto.MessageContentCodec
import io.tima.crypto.MessageSerializer
import io.tima.domain.chat.MessageBodyCodec

/**
 * Упаковка тела сообщения — переходник к порту `domain-chat`.
 *
 * `zstd(protobuf(MessageBody))`, **один кодек на провод и на диск**. В v1 тело
 * склеивалось разделителем `\n\0`, и разметка через такую склейку не проходила вовсе
 * (Plan.md §3.4); здесь тот же вызов служит и отправке, и записи в базу — разойтись
 * им негде.
 *
 * Отправитель кладёт в тело **оба** представления текста: узлы и плоскую склейку.
 * Клиент, не знающий про узлы, прочитает плоский текст и покажет сообщение без
 * оформления — это хуже, чем с оформлением, и несравнимо лучше, чем пустое сообщение.
 */
object TextBodyCodec : MessageBodyCodec {

    override fun encodeText(text: String): ByteArray =
        MessageSerializer.encodeBody(MessageContentCodec.toBody(MessageContent.text(text)))

    /**
     * Обратный ход — для входящих.
     *
     * @return содержимое либо неудача. Именно `Result`, а не исключение: нечитаемое
     *   сообщение — обычное состояние входящей машины ([io.tima.core.outbox.Inbox]),
     *   а не поломка.
     */
    /**
     * Текст для показа на экране.
     *
     * Берётся плоская склейка узлов: клиент, не знающий про узлы, покажет сообщение без
     * оформления — это хуже, чем с оформлением, и несравнимо лучше, чем пустое место.
     * Разметка приедет вместе с редактором.
     */
    override fun decodeText(body: ByteArray): String? = decode(body).getOrNull()?.plainText()

    fun decode(payload: ByteArray): Result<MessageContent> =
        MessageSerializer.decodeBody(payload).map { MessageContentCodec.fromBody(it) }
}
