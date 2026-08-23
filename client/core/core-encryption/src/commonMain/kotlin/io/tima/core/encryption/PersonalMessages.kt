package io.tima.core.encryption

import io.tima.crypto.DeviceAddress
import io.tima.crypto.EnvelopeMeta
import io.tima.crypto.EscrowModule
import io.tima.crypto.MessageContent
import io.tima.crypto.MessageContentCodec
import io.tima.crypto.MessageSerializer
import io.tima.crypto.PersonalMessageSealer

/**
 * Отправка и приём личного сообщения — один вызов вместо четырёх.
 *
 * **Что именно этот класс делает, кроме перекладывания вызовов.** Порядок шагов
 * нормативен: содержимое → тело (`zstd(protobuf)`) → конверт (шифрование, escrow,
 * обёртки ключей, подпись) → protobuf конверта. Перепутанный порядок даёт не ошибку
 * компиляции, а сообщение, которое получатель не откроет; собранный не там escrow —
 * сообщение, недоступное по ордеру. Поэтому порядок живёт в одном месте.
 *
 * **Escrow обязателен на каждом сообщении** (ADR-0004), поэтому ключ эпохи — параметр
 * конструктора, а не необязательный аргумент: собрать конверт без него нельзя,
 * и это должно быть видно по подписи, а не выясняться в рантайме.
 *
 * Чего здесь нет: сети, хранения, повторов. Это Outbox и `core-network` (К3).
 */
class PersonalMessages(escrowKey: EscrowEpochKey) {

    private val sealer = PersonalMessageSealer(
        EscrowModule(escrowPublicKey = escrowKey.publicKey, escrowKeyVersion = escrowKey.version),
    )

    /**
     * Собирает конверт, готовый к `POST /api/v1/messages`.
     *
     * @param recipients устройства получателя **и остальные свои** — ключ сообщения
     *   оборачивается на каждое. Без своих второе устройство не прочитает
     *   отправленное с первого.
     * @return байты protobuf-конверта. Обёртки ключей едут **внутри** него
     *   (`wrapped_keys`), сервер раскладывает их по устройствам сам.
     *
     * Заголовок `X-Client-Msg-Id` этот метод не формирует: дедупликация повторной
     * отправки — свойство очереди, а не конверта, и живёт в Outbox.
     */
    fun seal(
        content: MessageContent,
        meta: EnvelopeMeta,
        sender: DeviceIdentity,
        recipients: List<RecipientDevice>,
    ): Result<ByteArray> = runCatching {
        require(recipients.isNotEmpty()) { "нужен хотя бы один адресат обёртки ключа" }
        val body = MessageContentCodec.toBody(content)
        val payload = MessageSerializer.encodeBody(body)
        val sealed = sealer.seal(
            meta = meta,
            payloadPlaintext = payload,
            senderDeviceKey = sender.key,
            recipientDevices = recipients.map { DeviceAddress(it.deviceId, it.encryptionPublic) },
        ).getOrThrow()
        MessageSerializer.encodeEnvelope(sealed)
    }

    companion object {
        /**
         * Разбирает полученный конверт: проверяет подпись, разворачивает ключ,
         * расшифровывает и достаёт содержимое.
         *
         * Не требует ключа escrow — он нужен только при отправке, поэтому метод
         * статический: приёмная сторона про эпохи ничего не знает.
         *
         * Провал возвращается как `Result.failure`, а не исключением наружу. Причины
         * бывают трёх разных родов, и различать их обязан вызывающий:
         * [io.tima.crypto.VerificationFailure] — подпись или обязательство по ключу
         * не сошлись, то есть возможная подмена; `IllegalStateException` — нет
         * обёртки для этого устройства, то есть своя несобранная картина; остальное —
         * повреждённые байты.
         */
        fun open(
            envelopeBytes: ByteArray,
            myDeviceId: String,
            me: DeviceIdentity,
            senderSigningPublic: ByteArray,
        ): Result<ReceivedMessage> = runCatching {
            val sealed = MessageSerializer.decodeEnvelope(envelopeBytes).getOrThrow()
            val payload = PersonalMessageSealer.openWithWrappedKey(
                message = sealed,
                myDeviceId = myDeviceId,
                myDeviceKey = me.key,
                senderSigningPub = senderSigningPublic,
            ).getOrThrow()
            val body = MessageSerializer.decodeBody(payload).getOrThrow()
            ReceivedMessage(
                meta = sealed.meta,
                content = MessageContentCodec.fromBody(body),
                // Байты тела отдаются как пришли — уже упакованными. Хранилищу нужны
                // именно они: один кодек на провод и на диск. Пересобрать их из
                // содержимого нельзя без потерь, а записать текстом — значит записать в
                // другом формате, чем читает экран.
                body = payload,
            )
        }
    }
}

/**
 * Разобранное сообщение: что пришло и от кого.
 *
 * [body] — **байты тела, как они пришли**: `zstd(protobuf(MessageBody))`. Ровно их и
 * записывает хранилище, потому что кодек один на провод и на диск.
 *
 * **Поле появилось после живого прогона, и это была настоящая поломка.** Приёмник писал в
 * базу `content.plainText()` — простой текст, — а экран читал столбец кодеком и получал
 * «сообщение не читается». Сообщение при этом было расшифровано и записано: состояние
 * `STORED`, причины нечитаемости нет. Поймать это могла только проверка, читающая ТАК ЖЕ,
 * КАК ЭКРАН; сценарий сравнивал байты напрямую и потому был зелёным.
 */
data class ReceivedMessage(
    val meta: EnvelopeMeta,
    val content: MessageContent,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is ReceivedMessage &&
        meta == other.meta && content == other.content && body.contentEquals(other.body)

    override fun hashCode(): Int {
        var h = meta.hashCode()
        h = 31 * h + content.hashCode()
        h = 31 * h + body.contentHashCode()
        return h
    }
}
