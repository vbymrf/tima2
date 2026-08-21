package io.tima.crypto

import io.kodium.Kodium
import io.kodium.KodiumPrivateKey

/**
 * Результат ротации группового ключа (crypto-protocol.md §4).
 *
 * `groupKey` остаётся у клиента-инициатора (и уходит участникам только внутри wrapped),
 * на сервер передаются wrapped-обёртки + escrow + `senderEphemeralPub` (нужен участникам
 * для разворачивания — как в личном конверте).
 */
class GroupKeyRotation(
    val gkVersion: Int,
    val groupKey: ByteArray,
    val senderEphemeralPub: ByteArray,
    val wrappedKeys: Map<String, ByteArray>,
    val escrow: EscrowBlob,
)

/**
 * Ротация группового ключа (Sender Keys / GK; crypto-protocol.md §4).
 *
 * GK генерирует клиент-инициатор (админ-устройство), не сервер. Триггеры ротации
 * (каждые 100 сообщений, вход/выход участника, компрометация устройства) — политика
 * прикладного слоя; менеджер выполняет саму ротацию:
 *
 * - `payload = SecretBox(zstd(protobuf(body)), GK)` — шифрование сообщений группы
 *   тем же [EnvelopeCipher], ключом выступает GK;
 * - `wrapped_GK = Box(ephemeral, device_identity, GK)` — на каждое устройство участника;
 *   одна эфемерная пара на ротацию, её публичная часть — в [GroupKeyRotation.senderEphemeralPub];
 * - `escrow_blob` — ОДИН на версию GK (не на сообщение).
 *
 * Устройствам исключённых участников обёртки просто не создаются: список [rotate] `devices` —
 * единственный источник получателей (post-compromise security обеспечивает сам вызов ротации).
 */
class GroupKeyManager(private val escrowModule: EscrowModule) {

    /**
     * @param currentVersion текущая версия GK (0, если группа новая)
     * @param devices активные устройства ВСЕХ участников после события
     *   (вход/выход уже применён к списку)
     */
    fun rotate(currentVersion: Int, devices: List<DeviceAddress>): Result<GroupKeyRotation> = runCatching {
        require(devices.isNotEmpty()) { "Ротация без устройств-получателей бессмысленна" }
        val gk = Kodium.generateHighEntropyKey()
        val ephemeral: KodiumPrivateKey = Kodium.generateKeyPair()
        GroupKeyRotation(
            gkVersion = currentVersion + 1,
            groupKey = gk,
            senderEphemeralPub = ephemeral.getPublicKey().encryptionKey,
            wrappedKeys = devices.associate { device ->
                device.deviceId to WrappedKeyService.wrap(ephemeral, device.identityEncryptionPub, gk).getOrThrow()
            },
            escrow = escrowModule.wrap(gk).getOrThrow(),
        )
    }

    companion object {
        /** Участник разворачивает свою wrapped_GK ключом устройства. */
        fun unwrapGroupKey(
            deviceKey: KodiumPrivateKey,
            senderEphemeralPub: ByteArray,
            wrappedGk: ByteArray,
        ): Result<ByteArray> = WrappedKeyService.unwrap(deviceKey, senderEphemeralPub, wrappedGk)
    }
}
