package io.tima.crypto

/**
 * Метаданные конверта (plaintext-часть, `Metadata` из envelope.proto).
 * Единицы и типы зеркалируют proto: uint64 → [ULong], int64 → [Long].
 */
data class EnvelopeMeta(
    val messageId: ULong,
    val chatId: String,
    val senderId: String,
    val senderDevice: String,
    val kind: Int,
    val createdAtUnixMs: Long,
    val replyTo: ULong = 0u,
)

/**
 * Сборка подписываемого preimage — schema/proto/README.md §canonical_bytes.
 *
 * Подпись Ed25519 берётся не от protobuf-сериализации (она не детерминирована между
 * реализациями), а от явной конкатенации с длинными префиксами. Все целые — little-endian;
 * строки — UTF-8. Подписываются sha256-хэши ciphertext-блобов, не сами блобы.
 * `wrapped_keys[]` в preimage не входят (per-recipient, план Б): их целостность обеспечивает
 * MAC SecretBox подписанного `encrypted_payload`.
 *
 * Порядок и состав полей ФИКСИРОВАНЫ. Любое изменение = новый format_version
 * + новый тест-вектор (`schema/test-vectors/vectors.json` → `canonical_bytes`).
 */
/**
 * Подписываемые поля сообщения группы (schema/proto/README.md §group_message_canonical_bytes).
 * `message_id` не входит — его назначает сервер при приёме. [gkVersion] 0 = публичная
 * группа (plaintext payload).
 */
data class GroupMessageMeta(
    val groupId: String,
    val senderId: String,
    val senderDevice: String,
    val kind: Int,
    val createdAtUnixMs: Long,
    val threadRoot: ULong = 0u,
    val replyTo: ULong = 0u,
    val gkVersion: Int = 0,
)

object CanonicalBytes {
    const val FORMAT_VERSION = 1

    /** Доменная метка preimage сообщения группы; несёт версию раскладки. */
    const val GROUP_MESSAGE_DOMAIN = "tima.group_message.v1"

    val EMPTY: ByteArray = ByteArray(0)

    /**
     * @param escrowBytes конкатенация `escrow.mlkem_ct ⊕ escrow.wrapped_message_key`
     * @param ratchetEnvelope [EMPTY], если ratchet-слоя нет (тогда хэшируются пустые байты)
     */
    fun build(
        meta: EnvelopeMeta,
        encryptedPayload: ByteArray,
        escrowBytes: ByteArray,
        senderEphemeralPub: ByteArray,
        ratchetEnvelope: ByteArray = EMPTY,
        formatVersion: Int = FORMAT_VERSION,
    ): ByteArray =
        u32le(formatVersion) +
            u64le(meta.messageId.toLong()) +
            lp(meta.chatId) +
            lp(meta.senderId) +
            lp(meta.senderDevice) +
            u32le(meta.kind) +
            u64le(meta.createdAtUnixMs) +
            u64le(meta.replyTo.toLong()) +
            sha256(encryptedPayload) +
            sha256(escrowBytes) +
            sha256(senderEphemeralPub) +
            sha256(ratchetEnvelope)

    /**
     * Preimage подписи сообщения группы — schema/proto/README.md
     * §group_message_canonical_bytes, KAT-вектор `group_message_canonical`.
     * Payload — тот же конвейер MessageBody: private-группа
     * `SecretBox(zstd(protobuf(body)), GK)`, публичная — plaintext protobuf.
     */
    fun buildGroupMessage(meta: GroupMessageMeta, payload: ByteArray): ByteArray =
        lp(GROUP_MESSAGE_DOMAIN) +
            lp(meta.groupId) +
            lp(meta.senderId) +
            lp(meta.senderDevice) +
            u32le(meta.kind) +
            u64le(meta.createdAtUnixMs) +
            u64le(meta.threadRoot.toLong()) +
            u64le(meta.replyTo.toLong()) +
            u32le(meta.gkVersion) +
            sha256(payload)
}
