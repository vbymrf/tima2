package io.tima.core.database

import io.tima.core.outbox.FieldCipher
import io.tima.domain.chat.GroupKeyBook

/**
 * Групповые ключи в местной базе — переходник к порту `domain-chat`.
 *
 * **Ключ лежит под шифром покоя, и это строже, чем у тела сообщения.** Тело открывает одно
 * сообщение, GK — всю переписку группы разом, включая ту её часть, которой на этом
 * устройстве ещё нет. Поэтому открытым он не лежит нигде: ни в файле базы, ни в резервной
 * копии телефона.
 *
 * **Не открылось — значит нет.** Ключ, который не расшифровался, не отличим для нас от
 * отсутствующего: и в том и в другом случае сообщение этой версии не прочесть. Бросать
 * исключение значило бы ронять чтение всей группы из-за одной испорченной строки.
 */
class SqlGroupKeys(
    private val db: TimaDatabase,
    private val cipher: FieldCipher,
) : GroupKeyBook {

    private val q get() = db.groupKeysQueries

    override fun put(groupId: String, version: Int, key: ByteArray) {
        q.putGroupKey(group_id = groupId, version = version.toLong(), key_enc = cipher.seal(key))
    }

    override fun key(groupId: String, version: Int): ByteArray? =
        q.groupKey(group_id = groupId, version = version.toLong())
            .executeAsOneOrNull()
            ?.let { cipher.open(it) }

    override fun latestVersion(groupId: String): Int? =
        // Запрос отдаёт агрегат MAX, и у группы без ключей ответа нет — признак «ключей
        // нет» получается сам собой, без второго запроса и без счёта строк.
        q.latestGroupKey(groupId).executeAsOne().version?.toInt()

    override fun versions(groupId: String): List<Int> =
        q.groupKeyVersions(groupId).executeAsList().map { it.toInt() }

    override fun forget(groupId: String) {
        q.deleteGroupKeys(groupId)
    }
}
