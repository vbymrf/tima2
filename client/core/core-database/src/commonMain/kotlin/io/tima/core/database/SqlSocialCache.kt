package io.tima.core.database

import io.tima.core.outbox.FieldCipher
import io.tima.domain.chat.ContentItem
import io.tima.domain.chat.ContentSection
import io.tima.domain.chat.ContentStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Лента канала и «Содержимое» на устройстве (ПЛАН-(СЦ) СЦ20, СЦ22): всё закрыто ключом устройства, как тела сообщений.
 * Запись канала хранится тем JSON, что прислал сервер, — разбирает её тот же код, что разбирает ответ.
 */
@OptIn(ExperimentalEncodingApi::class)
class SqlSocialCache(
    private val db: TimaDatabase,
    private val cipher: FieldCipher,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : ContentStore {

    /** Сохранённые записи канала, новые первыми: номер, время, JSON записи. */
    suspend fun posts(channelId: String, limit: Int): List<Triple<Long, Long, String>> = withContext(io) {
        db.socialQueries.postsOf(channelId, limit.toLong()).executeAsList().mapNotNull { row ->
            cipher.open(row.raw_enc)?.decodeToString()?.let { Triple(row.post_id, row.at_ms, it) }
        }
    }

    /**
     * Пришедший с сервера отрезок записей [from, to] — замена сохранённого в нём: чего сервер в отрезке не прислал,
     * того больше нет (удалено). `posts` — номер, время, JSON.
     */
    suspend fun putPosts(channelId: String, from: Long, to: Long, posts: List<Triple<Long, Long, String>>): Unit = withContext(io) {
        db.transaction {
            if (from in 1..to) db.socialQueries.dropPostsBetween(channelId, from, to)
            for ((id, at, raw) in posts) db.socialQueries.putPost(channelId, id, at, cipher.seal(raw.encodeToByteArray()))
        }
    }

    /** Записи канала новее номера, от старых к новым — «Содержимому» (СЦ20). */
    suspend fun postsAfter(channelId: String, after: Long): List<Triple<Long, Long, String>> = withContext(io) {
        db.socialQueries.postsAfter(channelId, after).executeAsList().mapNotNull { row ->
            cipher.open(row.raw_enc)?.decodeToString()?.let { Triple(row.post_id, row.at_ms, it) }
        }
    }

    override suspend fun items(entityKey: String): List<ContentItem> = withContext(io) {
        db.socialQueries.contentOf(entityKey).executeAsList().mapNotNull { row ->
            val section = ContentSection.fromWire(row.section) ?: return@mapNotNull null
            val f = cipher.open(row.data_enc)?.decodeToString()?.split(SEP) ?: return@mapNotNull null
            if (f.size < 11) return@mapNotNull null
            ContentItem(
                section = section, messageId = row.message_id, index = row.idx.toInt(), atMs = row.at_ms,
                name = f[0], size = f[1].toLongOrNull() ?: 0, mediaId = f[2],
                key = runCatching { Base64.decode(f[3]) }.getOrDefault(ByteArray(0)),
                mime = f[4], url = f[5], width = f[6].toIntOrNull() ?: 0, height = f[7].toIntOrNull() ?: 0,
                durationMs = f[8].toLongOrNull() ?: 0, title = f[9], authorId = f[10],
            )
        }
    }

    override suspend fun scannedUpTo(entityKey: String): Long = withContext(io) {
        db.socialQueries.scanOf(entityKey).executeAsOneOrNull() ?: -1L
    }

    override suspend fun put(entityKey: String, items: List<ContentItem>, upTo: Long): Unit = withContext(io) {
        db.transaction {
            for (i in items) {
                val data = listOf(
                    i.name, i.size.toString(), i.mediaId, Base64.encode(i.key), i.mime, i.url,
                    i.width.toString(), i.height.toString(), i.durationMs.toString(), i.title, i.authorId,
                ).joinToString(SEP) { it.replace(SEP, " ") }
                db.socialQueries.putContent(entityKey, i.messageId, i.index.toLong(), i.section.wire, i.atMs, cipher.seal(data.encodeToByteArray()))
            }
            db.socialQueries.putScan(entityKey, upTo)
        }
    }

    override suspend fun drop(entityKey: String, messageId: Long) {
        withContext(io) { db.socialQueries.dropContentOf(entityKey, messageId) }
    }

    private companion object {
        const val SEP = "\u0001"
    }
}
