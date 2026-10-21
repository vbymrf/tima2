package io.tima.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.tima.core.outbox.FieldCipher
import io.tima.domain.chat.COMMON_SECTION_ID
import io.tima.domain.chat.Pick
import io.tima.domain.chat.PickStore
import io.tima.domain.chat.Pointer
import io.tima.domain.chat.RefCache
import io.tima.domain.chat.Section
import io.tima.domain.chat.SectionSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Подборка сообщений — указатели — на устройстве (Решения §11.2, ПЛАН-(УК)). */
class SqlPicks(private val db: TimaDatabase, private val io: CoroutineDispatcher = Dispatchers.Default) : PickStore {
    override fun has(level: String, chatId: String, serverId: Long): Boolean =
        db.picksQueries.hasPick(level, chatId, serverId).executeAsOne() > 0

    override fun count(level: String): Int = db.picksQueries.countPicks(level).executeAsOne().toInt()

    override fun add(level: String, chatId: String, serverId: Long, atMs: Long) {
        db.picksQueries.addPick(level, chatId, serverId, atMs)
    }

    override fun list(level: String): List<Pick> =
        db.picksQueries.picksOf(level).executeAsList().map { Pick(it.chat_id, it.server_id) }

    override fun listLike(pattern: String): List<Pick> =
        db.picksQueries.picksLike(pattern).executeAsList().map { Pick(it.chat_id, it.server_id) }

    override fun pointers(): Flow<List<Pointer>> =
        db.picksQueries.allPicks().asFlow().mapToList(io).map { rows ->
            rows.map { Pointer(it.level_key, it.chat_id, it.server_id, it.added_at, it.section_id) }
        }

    override fun remove(level: String, chatId: String, serverId: Long) {
        db.picksQueries.removePick(level, chatId, serverId)
    }

    override fun removeLevel(level: String) {
        db.picksQueries.removeLevel(level)
    }

    override fun move(level: String, chatId: String, serverId: Long, sectionId: String) {
        db.picksQueries.movePick(sectionId, level, chatId, serverId)
    }
}

/**
 * Разделы указателей — таблица `pointer_section` (миграция 22 → 23). Строение и правила — как у
 * [SqlCommunitySections]: устойчивый случайный `id`, имён-двойников не заводится, убранный раздел
 * возвращает свои указатели в «Общий».
 */
@OptIn(ExperimentalUuidApi::class)
class SqlPointerSections(
    private val db: TimaDatabase,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : SectionSet {

    override fun sections(): Flow<List<Section>> =
        db.picksQueries.pointerSections().asFlow().mapToList(io).map { rows ->
            rows.map { Section(id = it.id, name = it.name, icon = it.icon.toInt(), place = it.place.toInt()) }
        }

    override suspend fun setCommon(name: String, icon: Int): Unit = withContext(io) {
        val clean = name.trim().ifEmpty { return@withContext }
        db.transaction {
            if (db.picksQueries.pointerSections().executeAsList().none { it.id == COMMON_SECTION_ID }) {
                db.picksQueries.addPointerSection(COMMON_SECTION_ID, clean, icon.toLong(), 1_000L)
            }
            db.picksQueries.renamePointerSection(clean, icon.toLong(), COMMON_SECTION_ID)
        }
    }

    override suspend fun add(name: String, icon: Int): String = withContext(io) {
        val clean = name.trim()
        db.transactionWithResult {
            db.picksQueries.pointerSectionByName(clean).executeAsOneOrNull()?.let { return@transactionWithResult it }
            val place = db.picksQueries.pointerSections().executeAsList().size.toLong()
            val id = Uuid.random().toString()
            db.picksQueries.addPointerSection(id, clean, icon.toLong(), place)
            id
        }
    }

    override suspend fun rename(id: String, name: String, icon: Int): Unit = withContext(io) {
        db.picksQueries.renamePointerSection(name.trim(), icon.toLong(), id)
    }

    override suspend fun place(id: String, place: Int): Unit = withContext(io) {
        db.picksQueries.placePointerSection(place.toLong(), id)
    }

    override suspend fun remove(id: String): Unit = withContext(io) {
        db.transaction {
            db.picksQueries.emptyPointerSection(id)
            db.picksQueries.removePointerSection(id)
        }
    }
}

/** Сообщения-цели с сервера — закрытые ключом устройства (ПЛАН-(УК) УК1). */
class SqlRefCache(private val db: TimaDatabase, private val cipher: FieldCipher) : RefCache {
    override fun get(chatId: String, serverId: Long): Pair<String, ByteArray>? {
        val row = db.picksQueries.refCached(chatId, serverId).executeAsOneOrNull() ?: return null
        return cipher.open(row.body_enc)?.let { row.kind to it }
    }

    override fun put(chatId: String, serverId: Long, kind: String, body: ByteArray) {
        db.picksQueries.putRef(chatId, serverId, kind, cipher.seal(body))
    }

    override fun drop(chatId: String, serverId: Long) {
        db.picksQueries.dropRef(chatId, serverId)
    }
}
