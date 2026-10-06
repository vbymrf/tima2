package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.encryption.DeviceEpochKeys
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.network.DeviceKeyRecord
import io.tima.core.network.KeysApi
import io.tima.core.network.TrustCallResult
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Ключи шифрования устройства на эпоху (ПЛАН-(ПС) ПС3, Р2, Р3) — прямая секретность от утечки
 * ключа устройства.
 *
 * Раз в эпоху депозитария (календарный месяц UTC) устройство заводит новую пару X25519, держит
 * закрытую часть в хранилище платформы и публикует открытую, подписанную своим ключом подписи.
 * Отправитель заворачивает под ключ эпохи; это устройство пробует свои ключи от нового к старому.
 *
 * ── ПОРЯДОК, КОТОРЫЙ ВАЖНЕЕ КОДА ────────────────────────────────────────────
 *
 * 1. **Ключ эпохи заводится ДО сборки** ([prepare]): устройство держит его с первой секунды, и
 *    обёртка под него, пришедшая сразу после публикации, открывается.
 * 2. **Публикация — после**, в фоне, с повтором при следующем запуске ([publish]).
 * 3. **Уничтожаются ключи старше прошлой эпохи и только после того, как текущий опубликован**:
 *    пока сервер раздаёт старый ключ, обёртки под него ещё идут. Прошлая эпоха держится всю
 *    текущую — столько живут обёртки под неё (ПС2: конец месяца сообщения плюс запас).
 */
class EpochKeyRing(
    private val secrets: EpochKeySecrets,
    private val now: () -> Long,
) {
    /** Текущая эпоха — «2026-10». Тот же формат, что у сервера и депозитария. */
    fun currentEpoch(): String = epochAt(now())

    private fun epochAt(ms: Long): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC)
        return t.year.toString() + "-" + t.monthNumber.toString().padStart(2, '0')
    }

    /** Прошлая эпоха относительно [epoch] — «2026-09» для «2026-10». */
    private fun previous(epoch: String): String {
        val (y, m) = epoch.split('-').map { it.toInt() }
        return if (m == 1) "${y - 1}-12" else "$y-${(m - 1).toString().padStart(2, '0')}"
    }

    /**
     * Завести ключ текущей эпохи, если его нет, и отдать секреты всех хранимых — от нового к
     * старому, для [DeviceIdentity.withEpochKeys]. Хранилище отказало — пустой список: тогда
     * устройство живёт на основном ключе, как до ПС3.
     */
    fun prepare(): List<ByteArray> = runCatching {
        val epoch = currentEpoch()
        if (secrets.get(epoch) == null) {
            secrets.put(epoch, DeviceEpochKeys.generate())
            Journal.note(LogCode.DEVICE_TRUST, "заведён ключ шифрования на эпоху", "эпоха" to epoch)
        }
        secrets.epochs().sortedDescending().mapNotNull { secrets.get(it) }
    }.getOrElse {
        Journal.trouble(LogCode.DEVICE_TRUST, "ключ эпохи не заведён — шифруют под основной", "причина" to (it.message ?: "?"))
        emptyList()
    }

    /**
     * Опубликовать ключ текущей эпохи и, если вышло, уничтожить ключи старше прошлой эпохи.
     *
     * @return опубликован ли ключ текущей эпохи.
     */
    suspend fun publish(keys: KeysApi, identity: DeviceIdentity, deviceId: String): Boolean {
        val epoch = currentEpoch()
        val secret = runCatching { secrets.get(epoch) }.getOrNull() ?: return false
        val pub = DeviceEpochKeys.publicOf(secret)
        val signature = DeviceEpochKeys.sign(identity, deviceId, epoch, pub) ?: return false
        val published = when (val answer = keys.publishEpochKey(epoch, pub, signature)) {
            TrustCallResult.Done -> true
            is TrustCallResult.Refused -> {
                Journal.trouble(LogCode.DEVICE_TRUST, "ключ эпохи не принят", "эпоха" to epoch, "код" to answer.code)
                false
            }
            is TrustCallResult.Offline -> false
        }
        if (!published) return false
        val keep = setOf(epoch, previous(epoch))
        val dropped = runCatching { secrets.epochs().filter { it !in keep }.onEach { secrets.remove(it) } }.getOrDefault(emptyList())
        Journal.note(LogCode.DEVICE_TRUST, "ключ эпохи опубликован", "эпоха" to epoch, "уничтожено" to dropped.size)
        return true
    }
}

/**
 * Под какой ключ заворачивать для устройства (ПС3): под ключ эпохи, если его подпись сходится с
 * ключом подписи устройства — тем, что прошёл проверку заверения; иначе под основной.
 */
fun DeviceKeyRecord.wrapPub(): ByteArray {
    val e = epochKey ?: return encryptionPub
    return if (DeviceEpochKeys.valid(signingPub, deviceId, e.epoch, e.encryptionPub, e.signature)) e.encryptionPub else encryptionPub
}
