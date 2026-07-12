package io.tima.crypto

import io.kodium.Kodium
import io.kodium.ratchet.HKDF

/**
 * Escrow-блоб: `mlkem_ct(1088) ‖ SecretBox(message_key, hkdf(mlkem_shared))`
 * (envelope.proto → EscrowBlob; поля хранятся раздельно, конкатенация — для canonical_bytes).
 */
class EscrowBlob(
    val mlkemCt: ByteArray,
    val wrappedMessageKey: ByteArray,
    val escrowKeyVersion: Int,
) {
    /** `mlkem_ct ⊕ wrapped_message_key` — вход sha256 в canonical_bytes. */
    fun canonicalConcat(): ByteArray = mlkemCt + wrappedMessageKey
}

/**
 * Слой 2 — controlled escrow (ADR-0004; crypto-protocol.md §3.2, §6).
 *
 * Оборачивает `message_key` (или GK/media_key) на публичный ML-KEM-768 ключ escrow.
 * Приватный ключ существует только в HSM/анклаве; [unwrap] здесь — для stub-анклава MVP
 * и тестов round-trip, на клиенте он не вызывается.
 *
 * Нормативная деривация ключа обёртки (контракт с HSM-стороной):
 * `wrap_key = HKDF-SHA256(ikm = mlkem_shared, salt = пусто, info = "tima/escrow/v1", len = 32)`.
 */
class EscrowModule(
    private val escrowPublicKey: ByteArray,
    private val escrowKeyVersion: Int,
) {
    init {
        require(escrowPublicKey.size == Mlkem768.PublicKeySize) {
            "Escrow public key должен быть ${Mlkem768.PublicKeySize} байт (ML-KEM-768)"
        }
    }

    /** Инкапсулирует shared на escrow-ключ и оборачивает им `message_key`. */
    fun wrap(messageKey: ByteArray): Result<EscrowBlob> = runCatching {
        // ВНИМАНИЕ порядок: encapsulate возвращает Pair(sharedSecret, ciphertext)
        val (shared, ct) = Mlkem768.encapsulate(escrowPublicKey)
        check(ct.size == Mlkem768.CiphertextSize) { "ML-KEM ct: ожидалось ${Mlkem768.CiphertextSize} байт, получено ${ct.size}" }
        val wrapped = Kodium.encryptSymmetric(deriveWrapKey(shared), messageKey).getOrThrow()
        EscrowBlob(ct, wrapped, escrowKeyVersion)
    }

    companion object {
        private val HKDF_INFO = "tima/escrow/v1".encodeToByteArray()

        internal fun deriveWrapKey(mlkemShared: ByteArray): ByteArray =
            HKDF.deriveSecrets(salt = null, ikm = mlkemShared, info = HKDF_INFO, length = 32)

        /** Обратная операция для HSM/stub-анклава: decapsulate + разворачивание `message_key`. */
        fun unwrap(blob: EscrowBlob, escrowSecretKey: ByteArray): Result<ByteArray> = runCatching {
            val shared = Mlkem768.decapsulate(blob.mlkemCt, escrowSecretKey)
                ?: throw IllegalStateException("ML-KEM decapsulate вернул null (ct/sk повреждены)")
            Kodium.decryptSymmetric(deriveWrapKey(shared), blob.wrappedMessageKey).getOrThrow()
        }
    }
}
