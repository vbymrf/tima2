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
    /**
     * v1 — исходная раскладка.
     * v2 — добавлен `key_commitment` ХВОСТОМ preimage (ADR-0013). Раскладка v1
     *      осталась байт-в-байт прежней, поэтому старые векторы сходятся.
     */
    const val FORMAT_VERSION = 2

    /** Версия, которую приёмная сторона обязана принимать на время перехода. */
    const val FORMAT_VERSION_LEGACY = 1

    /** Длина обязательства по ключу. */
    const val COMMITMENT_SIZE = 32

    /** Доменная метка деривации обязательства; отличается от escrow-инфо намеренно. */
    private const val COMMITMENT_INFO = "tima/commit/v1"

    /**
     * Обязательство по ключу сообщения.
     *
     * `message_key` едет получателю тремя независимыми путями — ratchet, wrapped_key
     * и escrow_blob, — и ни один не доказывает, что остальные ведут к тому же ключу.
     * Poly1305 в SecretBox не является key-committing: один шифртекст может валидно
     * раскрыться под ДВУМЯ разными ключами. Без обязательства отправитель мог бы
     * собрать сообщение так, что по ордеру расшифруется один текст, а у получателя
     * на экране будет другой.
     *
     * Значение входит в подписываемые байты, поэтому подменить его нельзя. Получатель
     * на ЛЮБОМ пути пересчитывает его из добытого ключа и сверяет.
     */
    fun keyCommitment(messageKey: ByteArray): ByteArray =
        io.kodium.ratchet.HKDF.deriveSecrets(
            salt = null,
            ikm = messageKey,
            info = COMMITMENT_INFO.encodeToByteArray(),
            length = COMMITMENT_SIZE,
        )

    /**
     * Сверка обязательства в постоянное время. Расхождение означает, что путь
     * доставки ключа привёл не к тому ключу — сообщение принимать нельзя.
     */
    fun commitmentMatches(messageKey: ByteArray, expected: ByteArray?): Boolean {
        if (expected == null || expected.size != COMMITMENT_SIZE) return false
        val got = keyCommitment(messageKey)
        var diff = 0
        for (i in got.indices) diff = diff or (got[i].toInt() xor expected[i].toInt())
        return diff == 0
    }

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
        keyCommitment: ByteArray = EMPTY,
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
            sha256(ratchetEnvelope) +
            // Хвост версии 2. В версии 1 добавлять нечего — раскладка обязана
            // остаться прежней, иначе старые подписи перестанут проверяться.
            (if (formatVersion >= FORMAT_VERSION) keyCommitment else EMPTY)

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
