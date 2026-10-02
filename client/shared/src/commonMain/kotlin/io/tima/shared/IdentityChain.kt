package io.tima.shared

import io.tima.core.database.SqlChatRehome
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.encryption.PersonalChatIdsOverKodium
import io.tima.core.network.UsersApi
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words

/**
 * Один человек — одна переписка, сколько бы раз он ни начинал заново
 * (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ДУ6, Р8, Р10, Р26, Р30).
 *
 * Для каждой личной переписки спрашиваем сервер, какая личность у аккаунта собеседника сейчас
 * текущая. Сменилась — переписка переезжает под чат текущей, и в ней строка «сменил ключ
 * личности». Новую отменил владелец — переезжает обратно, со строкой «восстановлена»; личность
 * помечается отменённой, и её сообщения подписываются «личность отменена владельцем».
 */
class IdentityChain(
    private val environment: Environment,
    private val users: UsersApi,
    private val me: String,
    private val msNow: () -> Long,
    private val words: () -> Words = { CurrentWords.value },
) {
    private val rehome = SqlChatRehome(environment.db)

    /** Проверить все личные переписки. Возвращает, сколько переехало. */
    suspend fun refresh(): Int {
        val chats = runCatching { environment.db.chatsQueries.personalContacts().executeAsList() }.getOrDefault(emptyList())
        val peers = chats.mapNotNull { it.peer_id }.distinct()
        if (peers.isEmpty()) return 0
        val statuses = users.identities(peers) ?: return 0
        var moved = 0
        for (chat in chats) {
            val peer = chat.peer_id ?: continue
            val st = statuses[peer] ?: continue
            if (st.cancelled) rememberCancelled(peer)
            val current = st.currentId ?: continue
            if (current == peer || current == me) continue
            val newChat = PersonalChatIdsOverKodium.personalChatId(me, current)
            runCatching { rehome.rehome(chat.chat_id, newChat, current) }
                .onFailure { Journal.trouble(LogCode.DEVICE_TRUST, "переписка не переехала", "причина" to (it.message ?: "?")) }
                .onSuccess {
                    moved++
                    val line = if (st.cancelled) words().auth.identityRestoredLine else words().auth.identityChangedLine
                    runCatching { environment.journal.note(newChat, "identity:$peer>$current", line, msNow()) }
                    Journal.note(LogCode.DEVICE_TRUST, "собеседник сменил личность — переписка переехала", "было" to peer.take(8), "стало" to current.take(8))
                }
        }
        return moved
    }

    private suspend fun rememberCancelled(userId: String) {
        runCatching { environment.settings.put(CANCELLED_PREFIX + userId, "1") }
    }

    companion object {
        /** Отменённые личности — их сообщения подписываются (Р30). Имя латиницей. */
        const val CANCELLED_PREFIX: String = "trust.cancelled."
    }
}
