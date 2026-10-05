package io.tima.core.encryption

import io.kodium.Kodium
import io.kodium.ratchet.HKDF
import io.tima.crypto.AccountMnemonic
import io.tima.crypto.MessageSerializer
import io.tima.crypto.MessageSigner
import io.tima.crypto.SealedPersonalMessage
import io.tima.crypto.WrappedKeyService
import kotlin.io.encoding.Base64

/**
 * Копия ключей по модели Matrix (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ §3а, Р43–Р47, М1–М7).
 *
 * Ключ каждого сообщения по-прежнему завёрнут на устройства. Копия — ещё одна обёртка того же
 * ключа, под **открытый ключ копии** личности. Пара копии выводится из фразы своей меткой
 * `tima/key-copy/v1|<эпоха>` — отдельно от ключа личности. Пополнить копию может любое своё
 * устройство (нужен только открытый ключ), открыть — тот, у кого фраза или сохранённый на
 * телефоне секрет копии (Р46).
 *
 * Обёртка копии устроена ровно как обёртка устройству — своя эфемерная пара и
 * [WrappedKeyService]; копия для неё — ещё одно «устройство» с адресом [RECIPIENT]. Поэтому и
 * разворачивается она тем же [HistoryKeys.rewrap], а восстановленное сообщение открывается
 * тем же разбором, что пришедшее живым: подпись и обязательство — по исходному конверту.
 *
 * Метка вывода **печёная** (`crypto-invariants.mdc`): её правка делает все копии нечитаемыми.
 */
object KeyCopy {
    /** Адрес копии в конверте, который сервер отдаёт для восстановления. */
    const val RECIPIENT = "key-copy"

    private const val LABEL = "tima/key-copy/v1|"
    private const val EPH = 32
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** Пара копии эпохи [epoch] из фразы; `null` — слова не складываются в фразу. */
    fun fromWords(words: List<String>, epoch: Int): DeviceIdentity? = runCatching {
        require(epoch >= 1) { "эпоха копии — с единицы" }
        val clean = words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val seed = HKDF.deriveSecrets(
            salt = null,
            ikm = AccountMnemonic.mnemonicToEntropy(clean),
            info = (LABEL + epoch).encodeToByteArray(),
            length = 32,
        )
        DeviceIdentity.fromRaw(seed)
    }.getOrNull()

    /** Что подписывает ключ личности: эпоха и открытый ключ копии (тот же текст, что на сервере). */
    fun signedBytes(epoch: Int, pub: ByteArray): ByteArray =
        "tima.key-copy.v1|$epoch|${b64.encode(pub)}".encodeToByteArray()

    /** Подпись ключом личности из фразы; `null` — фраза не та. */
    fun sign(words: List<String>, epoch: Int, pub: ByteArray): ByteArray? =
        IdentitySignerOverKodium.sign(words, signedBytes(epoch, pub))

    /** Открытый ключ копии действительно подписан ключом личности — сервер не подсунул свой. */
    fun verify(identityPub: ByteArray, epoch: Int, pub: ByteArray, signature: ByteArray): Boolean =
        runCatching { MessageSigner.verify(identityPub, signedBytes(epoch, pub), signature) }.getOrDefault(false)

    /**
     * Обёртка ключа сообщения в копию (М2): `эфемерал (32) || обёртка`.
     *
     * @param envelope конверт с обёрткой под ЭТО устройство — отправленный или полученный.
     * @return `null` — обёртки для нас в конверте нет или ключ не сошёлся с обязательством.
     */
    fun wrapMessage(
        envelope: ByteArray,
        wrapEphemeral: ByteArray?,
        myDeviceId: String,
        me: DeviceIdentity,
        copyPub: ByteArray,
    ): ByteArray? = HistoryKeys.rewrap(envelope, wrapEphemeral, myDeviceId, me, copyPub)
        ?.let { it.ephemeralPub + it.wrapped }

    /** Обёртка версии ключа группы в копию (М2): `эфемерал (32) || обёртка`. */
    fun wrapGroupKey(copyPub: ByteArray, groupKey: ByteArray): ByteArray? = runCatching {
        require(copyPub.size == 32) { "открытый ключ копии — 32 байта" }
        val ephemeral = Kodium.generateKeyPair()
        ephemeral.getPublicKey().encryptionKey + WrappedKeyService.wrap(ephemeral, copyPub, groupKey).getOrThrow()
    }.getOrNull()

    /** Версия ключа группы из копии (М3); `null` — обёртка не наша или испорчена. */
    fun openGroupKey(copy: DeviceIdentity, blob: ByteArray): ByteArray? = runCatching {
        require(blob.size > EPH) { "обёртка копии короче эфемерала" }
        WrappedKeyService.unwrap(copy.key, blob.copyOfRange(0, EPH), blob.copyOfRange(EPH, blob.size)).getOrThrow()
    }.getOrNull()

    /**
     * Ключ сообщения из копии — заново под устройство [targetPub] (М3, М4).
     *
     * @param envelope конверт страницы копии: обёртка в нём — для [RECIPIENT].
     */
    fun rewrapFor(
        envelope: ByteArray,
        wrapEphemeral: ByteArray,
        copy: DeviceIdentity,
        targetPub: ByteArray,
    ): HistoryKeys.Rewrapped? = HistoryKeys.rewrap(envelope, wrapEphemeral, RECIPIENT, copy, targetPub)

    /**
     * Сообщение из копии — в том виде, в каком его ждёт очередь входящих ЭТОГО устройства (М3):
     * конверт с обёрткой под это устройство и эфемерал рядом ([HistoryFrame]). Сам конверт и
     * подпись не меняются — меняется только список обёрток, который под подпись не входит.
     *
     * @return `null` — копия не открылась (не та эпоха, порча) или ключ не сошёлся.
     */
    fun restoreFor(
        envelope: ByteArray,
        wrapEphemeral: ByteArray,
        copy: DeviceIdentity,
        myDeviceId: String,
        myEncryptionPub: ByteArray,
    ): ByteArray? = runCatching {
        val rewrapped = rewrapFor(envelope, wrapEphemeral, copy, myEncryptionPub) ?: return null
        val sealed = MessageSerializer.decodeEnvelope(envelope).getOrThrow()
        val mine = SealedPersonalMessage(
            formatVersion = sealed.formatVersion,
            meta = sealed.meta,
            encryptedPayload = sealed.encryptedPayload,
            escrow = sealed.escrow,
            senderEphemeralPub = sealed.senderEphemeralPub,
            ratchetEnvelope = sealed.ratchetEnvelope,
            signature = sealed.signature,
            wrappedKeys = mapOf(myDeviceId to rewrapped.wrapped),
            keyCommitment = sealed.keyCommitment,
        )
        HistoryFrame.toStored(rewrapped.ephemeralPub, MessageSerializer.encodeEnvelope(mine))
    }.getOrNull()
}
