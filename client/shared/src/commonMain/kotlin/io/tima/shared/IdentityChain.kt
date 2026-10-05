package io.tima.shared

import io.tima.core.database.SqlChatRehome
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.encryption.PersonalChatIdsOverKodium
import io.tima.core.network.UsersApi
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import kotlinx.coroutines.flow.first

/**
 * Один человек — одна переписка, сколько бы раз он ни начинал заново
 * (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ6, Р8, Р10, Р26, Р30).
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
        val chats = runCatching { rehome.personalPeers() }.getOrDefault(emptyList())
        val peers = chats.map { it.second }.distinct()
        if (peers.isEmpty()) return 0
        val statuses = users.identities(peers) ?: return 0
        // Текущие личности, на которые переезжаем, — чтобы отличить перерегистрацию (ДУ9) от «начать заново».
        val currents = statuses.values.mapNotNull { it.currentId }.filter { it !in statuses && it != me }.distinct()
        val currentStatuses = if (currents.isEmpty()) emptyMap() else users.identities(currents).orEmpty()
        val marks = runCatching { environment.settings.all().first() }.getOrDefault(emptyMap())
        var moved = 0
        for ((chatId, peer) in chats) {
            val st = statuses[peer] ?: continue
            // Удаляемая личность (ДУ11) — как отменённая: её сообщения с пометкой (Р49).
            if (st.cancelled || st.deleteAt != null) rememberCancelled(peer)
            val current = st.currentId ?: continue
            if (current == peer || current == me) {
                noteDispute(chatId, peer, st.disputedUntil, marks)
                continue
            }
            val newChat = PersonalChatIdsOverKodium.personalChatId(me, current)
            val w = words().auth
            runCatching { rehome.rehome(chatId, newChat, peer, current) }
                .onFailure { Journal.trouble(LogCode.DEVICE_TRUST, "переписка не переехала", "причина" to (it.message ?: "?")) }
                .onSuccess {
                    moved++
                    val line = when {
                        st.deleteAt != null -> w.peerBackToOld
                        st.cancelled -> w.identityRestoredLine
                        currentStatuses[current]?.reregistered == true -> w.peerReregistered
                        else -> w.identityChangedLine
                    }
                    runCatching { environment.journal.note(newChat, "identity:$peer>$current", line, msNow()) }
                    // Был спор — он кончился исходом: строка перед строкой о личности.
                    noteDispute(newChat, peer, null, marks)
                    val what = if (st.cancelled || st.deleteAt != null) "новую личность собеседника отменили — переписка вернулась" else "собеседник сменил личность — переписка переехала"
                    Journal.note(LogCode.DEVICE_TRUST, what, "было" to peer.take(8), "стало" to current.take(8))
                }
        }
        return moved
    }

    /**
     * Спор за аккаунт собеседника (ДУ9): начался — строка «оспаривается до …», кончился — «спор
     * завершён». Помнится в настройках, чтобы строка не повторялась при каждой сверке.
     */
    private suspend fun noteDispute(chatId: String, peer: String, until: Long?, marks: Map<String, String>) {
        val key = DISPUTE_PREFIX + peer
        val known = marks[key].orEmpty()
        val now = until?.toString().orEmpty()
        if (now == known) return
        val line = if (until != null) words().auth.peerDisputed(reregDate(until)) else words().auth.peerDisputeOver
        runCatching { environment.journal.note(chatId, "dispute:$peer:${now.ifEmpty { "over:$known" }}", line, msNow()) }
        runCatching { environment.settings.put(key, now) }
    }

    private suspend fun rememberCancelled(userId: String) {
        runCatching { environment.settings.put(CANCELLED_PREFIX + userId, "1") }
    }

    companion object {
        /** Отменённые личности — их сообщения подписываются (Р30). Имя латиницей. */
        const val CANCELLED_PREFIX: String = "trust.cancelled."

        /** Спор за аккаунт собеседника (ДУ9): до какого момента, мс; пусто — спора нет. */
        const val DISPUTE_PREFIX: String = "trust.dispute."
    }
}
