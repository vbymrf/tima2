package io.tima.core.encryption

import io.kodium.Kodium
import io.tima.crypto.CanonicalBytes
import io.tima.crypto.MessageSerializer
import io.tima.crypto.WrappedKeyService

/**
 * История личных переписок на новом устройстве (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ИУ2–ИУ3, ADR-0010
 * этап 2).
 *
 * Ключ каждого сообщения завёрнут на устройства, бывшие у сторон в момент отправки. Новое
 * устройство в их число не входило. Доверенное своё устройство берёт с сервера свою обёртку,
 * разворачивает ключ сообщения и заворачивает его заново — под новое устройство, своей
 * эфемерной парой. Сам конверт и его подпись не меняются: новое устройство проверяет их так
 * же, как любое пришедшее сообщение.
 */
object HistoryKeys {

    /** Ключ одного сообщения, завёрнутый под новое устройство. */
    class Rewrapped(val messageId: Long, val ephemeralPub: ByteArray, val wrapped: ByteArray)

    /**
     * Перезавернуть ключ сообщения под [targetEncryptionPub].
     *
     * @param envelope конверт, как его отдал сервер: с обёрткой под ЭТО устройство.
     * @param wrapEphemeral эфемерал этой обёртки, если сервер его назвал; `null` — эфемерал
     *   конверта.
     * @return `null` — обёртки для нас нет, ключ не развернулся или не сошёлся с
     *   обязательством в конверте. Такой ключ отдавать нельзя: новое устройство его всё равно
     *   отвергнет, а помощник не должен раздавать то, чего не проверил сам.
     */
    fun rewrap(
        envelope: ByteArray,
        wrapEphemeral: ByteArray?,
        myDeviceId: String,
        me: DeviceIdentity,
        targetEncryptionPub: ByteArray,
    ): Rewrapped? = runCatching {
        require(targetEncryptionPub.size == 32) { "открытый ключ устройства — 32 байта" }
        val messageId = MessageSerializer.decodeEnvelope(envelope).getOrThrow().meta.messageId.toLong()
        val messageKey = keyOf(envelope, wrapEphemeral, myDeviceId, me) ?: return null
        wrapKey(messageId, messageKey, targetEncryptionPub)
    }.getOrNull()

    /**
     * Ключ сообщения из своей обёртки, сверенный с обязательством в конверте (план КК, КК9: ключи сообщений
     * хранятся на устройстве). `null` — обёртки для нас нет, ключ не развернулся или не сошёлся.
     */
    fun keyOf(envelope: ByteArray, wrapEphemeral: ByteArray?, myDeviceId: String, me: DeviceIdentity): ByteArray? = runCatching {
        val sealed = MessageSerializer.decodeEnvelope(envelope).getOrThrow()
        val wrapped = sealed.wrappedKeys[myDeviceId] ?: return null
        val eph = wrapEphemeral?.takeIf { it.size == 32 } ?: sealed.senderEphemeralPub
        // Ключи эпох от нового к старому, затем основной (ПС3).
        val messageKey = me.decryptKeys.firstNotNullOfOrNull { WrappedKeyService.unwrap(it, eph, wrapped).getOrNull() }
            ?: return null
        if (sealed.formatVersion >= CanonicalBytes.FORMAT_VERSION &&
            !CanonicalBytes.commitmentMatches(messageKey, sealed.keyCommitment)
        ) return null
        messageKey
    }.getOrNull()

    /** Ключ из конверта, как он лежит в очереди входящих: с меткой истории или без. */
    fun keyOfStored(stored: ByteArray, myDeviceId: String, me: DeviceIdentity): ByteArray? {
        val (eph, envelope) = HistoryFrame.split(stored)
        return keyOf(envelope, eph, myDeviceId, me)
    }

    /**
     * Завернуть **известный** ключ сообщения под устройство (КК9): помощник отвечает из своих ключей, а не из
     * обёртки на сервере — она могла быть уже стёрта.
     */
    fun wrapKey(messageId: Long, messageKey: ByteArray, targetEncryptionPub: ByteArray): Rewrapped {
        require(targetEncryptionPub.size == 32) { "открытый ключ устройства — 32 байта" }
        // Своя эфемерная пара на каждый ключ — как у долей ключа группы: компрометация одной
        // не раскрывает остальную историю.
        val ephemeral = Kodium.generateKeyPair()
        return Rewrapped(
            messageId = messageId,
            ephemeralPub = ephemeral.getPublicKey().encryptionKey,
            wrapped = WrappedKeyService.wrap(ephemeral, targetEncryptionPub, messageKey).getOrThrow(),
        )
    }
}

/**
 * Конверт истории, как он лежит в очереди входящих: метка, эфемерал обёртки, конверт.
 *
 * Обёртку истории помощник делает своей эфемерной парой, а не той, что в конверте, — и
 * конверт без неё не открыть. Подменить эфемерал в самом конверте нельзя: он под подписью.
 * Поэтому эфемерал хранится рядом, в тех же байтах: нечитаемое сообщение очередь разбирает
 * повторно позже, и эфемерал не должен потеряться между попытками.
 *
 * Метка `1` в protobuf невозможна, как и `0` группового кадра: первый байт protobuf — тег
 * поля, а поля с номером ноль не бывает.
 */
object HistoryFrame {

    const val LABEL: Byte = 1
    private const val EPH = 32

    fun toStored(wrapEphemeral: ByteArray, envelope: ByteArray): ByteArray {
        require(wrapEphemeral.size == EPH) { "эфемерал обёртки — 32 байта" }
        return byteArrayOf(LABEL) + wrapEphemeral + envelope
    }

    fun isHistory(stored: ByteArray): Boolean = stored.size > 1 + EPH && stored[0] == LABEL

    /** Эфемерал обёртки и конверт; для обычного конверта — `null` и он сам. */
    fun split(stored: ByteArray): Pair<ByteArray?, ByteArray> =
        if (isHistory(stored)) stored.copyOfRange(1, 1 + EPH) to stored.copyOfRange(1 + EPH, stored.size)
        else null to stored
}
