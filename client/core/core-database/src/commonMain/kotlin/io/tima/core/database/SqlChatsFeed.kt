package io.tima.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.tima.core.outbox.FieldCipher
import io.tima.core.outbox.Inbox
import io.tima.core.outbox.IncomingState
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.ChatBook
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.ChatsFeed
import io.tima.domain.chat.ReadMarks
import io.tima.domain.chat.MessageBodyCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Список переписок из базы — переходник к порту `domain-chat`.
 *
 * **Список выводится из сообщений.** Строка `chats` только добавляет переписке имя, и
 * поэтому список не может её потерять: есть сообщение — есть строка в списке. Превью,
 * время, состояние и число непрочитанных считает один запрос, а не отдельные записанные
 * поля: в v1 список чатов лежал одновременно в SQLite и в `chats.json`, и они молча
 * расходились.
 *
 * **Что здесь расшифровывается.** Дважды одно и то же: превью — тело последнего
 * сообщения, имя — строка `chats`. И то и другое лежит под ключом покоя, потому что и то
 * и другое — содержимое переписки, а не метаданные. Не открылось — значит нет: строка
 * остаётся, текста нет.
 */
class SqlChatsFeed(
    private val db: TimaDatabase,
    private val codec: MessageBodyCodec,
    private val cipher: FieldCipher,
    /** Кто я: последнее сообщение может быть своим, пришедшим со второго устройства. */
    private val myUserId: String,
) : ChatsFeed {

    override fun list(limit: Int): Flow<List<ChatSummary>> =
        db.chatsQueries.chatList(
            readState = IncomingState.READ.ordinal.toLong(),
            limit = limit.toLong(),
        )
            // asFlow + mapToList: обновление приходит от самой базы. Пришло сообщение —
            // список переехал сам, без опроса по таймеру.
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { строки -> строки.map { it.toSummary() } }

    private fun ChatList.toSummary() = ChatSummary(
        chatId = chat_id,
        // Имени может не быть вовсе: профиль не приезжал. Это не причина прятать
        // переписку — сообщение есть, значит есть и строка.
        title = title_enc?.let { cipher.open(it) }?.decodeToString(),
        // Нет строки имени — считаем переписку личной: группа без имени не заводится, а
        // личная появляется от одного сообщения.
        kind = if (kind == ГРУППА) ChatKind.Group else ChatKind.Personal,
        peerId = peer_id,
        preview = превью(last_direction, last_state, last_body),
        // То же правило, что в переписке: входящее от себя же — своё.
        lastOutgoing = last_direction == ИСХОДЯЩЕЕ ||
            (last_sender.isNotEmpty() && last_sender == myUserId),
        lastDisplay = displayOf(last_direction, last_state),
        atMs = last_at ?: 0,
        unread = unread.toInt(),
    )

    /**
     * Превью — первая строка последнего сообщения.
     *
     * Те же два шага и в том же порядке, что в самом чате: открыть поле ключом покоя,
     * потом разобрать тело кодеком. У входящего до разбора в столбце лежит конверт, а не
     * тело, и превью у такой строки нет — показывать байты конверта было бы хуже пустоты.
     */
    private fun превью(direction: Long, state: Long, поле: ByteArray): String? {
        if (direction != ИСХОДЯЩЕЕ && !входящееРазобрано(state)) return null
        val открытое = cipher.open(поле) ?: return null
        return codec.decodeText(открытое)
    }

    private companion object {
        /** `kind` из схемы: 0 — личная, 1 — группа. */
        const val ГРУППА = 1L
    }
}

/**
 * Запись имени переписки — переходник к порту `domain-chat`.
 *
 * «Запомнить», а не «создать»: список переписок выводится из сообщений, и строка здесь
 * необязательна — она добавляет переписке имя и собеседника. Имя ложится **под шифром
 * покоя**, тем же, что тело сообщения: имя собеседника — это содержимое переписки, а не
 * метаданные.
 */
class SqlChatBook(
    private val db: TimaDatabase,
    private val cipher: FieldCipher,
) : ChatBook {

    override fun remember(chatId: String, kind: ChatKind, title: String?, peerId: String?) {
        db.chatsQueries.upsertChat(
            chat_id = chatId,
            kind = if (kind == ChatKind.Group) 1L else 0L,
            title_enc = title?.let { cipher.seal(it.encodeToByteArray()) },
            peer_id = peerId,
        )
    }
}

/**
 * Отметки прочтения — переходник к порту `domain-chat`.
 *
 * Переход делает машина входящих: предусловие «прочитанным становится разобранное»
 * принадлежит ей, и обходить её запросом напрямую значило бы держать правило в двух местах.
 */
class SqlReadMarks(private val inbox: Inbox) : ReadMarks {
    override fun markChatRead(chatId: String): Int = inbox.markChatRead(chatId)
}
