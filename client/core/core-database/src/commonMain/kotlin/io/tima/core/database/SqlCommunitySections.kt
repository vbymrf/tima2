package io.tima.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.tima.domain.chat.ChatSections
import io.tima.domain.chat.COMMON_SECTION_ID
import io.tima.domain.chat.Section
import io.tima.domain.chat.SectionSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Набор разделов сообществ — таблица `community_section` (миграция 9 → 10).
 *
 * Строение и правила те же, что у разделов книги в [SqlBook]: устойчивый случайный `id`,
 * имён-двойников не заводится, порядок — по времени появления, убранный раздел
 * возвращает своё содержимое в «Общий». Повторено, а не вынесено в общий класс: две таблицы
 * с разными именами запросов, и общий класс здесь был бы отражением через строки.
 */
@OptIn(ExperimentalUuidApi::class)
class SqlCommunitySections(
    private val db: TimaDatabase,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : SectionSet, ChatSections {

    override fun sections(): Flow<List<Section>> =
        db.chatsQueries.communitySections().asFlow().mapToList(io).map { rows ->
            rows.map { Section(id = it.id, name = it.name, icon = it.icon.toInt(), place = it.place.toInt()) }
        }

    override suspend fun setCommon(name: String, icon: Int): Unit = withContext(io) {
        val clean = name.trim().ifEmpty { return@withContext }
        db.transaction {
            if (db.chatsQueries.communitySections().executeAsList().none { it.id == COMMON_SECTION_ID }) {
                db.chatsQueries.addCommunitySection(COMMON_SECTION_ID, clean, icon.toLong(), 1_000L)
            }
            db.chatsQueries.renameCommunitySection(clean, icon.toLong(), COMMON_SECTION_ID)
        }
    }

    override suspend fun add(name: String, icon: Int): String = withContext(io) {
        val clean = name.trim()
        db.transactionWithResult {
            db.chatsQueries.communitySectionByName(clean).executeAsOneOrNull()?.let { return@transactionWithResult it }
            val place = db.chatsQueries.communitySections().executeAsList().size.toLong()
            val id = Uuid.random().toString()
            db.chatsQueries.addCommunitySection(id, clean, icon.toLong(), place)
            id
        }
    }

    override suspend fun rename(id: String, name: String, icon: Int): Unit = withContext(io) {
        db.chatsQueries.renameCommunitySection(name.trim(), icon.toLong(), id)
    }

    override suspend fun place(id: String, place: Int): Unit = withContext(io) {
        db.chatsQueries.placeCommunitySection(place.toLong(), id)
    }

    override suspend fun remove(id: String): Unit = withContext(io) {
        db.transaction {
            db.chatsQueries.emptyCommunitySection(id)
            db.chatsQueries.removeCommunitySection(id)
        }
    }

    override suspend fun moveTo(chatId: String, sectionId: String): Unit = withContext(io) {
        db.chatsQueries.setChatSection(sectionId, chatId)
    }
}
