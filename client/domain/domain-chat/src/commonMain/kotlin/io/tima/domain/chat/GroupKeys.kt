package io.tima.domain.chat

/**
 * Групповые ключи: забрать адресованные нам обёртки и положить их себе.
 *
 * **Почему это отдельная работа, а не часть получения сообщений.** Обёртку ключа выдают
 * один раз — при ротации, — и адресована она устройству, а не переписке. Устройство,
 * добавленное в группу вчера, обёртки вчерашней ротации не получит никогда: сервер
 * раскладывает их только тем, кто был в списке получателей. Значит ключи надо забирать
 * отдельно и заранее, а не в момент, когда пришло сообщение и читать его уже нечем.
 *
 * **Почему хранятся все версии.** Сообщение зашифровано той версией, что была текущей в
 * момент отправки, и версия едет вместе с ним. Ротация происходит при каждом входе и
 * выходе участника — то есть переписка недельной давности почти наверняка лежит под старой
 * версией. Хранить только последнюю значит сделать историю нечитаемой при первом же новом
 * участнике.
 */
class SyncGroupKeys(
    private val wraps: GroupKeyWraps,
    private val unwrap: GroupKeyUnwrap,
    private val keys: GroupKeyBook,
) {

    /**
     * Забрать всё, чего у нас ещё нет.
     *
     * **Неоткрывшаяся обёртка не отменяет остальные.** Причины бывают разные: обёртка
     * испорчена, ключ устройства сменился, версия адресована не нам. Уронить из-за одной
     * весь разбор значило бы потерять и те ключи, что открылись, — и группа осталась бы
     * нечитаемой целиком вместо одного сообщения. Поэтому число неоткрытых возвращается
     * наружу: это то, о чём стоит знать, но не то, из-за чего стоит останавливаться.
     */
    suspend fun обновить(groupId: String): SyncKeysStep {
        val наша = keys.latestVersion(groupId) ?: 0
        return when (val ответ = wraps.mine(groupId, sinceVersion = наша)) {
            is GroupKeyWrapsStep.Wraps -> {
                var добавлено = 0
                var неоткрытых = 0
                for (обёртка in ответ.wraps) {
                    val ключ = unwrap.unwrap(обёртка.senderEphemeralPub, обёртка.wrapped)
                    if (ключ == null) {
                        неоткрытых++
                        continue
                    }
                    keys.put(groupId, обёртка.gkVersion, ключ)
                    добавлено++
                }
                SyncKeysStep.Synced(
                    добавлено = добавлено,
                    неоткрытых = неоткрытых,
                    // Версия группы может быть больше всего, что выдано нам: нас могли
                    // добавить после ротации. Отправляющему нужна она, а не наша:
                    // зашифровать своей старой значит написать в группу так, что новые
                    // участники не прочтут.
                    текущаяВерсия = ответ.currentVersion,
                )
            }
            is GroupKeyWrapsStep.Offline -> SyncKeysStep.Offline(ответ.retryAfterMs)
            is GroupKeyWrapsStep.Refused -> SyncKeysStep.Refused(ответ.reason)
        }
    }
}

// ── порты ───────────────────────────────────────────────────────────────────

/** Порт к обёрткам ключа на сервере. Реализуется `core-network`. */
interface GroupKeyWraps {
    /** @param sinceVersion какие версии у нас уже есть; ноль — «дайте всё». */
    suspend fun mine(groupId: String, sinceVersion: Int): GroupKeyWrapsStep
}

/**
 * Порт разворачивания обёртки. Реализуется `core-encryption`.
 *
 * `null` — не открылась. Домен не должен знать, чем именно она не открылась: различие
 * между испорченными байтами и чужим ключом ничего не меняет в том, что делать дальше.
 */
interface GroupKeyUnwrap {
    fun unwrap(senderEphemeralPub: ByteArray, wrapped: ByteArray): ByteArray?
}

/**
 * Порт хранения ключей. Реализуется `core-database`.
 *
 * Ключ приходит и уходит **открытыми байтами**: закрывать его перед записью — работа
 * хранилища, у которого есть шифр покоя, а не домена, который про шифры не знает. Так же
 * устроено тело сообщения.
 */
interface GroupKeyBook {
    fun put(groupId: String, version: Int, key: ByteArray)

    /** Ключ названной версии: им открывается сообщение, назвавшее эту версию. */
    fun key(groupId: String, version: Int): ByteArray?

    /** Самая свежая известная версия. `null` — ключей нет вовсе, писать в группу нечем. */
    fun latestVersion(groupId: String): Int?

    /** Все известные версии по возрастанию. */
    fun versions(groupId: String): List<Int>

    /**
     * Отметить отправку под версией и вернуть новое число.
     *
     * Счётчик привязан к ключу, а не к группе: он ограничивает объём, который раскроется
     * при утечке ЭТОЙ версии. Ротация заводит новую строку — счёт начинается заново сам,
     * без отдельного обнуления.
     */
    fun отметитьОтправку(groupId: String, version: Int): Int

    /** Забыть группу: ключи уходят вместе с её сообщениями. */
    fun forget(groupId: String)
}

/** Обёртка ключа, как её отдал сервер. */
class WrappedGroupKeyInfo(
    val gkVersion: Int,
    /** Эфемерный открытый ключ ротации: без него обёртку не развернуть. */
    val senderEphemeralPub: ByteArray,
    val wrapped: ByteArray,
)

// ── исходы ──────────────────────────────────────────────────────────────────

sealed interface GroupKeyWrapsStep {
    data class Wraps(val wraps: List<WrappedGroupKeyInfo>, val currentVersion: Int) : GroupKeyWrapsStep
    data class Offline(val retryAfterMs: Long) : GroupKeyWrapsStep
    data class Refused(val reason: String) : GroupKeyWrapsStep
}

sealed interface SyncKeysStep {
    /**
     * @param неоткрытых обёрток, которые не развернулись. Не ошибка сама по себе, но
     *   растущее число здесь означает, что с ключом устройства что-то не так.
     */
    data class Synced(val добавлено: Int, val неоткрытых: Int, val текущаяВерсия: Int) : SyncKeysStep

    data class Offline(val retryAfterMs: Long) : SyncKeysStep
    data class Refused(val reason: String) : SyncKeysStep
}
