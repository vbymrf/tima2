package io.tima.core.encryption

import io.tima.crypto.CanonicalBytes
import io.tima.crypto.EnvelopeCipher
import io.tima.crypto.GroupMessageMeta
import io.tima.crypto.MessageContent
import io.tima.crypto.MessageContentCodec
import io.tima.crypto.MessageSerializer
import io.tima.crypto.MessageSigner
import io.tima.crypto.VerificationFailure

/**
 * Сообщение группы: собрать и открыть — один вызов вместо четырёх.
 *
 * **Чем это отличается от личного сообщения — не «почти тем же самым».** Личное едет
 * конвертом `Envelope`: у него на каждое сообщение свой одноразовый ключ, свои обёртки на
 * устройства получателей и свой escrow-блоб. У группового нет ничего из этого
 * (`crypto-protocol §4.1`): ключ один на версию GK, обёртки раздаются при ротации, escrow
 * тоже один на версию. Поэтому на провод уходит не конверт, а три поля — `payload`,
 * `signature` и метаданные, — и подпись считается по своей раскладке
 * (`group_message_canonical_bytes`), с доменной меткой, не пересекающейся с конвертом.
 *
 * Порядок шагов нормативен: содержимое → тело (`zstd(protobuf)`) → шифрование ключом
 * группы → подпись по каноническим байтам. Перепутанный порядок даёт не ошибку сборки,
 * а сообщение, которое не откроется ни у кого.
 *
 * ── ПОЧЕМУ ПУБЛИЧНАЯ ГРУППА СЮДА НЕ ПРОХОДИТ ─────────────────────────────────
 *
 * В протоколе `gk_version = 0` означает публичную группу, где `payload` идёт открытым
 * protobuf. Соблазнительно разрешить это здесь же: «нет ключа — шлём открытым». Именно так
 * и выглядит утечка: группа считалась частной, ключ не доехал, сообщение ушло в
 * незашифрованном виде, и никто ничего не заметил, потому что оно доставилось.
 *
 * Поэтому этот фасад требует версию ключа не меньше единицы. Публичная группа, когда она
 * появится, получит **отдельный** вызов, и его придётся написать намеренно.
 */
object GroupMessages {

    /**
     * @param groupKey GK той версии, что названа в [GroupMessageMeta.gkVersion]. Брать
     *   последнюю известную: сообщение, зашифрованное старой версией, участники, пришедшие
     *   после ротации, не прочитают.
     */
    fun seal(
        content: MessageContent,
        meta: GroupMessageMeta,
        sender: DeviceIdentity,
        groupKey: ByteArray,
    ): Result<SealedGroupMessage> = runCatching {
        require(meta.gkVersion >= 1) {
            "версия группового ключа обязана быть не меньше 1: нулевая означает открытый payload"
        }
        val body = MessageContentCodec.toBody(content)
        val plaintext = MessageSerializer.encodeBody(body)
        val payload = EnvelopeCipher.seal(groupKey, plaintext).getOrThrow()
        signedV2(meta, payload, sender, groupKey)
    }

    /**
     * Собирает ОТКРЫТОЕ сообщение группы — уровни 0…3 (ADR-0019 §1).
     *
     * Шифрования здесь нет по построению: сообщение уровня 0 существует ровно для того,
     * чтобы его прочёл тот, у кого ключа нет и не будет, — карточка личной группы, лента
     * публичной. Подпись при этом остаётся: открытость содержимого не отменяет вопроса,
     * кто его написал.
     *
     * `gkVersion` обязан быть нулевым: ненулевой означал бы, что payload закрыт, и сервер
     * такое сообщение отвергнет (`gk_without_secret`).
     */
    fun sealPlain(
        content: MessageContent,
        meta: GroupMessageMeta,
        sender: DeviceIdentity,
    ): Result<SealedGroupMessage> = runCatching {
        require(meta.gkVersion == 0) {
            "у открытого сообщения версии ключа нет: ненулевая означает шифр"
        }
        val payload = MessageSerializer.encodeBody(MessageContentCodec.toBody(content))
        val canonical = CanonicalBytes.buildGroupMessage(meta, payload)
        SealedGroupMessage(
            meta = meta,
            payload = payload,
            signature = MessageSigner.sign(sender.key, canonical).getOrThrow(),
        )
    }

    /**
     * То же, что [seal], но тело уже упаковано — оно пришло из очереди исходящих.
     *
     * Очередь хранит `zstd(protobuf(MessageBody))`, собранный при постановке сообщения.
     * Разбирать его обратно ради повторной упаковки значило бы делать одну и ту же работу
     * дважды — и однажды сделать её иначе.
     */
    fun sealPrepared(
        body: ByteArray,
        meta: GroupMessageMeta,
        sender: DeviceIdentity,
        groupKey: ByteArray,
        /** Правка (ADR-0045): номер исправляемого; не 0 — подпись `tima.group_message.v3`. */
        editOf: Long = 0,
    ): Result<SealedGroupMessage> = runCatching {
        require(meta.gkVersion >= 1) {
            "версия группового ключа обязана быть не меньше 1: нулевая означает открытый payload"
        }
        val payload = EnvelopeCipher.seal(groupKey, body).getOrThrow()
        signedV2(meta, payload, sender, groupKey, editOf)
    }

    /**
     * Подпись версии 2 — привязка ключа группы (ADR-0013 для групп; решение заказчика 2026-10-06
     * «отменяем решение 5»). Обязательство считается ОДИН раз от того же ключа, которым закрыт
     * payload: получатель и депозитарий пересчитают его из своего ключа и сверят.
     */
    private fun signedV2(meta: GroupMessageMeta, payload: ByteArray, sender: DeviceIdentity, groupKey: ByteArray, editOf: Long = 0): SealedGroupMessage {
        val commitment = CanonicalBytes.keyCommitment(groupKey)
        return SealedGroupMessage(
            meta = meta,
            payload = payload,
            signature = MessageSigner.sign(sender.key, canonical(meta, payload, commitment, editOf)).getOrThrow(),
            keyCommitment = commitment,
            editOf = editOf,
        )
    }

    /**
     * Подписываемые байты по виду: правка — v3 (ADR-0045; у открытого обязательство пусто),
     * зашифрованное — v2, открытое — v1.
     */
    private fun canonical(meta: GroupMessageMeta, payload: ByteArray, commitment: ByteArray?, editOf: Long): ByteArray = when {
        editOf > 0 -> CanonicalBytes.buildGroupMessageV3(meta, editOf.toULong(), payload, commitment ?: CanonicalBytes.EMPTY)
        commitment != null -> CanonicalBytes.buildGroupMessageV2(meta, payload, commitment)
        else -> CanonicalBytes.buildGroupMessage(meta, payload)
    }

    /** То же для ОТКРЫТОГО сообщения: тело уходит как есть, шифра нет, подпись есть. */
    fun sealPreparedPlain(
        body: ByteArray,
        meta: GroupMessageMeta,
        sender: DeviceIdentity,
        editOf: Long = 0,
    ): Result<SealedGroupMessage> = runCatching {
        require(meta.gkVersion == 0) {
            "у открытого сообщения версии ключа нет: ненулевая означает шифр"
        }
        SealedGroupMessage(
            meta = meta,
            payload = body,
            signature = MessageSigner.sign(sender.key, canonical(meta, body, null, editOf)).getOrThrow(),
            editOf = editOf,
        )
    }

    /**
     * Открывает пришедшее сообщение группы.
     *
     * Подпись проверяется **до** расшифровки и по тем же метаданным, что пришли с сервером:
     * иначе сервер мог бы подменить автора или время, оставив payload нетронутым, и подпись
     * всё равно сошлась бы — она считается по метаданным вместе с содержимым.
     *
     * Провал возвращается как `Result.failure`, и рода у него два, различать их обязан
     * вызывающий: [VerificationFailure] — подпись не сошлась, то есть возможная подмена;
     * остальное — не тот ключ или повреждённые байты, то есть своя несобранная картина.
     */
    fun open(
        sealed: SealedGroupMessage,
        senderSigningPublic: ByteArray,
        groupKey: ByteArray,
    ): Result<ReceivedGroupMessage> = openSealed(sealed, senderSigningPublic, groupKey)

    /** `null` вместо ключа подписи — открыть без проверки подписи (КК13); только через [openUnverified]. */
    private fun openSealed(
        sealed: SealedGroupMessage,
        senderSigningPublic: ByteArray?,
        groupKey: ByteArray,
    ): Result<ReceivedGroupMessage> = runCatching {
        val commitment = sealed.keyCommitment
        if (senderSigningPublic != null && !signatureMatches(sealed, senderSigningPublic)) {
            throw VerificationFailure("Подпись сообщения группы не прошла проверку")
        }
        // Версия 2: обязательство в подписи обязано сойтись с ключом, которым открываем. Не
        // сошлось — у нас не тот ключ, которым отправитель обещал закрыть текст, и
        // расшифровывать им нельзя: шифртекст может раскрыться под двумя ключами в два разных
        // текста. Подписью это не подмена (она сошлась), поэтому и не VerificationFailure —
        // «не тот ключ», как и без обязательства.
        check(commitment == null || CanonicalBytes.keyCommitment(groupKey).contentEquals(commitment)) {
            "обязательство по ключу группы не сошлось с ключом — расшифровывать нельзя"
        }
        val plaintext = EnvelopeCipher.open(groupKey, sealed.payload).getOrThrow()
        val body = MessageSerializer.decodeBody(plaintext).getOrThrow()
        ReceivedGroupMessage(
            meta = sealed.meta,
            content = MessageContentCodec.fromBody(body),
            // Байты тела отдаются как пришли — уже упакованными. Хранилище пишет именно их:
            // кодек один на провод и на диск. Записать текстом значит записать в другом
            // формате, чем читает экран, — на этом уже ловились личные сообщения.
            body = plaintext,
        )
    }

    /**
     * Открывает ОТКРЫТОЕ сообщение группы — уровни 0…3 (ADR-0019).
     *
     * Ключа здесь нет по назначению, но **подпись проверяется так же строго**: открытость
     * содержимого не отменяет вопроса, кто его написал. Без этой проверки сервер мог бы
     * подставить в ленту любой текст от чужого имени.
     */
    fun openPlain(
        sealed: SealedGroupMessage,
        senderSigningPublic: ByteArray,
    ): Result<ReceivedGroupMessage> = openPlainSealed(sealed, senderSigningPublic)

    private fun openPlainSealed(
        sealed: SealedGroupMessage,
        senderSigningPublic: ByteArray?,
    ): Result<ReceivedGroupMessage> = runCatching {
        require(sealed.meta.gkVersion == 0) {
            "у открытого сообщения версии ключа нет: ненулевая означает шифр"
        }
        if (senderSigningPublic != null && !signatureMatches(sealed, senderSigningPublic)) {
            throw VerificationFailure("Подпись сообщения группы не прошла проверку")
        }
        val body = MessageSerializer.decodeBody(sealed.payload).getOrThrow()
        ReceivedGroupMessage(
            meta = sealed.meta,
            content = MessageContentCodec.fromBody(body),
            body = sealed.payload,
        )
    }

    /**
     * То же открытие, но **по полям кадра**, как они пришли с сервера.
     *
     * Появилось 2026-08-25. До этого приёмник собирал [GroupMessageMeta] сам — восемь
     * полей в правильном порядке и с правильными типами, — и ради этого импортировал
     * крипто-типы в composition root. Плата была не в строчках: раскладка метаданных
     * входит в подписываемые байты, значит любое её изменение обязано было править и
     * приёмник, а забытая правка даёт не ошибку сборки, а сообщение, которое не
     * открывается ни у кого.
     *
     * Теперь meta собирается здесь, рядом с `CanonicalBytes`, — в том же модуле, что
     * и правило её раскладки.
     *
     * @param threadRoot и [replyTo] — беззнаковые в протоколе, но приходят из транспорта
     *   знаковыми `Long`: преобразование сделано здесь, чтобы вызывающий о нём не знал.
     */
    @Suppress("LongParameterList")
    fun open(
        groupId: String,
        senderId: String,
        senderDevice: String,
        kind: Int,
        createdAtUnixMs: Long,
        threadRoot: Long,
        replyTo: Long,
        gkVersion: Int,
        payload: ByteArray,
        signature: ByteArray,
        senderSigningPublic: ByteArray,
        /** Пустой при [gkVersion] = 0: открытому сообщению ключ не нужен. */
        groupKey: ByteArray,
        /** Обязательство по ключу группы (подпись версии 2); `null` — версия 1. */
        keyCommitment: ByteArray? = null,
        /** Правка (ADR-0045): номер исправляемого из кадра; не 0 — подпись версии 3. */
        editOf: Long = 0,
    ): Result<ReceivedGroupMessage> = openAny(
        sealed = sealedOf(groupId, senderId, senderDevice, kind, createdAtUnixMs, threadRoot, replyTo, gkVersion, payload, signature, keyCommitment, editOf),
        senderSigningPublic = senderSigningPublic,
        groupKey = groupKey,
    )

    /**
     * Открыть **без проверки подписи** — ключа подписи автора нет ни у кого (план КК, КК13, Р10). Сообщение
     * показывается с пометкой «Не подтверждено»; подпись проверяется [signatureMatches], когда ключ придёт.
     * Обязательство по ключу группы проверяется как обычно. Вызывающий обязан пометить результат.
     */
    @Suppress("LongParameterList")
    fun openUnverified(
        groupId: String,
        senderId: String,
        senderDevice: String,
        kind: Int,
        createdAtUnixMs: Long,
        threadRoot: Long,
        replyTo: Long,
        gkVersion: Int,
        payload: ByteArray,
        signature: ByteArray,
        groupKey: ByteArray,
        keyCommitment: ByteArray? = null,
        editOf: Long = 0,
    ): Result<ReceivedGroupMessage> = openAny(
        sealed = sealedOf(groupId, senderId, senderDevice, kind, createdAtUnixMs, threadRoot, replyTo, gkVersion, payload, signature, keyCommitment, editOf),
        senderSigningPublic = null,
        groupKey = groupKey,
    )

    /** Сошлась ли подпись автора — по полям кадра, без расшифровки (повторная проверка, КК13). */
    @Suppress("LongParameterList")
    fun signatureMatches(
        groupId: String,
        senderId: String,
        senderDevice: String,
        kind: Int,
        createdAtUnixMs: Long,
        threadRoot: Long,
        replyTo: Long,
        gkVersion: Int,
        payload: ByteArray,
        signature: ByteArray,
        senderSigningPublic: ByteArray,
        keyCommitment: ByteArray? = null,
        editOf: Long = 0,
    ): Boolean = signatureMatches(
        sealedOf(groupId, senderId, senderDevice, kind, createdAtUnixMs, threadRoot, replyTo, gkVersion, payload, signature, keyCommitment, editOf),
        senderSigningPublic,
    )

    private fun signatureMatches(sealed: SealedGroupMessage, senderSigningPublic: ByteArray): Boolean =
        MessageSigner.verify(
            senderSigningPublic,
            canonical(sealed.meta, sealed.payload, sealed.keyCommitment.takeIf { sealed.meta.gkVersion != 0 }, sealed.editOf),
            sealed.signature,
        )

    @Suppress("LongParameterList")
    private fun sealedOf(
        groupId: String,
        senderId: String,
        senderDevice: String,
        kind: Int,
        createdAtUnixMs: Long,
        threadRoot: Long,
        replyTo: Long,
        gkVersion: Int,
        payload: ByteArray,
        signature: ByteArray,
        keyCommitment: ByteArray?,
        editOf: Long,
    ) = SealedGroupMessage(
        meta = GroupMessageMeta(
            groupId = groupId,
            senderId = senderId,
            senderDevice = senderDevice,
            kind = kind,
            createdAtUnixMs = createdAtUnixMs,
            threadRoot = threadRoot.toULong(),
            replyTo = replyTo.toULong(),
            gkVersion = gkVersion,
        ),
        payload = payload,
        signature = signature,
        keyCommitment = keyCommitment,
        editOf = editOf,
    )

    /** Открыть по тому, шифровано ли: нулевая версия ключа — открытое сообщение. */
    private fun openAny(
        sealed: SealedGroupMessage,
        senderSigningPublic: ByteArray?,
        groupKey: ByteArray,
    ): Result<ReceivedGroupMessage> =
        if (sealed.meta.gkVersion == 0) openPlainSealed(sealed, senderSigningPublic)
        else openSealed(sealed, senderSigningPublic, groupKey)
}

/** Готовое к отправке сообщение группы: то, что уходит в `POST /groups/{id}/messages`. */
class SealedGroupMessage(
    val meta: GroupMessageMeta,
    /** `SecretBox(zstd(protobuf(body)), GK)`. */
    val payload: ByteArray,
    /** Ed25519 по `group_message_canonical_bytes`, 64 байта. */
    val signature: ByteArray,
    /** Обязательство по ключу группы — у зашифрованного (подпись версии 2); `null` — версия 1 или открытое. */
    val keyCommitment: ByteArray? = null,
    /** Правка (ADR-0045): номер исправляемого; не 0 — подпись `tima.group_message.v3`. */
    val editOf: Long = 0,
)

/** Разобранное сообщение группы. [body] — байты тела как пришли, их и пишет хранилище. */
class ReceivedGroupMessage(
    val meta: GroupMessageMeta,
    val content: MessageContent,
    val body: ByteArray,
)
