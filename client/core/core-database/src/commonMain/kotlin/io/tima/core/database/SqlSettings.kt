package io.tima.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.tima.domain.chat.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Настройки экранов в местной базе.
 *
 * **Открытым текстом, в отличие от имён и переписки.** Здесь лежит то, что человек
 * выбрал глазами: вид списка, показывать ли поиск. Шифровать выбор вида не от кого —
 * он не говорит о человеке ничего, чего не видно на его же экране.
 */
class SqlSettings(
    private val db: TimaDatabase,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : Settings {

    override fun all(): Flow<Map<String, String>> =
        db.settingsQueries.all().asFlow().mapToList(io)
            .map { rows -> rows.associate { it.name to it.value_ } }

    override suspend fun put(name: String, value: String): Unit = withContext(io) {
        db.transaction {
            db.settingsQueries.put(name, value)
            db.settingsQueries.update(value, name)
        }
    }
}
