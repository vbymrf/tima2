package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.domain.chat.ShareStep
import io.tima.core.encryption.GroupKeyUnwrapOverKodium
import io.tima.core.encryption.GroupKeyWrapOverKodium
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.database.SqlGroupKeys
import io.tima.core.network.EventStreamProtocol
import io.tima.core.network.GroupKeyRecoveryOverHttp
import io.tima.core.network.GroupKeyWrapsOverHttp
import io.tima.core.network.GroupKeysResult
import io.tima.domain.chat.RotateStep
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import io.tima.domain.chat.ShareGroupKeys
import io.tima.domain.chat.HealGroupKey
import io.tima.domain.chat.RotationReason
import io.tima.domain.chat.SyncGroupKeys

/**
 * Кадры живого канала про групповые ключи: сходить за своим, отдать чужому, ротировать.
 *
 * ── ПОЧЕМУ ОТДЕЛЬНО ОТ ПРИЁМНИКА ────────────────────────────────────────────
 *
 * Приёмник держит канал и разбирает сообщения. Ключи ехали тем же каналом, и он же
 * их и собирал: три подсистемы прямо в его конструкторе — сверка, раздача, ротация.
 * Смысла в этом не было: канал только приносит кадры, а выполняет их другая работа,
 * которой нужны escrow, крипта, сеть и хранилище разом.
 *
 * Теперь приёмник получает готовый оркестр и знает про него одно: «вот кадр».
 *
 * **Ни один из трёх исходов не роняет канал.** Ключи — работа рядом с доставкой, а не
 * вместо неё: не удалось сходить за обёртками — сообщения всё равно должны идти.
 * Поэтому провал остаётся строкой диагностики, а не летит наверх.
 */
class GroupKeyOrchestrator(
    private val environment: Environment,
    private val network: GroupPorts,
    identity: DeviceIdentity,
    private val msNow: () -> Long,
) {
    private val groupKeys = SqlGroupKeys(environment.db, environment.cipher, onPut = { g, v, k -> environment.onGroupKeyStored?.invoke(g, v, k) })

    private val sync = SyncGroupKeys(
        wraps = GroupKeyWrapsOverHttp(network.groupKeys),
        unwrap = GroupKeyUnwrapOverKodium(identity),
        keys = groupKeys,
    )

    /**
     * Лечение группы без ключа при открытии — забрать обёртки, а если ключа не было ни у
     * кого, выпустить первый. Собирается здесь: те же `sync` и `rotation`.
     */
    val heal: HealGroupKey get() = HealGroupKey(sync = sync, rotator = rotation)

    private val sharing = ShareGroupKeys(
        keys = groupKeys,
        wrap = GroupKeyWrapOverKodium,
        upload = GroupKeyRecoveryOverHttp(network.groupKeyRecovery),
    )

    private val rotation = GroupKeyRotation(
        groups = network.groups,
        deviceKeys = network.keys,
        escrow = network.escrow,
        groupKeys = network.groupKeys,
        book = groupKeys,
        msNow = msNow,
        trust = environment.trustGate,
    )

    /**
     * Новое устройство человека получило доверие по QR — отдать ему ключи всех групп, что
     * есть здесь (ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ8).
     *
     * **Служебная группа аккаунта — в первую очередь.** Её ключом зашифрована копия книги,
     * а ротаций у неё не бывает (в ней нет сообщений), и просьбу о ключе никто не шлёт:
     * без этого второе устройство копию не получало никогда (ПК 2026-09-30: «ключа
     * служебной группы нет» при каждом запуске). Путь тот же, что у ответа на просьбу
     * участника (`ShareGroupKeys`): сервер принимает обёртки без просьбы, если получатель —
     * устройство участника, а своё устройство им и является.
     *
     * @return сколько групп отдано.
     */
    suspend fun handOver(deviceId: String, encryptionPub: ByteArray): Int {
        var shared = 0
        var failed = 0
        for (groupId in groupKeys.groupsWithKeys()) {
            when (sharing.share(groupId, deviceId, encryptionPub, groupKeys.versions(groupId))) {
                is ShareStep.Shared -> shared++
                ShareStep.NothingToShare -> Unit
                else -> failed++
            }
        }
        if (failed > 0) {
            Journal.trouble(LogCode.NET_CHANNEL, "ключи новому устройству: отданы не все", "отдано" to shared, "нет" to failed)
        } else {
            Journal.note(LogCode.NET_CHANNEL, "ключи новому устройству отданы", "групп" to shared)
        }
        return shared
    }

    /** Ключи этого устройства: их читает разбор сообщений группы. */
    val keys: SqlGroupKeys get() = groupKeys

    /**
     * Ротировать ключ группы. Тот же путь, что по событию сервера.
     *
     * Нужен отправке: перед зашифрованной посылкой ключ обязан быть привязан к текущей
     * эпохе (ADR-0017 §2), иначе сообщение окажется невосстановимым по ордеру в день
     * отправки.
     */
    suspend fun rotate(groupId: String, reason: RotationReason = RotationReason.Epoch): Boolean =
        rotation.rotate(groupId, reason) is RotateStep.Rotated

    /**
     * Устарела ли версия ключа группы: эпоха её выпуска ≠ текущая.
     *
     * Спрашивается у сервера: эпохи знает он (`escrow_epoch` в `GET /groups/{id}/keys`), а
     * клиент — только версии. Молчание сервера про эпоху трактуется как «не устарела»:
     * лишняя ротация стоит фан-аута обёрток по всем устройствам, а пропущенная —
     * восстановимости по ордеру, и второе чинится ближайшим событием сервера.
     */
    suspend fun keyStale(groupId: String): Boolean {
        val answer = network.groupKeys.mine(groupId)
        val epoch = (answer as? GroupKeysResult.Keys)?.escrowEpoch.orEmpty()
        if (epoch.isEmpty()) return false
        return epoch != currentEpoch()
    }

    /**
     * Открытый ключ просящего устройства — из списка устройств его владельца, пропущенного через
     * проверку заверения (Р57, как в ДУ3); `null` — устройство не прошло или не нашлось. В
     * «записывать» проверка пишет в журнал и пропускает, в «требовать» — отказывает. Ключ,
     * названный в событии, обязан совпасть с проверенным: иначе сервер подменил адресата.
     */
    private suspend fun trustedRequester(decision: EventStreamProtocol.Decision.ShareKeys): ByteArray? {
        val user = decision.requesterUser.ifEmpty { return null }
        val answer = network.keys.devicesOf(user) as? io.tima.core.network.DeviceKeysResult.Devices ?: return null
        val device = environment.trustGate.admit(user, answer).firstOrNull { it.deviceId == decision.requesterDevice }
            ?: return null
        if (!device.encryptionPub.contentEquals(decision.requesterEncryptionPub)) return null
        return device.encryptionPub
    }

    /** Текущая эпоха escrow — «2026-09». Тот же формат, что у сервера. */
    private fun currentEpoch(): String {
        val now = Instant.fromEpochMilliseconds(msNow()).toLocalDateTime(TimeZone.UTC)
        return now.year.toString() + "-" + now.monthNumber.toString().padStart(2, '0')
    }

    /**
     * Обработать кадр. Возвращает строку для диагностики — ту же, что раньше писал
     * приёмник: по ней на живом прогоне видно, что происходило с ключами.
     */
    suspend fun handle(decision: EventStreamProtocol.Decision): String? = runCatching {
        when (decision) {
            // Ротация и приезд обёрток означают одно: сходить за тем, чего у нас нет.
            is EventStreamProtocol.Decision.KeysArrived ->
                "ключи группы: ${sync.refresh(decision.groupId)}"

            // Просят у нас — значит, у нас эти версии есть. Молчание оставит человека
            // ждать вечно: другого способа получить историю до своего прихода у него нет.
            is EventStreamProtocol.Decision.ShareKeys -> {
                // Р57: заверение просящего проверяет отдающий, как в ДУ3 — сервер не якорь
                // доверия. Ключ заворачивается под ключ из проверенного списка.
                val pub = trustedRequester(decision)
                if (pub == null) "не отдали ключи: просящее устройство не прошло проверку заверения"
                else "отдали ключи: " + sharing.share(
                    groupId = decision.groupId,
                    requesterDevice = decision.requesterDevice,
                    requesterEncryptionPub = pub,
                    versions = decision.versions,
                )
            }

            // Сервер сам ротировать не может — ключа он не видит (ADR-0017 §3).
            is EventStreamProtocol.Decision.RotationNeeded ->
                "ротация по просьбе сервера (${decision.reason}): " +
                    rotation.rotate(decision.groupId, RotationReason.fromWire(decision.reason))

            else -> null
        }
    }.getOrElse { "кадр про ключи не обработан: ${it.message}" }
}
