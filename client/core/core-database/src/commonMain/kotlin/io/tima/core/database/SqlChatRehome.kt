package io.tima.core.database

/**
 * Перенос личной переписки под текущую личность собеседника (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ДУ6, Р26).
 *
 * Идентификатор личного чата выводится из пары личностей, и «начать заново» даёт собеседнику
 * новую. Человек же один — и переписка с ним одна: сообщения прежней личности переезжают под
 * чат текущей, строка списка — вместе с ними. Отменили новую личность — переезжают обратно.
 */
class SqlChatRehome(db: TimaDatabase) {

    private val messages = db.messagesQueries
    private val chats = db.chatsQueries

    /** Личные переписки и их собеседники: `chatId → peerId`. */
    fun personalPeers(): List<Pair<String, String>> =
        chats.personalContacts().executeAsList().mapNotNull { row -> row.peer_id?.let { row.chat_id to it } }

    fun rehome(oldChat: String, newChat: String, newPeer: String) {
        if (oldChat == newChat) return
        messages.transaction {
            messages.rehomeMessages(newChat = newChat, oldChat = oldChat)
            chats.moveChatRow(newChat = newChat, peer = newPeer, oldChat = oldChat)
            chats.deleteChatRow(oldChat)
            chats.setChatPeer(peer = newPeer, chatId = newChat)
        }
    }
}
