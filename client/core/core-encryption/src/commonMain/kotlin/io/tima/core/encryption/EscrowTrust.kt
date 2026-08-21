package io.tima.core.encryption

import io.tima.crypto.EscrowConfigSignature
import io.tima.crypto.EscrowKeyMeta

/**
 * Проверка ключа эпохи escrow подписью анклава (Р2, ADR-0012).
 *
 * **Зачем это вообще.** Без проверки компрометация бэкенда позволяет подменить
 * `public_key`, который клиент получает ручкой `/api/v1/escrow/key`: клиент зашифровал
 * бы сообщение на чужой ключ — **в обход анклава и всего механизма контролируемого
 * доступа**: порога долей, журнала аудита, срока уничтожения. Подпись делает анклав
 * ключом, который живёт только у него; бэкенд её лишь пересылает.
 *
 * Отсюда же следует, что проверка имеет смысл **только с заранее известным** ключом
 * анклава ([EscrowTrust]). Проверять подписью, взятой оттуда же, откуда и данные, —
 * значит не проверять.
 *
 * Принимаются **поля по отдельности, а не объект сетевого слоя**: раскладка
 * подписываемых байт зеркалит `server/internal/escrow/signing.go` байт-в-байт, и
 * знание о ней живёт здесь. Сетевой слой достаёт поля, этот — решает, верить ли им.
 */
object EscrowKeyVerifier {

    /** ML-KEM-768. Размер задан алгоритмом, а не нами. */
    const val PUBLIC_KEY_BYTES: Int = 1184

    /**
     * @param enclaveSigningPub заранее известный ключ подписи анклава (Ed25519, 32 байта).
     * @param nowMs текущее время: ключ вне своего окна к шифрованию не годится.
     * @return ключ эпохи, годный для запечатывания, либо причина отказа.
     */
    fun verify(
        enclaveSigningPub: ByteArray,
        id: Long,
        region: String,
        chatId: String,
        epoch: String,
        publicKey: ByteArray,
        signature: ByteArray,
        validFromMs: Long,
        validToMs: Long,
        destroyAtMs: Long,
        nowMs: Long,
    ): Result<EscrowEpochKey> {
        if (enclaveSigningPub.size != SIGNING_KEY_BYTES) {
            return отказ("ключ подписи анклава не $SIGNING_KEY_BYTES байт: ${enclaveSigningPub.size}")
        }
        if (publicKey.size != PUBLIC_KEY_BYTES) {
            return отказ("ключ эпохи не $PUBLIC_KEY_BYTES байт: ${publicKey.size}")
        }
        if (signature.size != SIGNATURE_BYTES) {
            return отказ("подпись не $SIGNATURE_BYTES байт: ${signature.size}")
        }

        val meta = EscrowKeyMeta(
            id = id,
            region = region,
            epoch = epoch,
            chatId = chatId,
            publicKey = publicKey,
            validFromUnixMs = validFromMs,
            validToUnixMs = validToMs,
            destroyAtUnixMs = destroyAtMs,
        )
        if (!EscrowConfigSignature.verify(
                enclaveSigningPub,
                EscrowConfigSignature.keyMetaSigningBytes(meta),
                signature,
            )
        ) {
            return отказ("подпись анклава не сошлась: ключ подменён или это не наш анклав")
        }

        // Окно проверяется ПОСЛЕ подписи: сроки тоже подписаны, и судить о них до
        // проверки подписи значит судить по неподтверждённым числам.
        if (nowMs < validFromMs) {
            return отказ("ключ эпохи $epoch ещё не начал действовать")
        }
        if (nowMs >= validToMs) {
            // Запечатывать на истёкшую эпоху — значит отдать сообщение ключу, который
            // анклав уничтожит раньше срока хранения переписки.
            return отказ("ключ эпохи $epoch истёк")
        }

        return Result.success(EscrowEpochKey(publicKey = publicKey, version = id.toInt()))
    }

    private fun отказ(message: String): Result<EscrowEpochKey> =
        Result.failure(EscrowKeyRejected(message))

    private const val SIGNING_KEY_BYTES = 32
    private const val SIGNATURE_BYTES = 64
}

/** Ключ эпохи не принят. Отдельный тип: это не поломка кода, а недоверие к данным. */
class EscrowKeyRejected(message: String) : Exception(message)

/**
 * Заранее известный ключ подписи анклава.
 *
 * `null` означает «ключ не зашит», и это **состояние, а не заглушка**: пока его нет,
 * ни один ключ эпохи не принимается, то есть отправка невозможна. Отказ закрытый —
 * подставить сюда что-нибудь «пока работало» значило бы завести проверку, которая
 * ничего не проверяет, и снова шифровать на ключ, взятый на веру у сервера.
 *
 * Ключ печатает анклав при первом запуске одной строкой
 * (`escrow-stub: ключ подписи конфига (Ed25519, …)`), и он же зашивается в сборку —
 * см. `doc_mig/ПЕРЕУСТАНОВКА-СЕРВЕРА.md`. Значит порядок работ такой: выкатка стенда
 * → ключ из журнала анклава → сборка клиента, умеющая отправлять.
 */
object EscrowTrust {
    val enclaveSigningPub: ByteArray? = null
}
