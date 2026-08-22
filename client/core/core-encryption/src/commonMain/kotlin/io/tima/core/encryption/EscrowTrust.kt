package io.tima.core.encryption

import io.tima.crypto.EscrowConfigSignature
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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
 * Ключ печатает анклав при **каждом** старте одной строкой
 * (`escrow-stub: ключ подписи конфига (Ed25519, …)`), и он же зашивается сюда — см.
 * `doc_mig/ПЕРЕУСТАНОВКА-СЕРВЕРА.md § «Ключ подписи анклава»`.
 *
 * Отказ здесь закрытый в обе стороны. `null` (ключ не выдан) означает, что не
 * принимается ни один ключ эпохи: подставить «что-нибудь, пока работало» значило бы
 * завести проверку, которая ничего не проверяет. Зашитый чужой ключ означает, что
 * подпись не сойдётся и отправка встанет — что и требуется: продолжать с подменённым
 * ключом эпохи хуже, чем не отправить.
 */
object EscrowTrust {

    /**
     * Ключ анклава **стенда** `пацак.рф`, взятый из его журнала 2026-08-22.
     *
     * Не секрет: он публичный, и печатается анклавом при каждом старте именно для того,
     * чтобы оператор мог сверить его с зашитым в сборке. Сверять надо: расхождение
     * означает либо подменённый бэкенд, либо переустановленный анклав — и в обоих
     * случаях отправка обязана встать, а не продолжиться.
     *
     * **У боевого выпуска будет свой анклав и свой ключ.** Эта константа привязывает
     * сборку к стенду, и при выпуске её надо заменить осознанно, а не унести с собой.
     *
     * Проверено прогоном против живого стенда: подпись ключа эпохи сошлась, сообщение
     * ушло и было прочитано вторым устройством (`standRun`).
     */
    const val STAND_ENCLAVE_KEY: String = "3QK9NksUYmcrlMJd6pp8J_hNhH6CudAUFatxwdBOm9A"

    /**
     * Зашитый ключ подписи анклава, 32 байта.
     *
     * `null` означало бы «ключ не выдан», и тогда ни один ключ эпохи не принимается —
     * отказ закрытый. Сейчас ключ есть, и отказ закрытый работает в другую сторону: с
     * чужим анклавом подпись не сойдётся, и отправка встанет.
     */
    val enclaveSigningPub: ByteArray? = decodeKey(STAND_ENCLAVE_KEY)

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeKey(base64url: String): ByteArray? = runCatching {
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(base64url)
    }.getOrNull()?.takeIf { it.size == 32 }
}
