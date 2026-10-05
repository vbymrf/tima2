package io.tima.core.database

/**
 * Прежние личности этого аккаунта (М6, Р55): после перерегистрации история прежней переносится
 * в новую, и её сообщения — **свои**, справа и без имени автора, как со второго своего устройства.
 *
 * Читается из настроек аккаунта сразу, без потока: лента решает «своё ли» при каждой выдаче, и
 * до первого чтения из потока она показала бы свои сообщения чужими.
 */
class PriorIdentities(private val db: TimaDatabase) {

    @kotlin.concurrent.Volatile
    private var known: Set<String> = read()

    /** Прежние личности; пусто — перерегистрации не было. */
    fun ids(): Set<String> = known

    /** Запомнить прежнюю личность. Повтор не вредит. */
    fun add(userId: String) {
        if (userId.isBlank() || userId in known) return
        val next = known + userId
        db.transaction {
            db.settingsQueries.put(KEY, next.joinToString(","))
            db.settingsQueries.update(next.joinToString(","), KEY)
        }
        known = next
    }

    private fun read(): Set<String> = runCatching {
        db.settingsQueries.all().executeAsList().firstOrNull { it.name == KEY }?.value_
            ?.split(',')?.filter { it.isNotBlank() }?.toSet()
    }.getOrNull() ?: emptySet()

    private companion object {
        const val KEY = "identity.prior.v1"
    }
}
