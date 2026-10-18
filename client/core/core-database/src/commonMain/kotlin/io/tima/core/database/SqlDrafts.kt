package io.tima.core.database

import io.tima.core.outbox.FieldCipher
import io.tima.domain.chat.Drafts

/** Черновики под ключом покоя: открытым текст черновика на диск не ложится (Решения §9). */
class SqlDrafts(private val db: TimaDatabase, private val cipher: FieldCipher, private val nowMs: () -> Long) : Drafts {
    override fun load(chatId: String): String? =
        db.draftsQueries.draftOf(chatId).executeAsOneOrNull()?.let { cipher.open(it) }?.decodeToString()

    override fun save(chatId: String, text: String) {
        if (text.isBlank()) db.draftsQueries.dropDraft(chatId)
        else db.draftsQueries.putDraft(chatId, cipher.seal(text.encodeToByteArray()), nowMs())
    }
}
