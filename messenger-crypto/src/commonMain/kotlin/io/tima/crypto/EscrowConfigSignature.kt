package io.tima.crypto

import io.kodium.Kodium

/**
 * Подпись конфига анклава (ПЛАН-РЕФАКТОРИНГА.md Р2; doc/adr/0012-escrow-key-epochs.md
 * «Что осталось»).
 *
 * Без неё компрометация бэкенда позволяет подменить `public_key`, который клиент
 * получает через ручки `/api/v1/escrow`: клиент зашифровал бы сообщение на чужой ключ
 * в обход анклава и всего механизма контролируемого доступа (порог долей, аудит,
 * срок уничтожения). Анклав подписывает ответ приватным ключом Ed25519, который
 * живёт только у него; бэкенд лишь пересылает подпись. Проверка здесь имеет смысл
 * только с ЗАРАНЕЕ известным публичным ключом анклава — см. `EscrowTrust` в клиенте.
 *
 * Раскладка байт зеркалит `server/internal/escrow/signing.go`
 * (`KeyMetaSigningBytes` / `PubkeySigningBytes`) байт-в-байт — расхождение
 * означает, что подпись, сделанная сервером, не пройдёт проверку у клиента.
 */
data class EscrowKeyMeta(
    val id: Long,
    val region: String,
    val epoch: String,
    val chatId: String,
    val publicKey: ByteArray, // ML-KEM-768, уже декодирован из base64url
    val validFromUnixMs: Long,
    val validToUnixMs: Long,
    val destroyAtUnixMs: Long,
)

object EscrowConfigSignature {

    /** Подписываемые байты KeyMeta (`/v1/key`, `/v1/keys/{id}` анклава). */
    fun keyMetaSigningBytes(meta: EscrowKeyMeta): ByteArray =
        u32le(meta.id.toInt()) +
            lp(meta.region) +
            lp(meta.epoch) +
            lp(meta.chatId) +
            meta.publicKey +
            u64le(meta.validFromUnixMs) +
            u64le(meta.validToUnixMs) +
            u64le(meta.destroyAtUnixMs)

    /** Подписываемые байты легаси-ответа `GET /v1/pubkey`. */
    fun pubkeySigningBytes(version: Int, publicKey: ByteArray): ByteArray =
        u32le(version) + publicKey

    /** @param enclaveSigningPub публичный ключ подписи анклава (Ed25519, 32 байта) */
    fun verify(enclaveSigningPub: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        Kodium.verifyDetached(enclaveSigningPub, message, signature)
}
