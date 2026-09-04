package io.tima.core.database

import io.tima.core.outbox.FieldCipher
import io.tima.core.outbox.IncomingState
import io.tima.domain.chat.ChatJournal
import io.tima.domain.chat.MessageBodyCodec

/**
 * [ChatJournal] поверх той же таблицы `messages`.
 *
 * Служебная строка кладётся тем же кодеком и тем же шифром покоя, что и обычное тело:
 * читает её тот же запрос страницы, и второе представление текста завело бы второй способ
 * его испортить. Отличает её столбец `system` — по нему же строится и показ.
 *
 * Состояние `READ`: служебную строку никто не «прочитывает» отдельно, и оставить её
 * непрочитанной значило бы держать в списке переписок янтарную точку о том, чего человек
 * не писал и на что не ответит.
 */
class SqlChatJournal(
    private val db: TimaDatabase,
    private val codec: MessageBodyCodec,
    private val cipher: FieldCipher,
) : ChatJournal {

    private val q get() = db.messagesQueries

    override fun levelChanged(chatId: String, messageId: Long, level: Int) {
        q.updateLevel(level = level.toLong(), chatId = chatId, serverId = messageId)
    }

    override fun note(chatId: String, key: String, text: String, atMs: Long) {
        q.insertSystem(
            // Ключ идёт от события сервера, а не от часов: одно и то же событие приезжает
            // и живым каналом, и догоном истории, и строка от этого не должна удваиваться.
            dedup_key = key,
            chat_id = chatId,
            client_ts = atMs,
            state = IncomingState.READ.ordinal.toLong(),
            body_enc = cipher.seal(codec.encodeText(text)),
        )
    }
}
