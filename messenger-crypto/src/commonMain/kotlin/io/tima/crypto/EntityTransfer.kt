package io.tima.crypto

/**
 * Акт передачи владения сущностью Социума (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §6, СЦ11).
 *
 * Его подписывают владелец — ключом личности последнего звена владения — и принимающий — своим ключом личности.
 * Номер звена и срок в байтах: акт нельзя повторить и нельзя придержать. Строка в строку как
 * `server/internal/crypto/device_trust.go` (`EntityTransferBytes`); вектор `entity_transfer`.
 */
object EntityTransfer {
    fun bytes(kind: String, entityId: String, linkNo: Int, fromUserId: String, toUserId: String, expiresUnix: Long): ByteArray =
        "tima.entity-transfer.v1|$kind|$entityId|$linkNo|$fromUserId|$toUserId|$expiresUnix".encodeToByteArray()
}
