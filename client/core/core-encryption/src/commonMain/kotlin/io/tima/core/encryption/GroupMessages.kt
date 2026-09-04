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
        val canonical = CanonicalBytes.buildGroupMessage(meta, payload)
        SealedGroupMessage(
            meta = meta,
            payload = payload,
            signature = MessageSigner.sign(sender.key, canonical).getOrThrow(),
        )
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
    ): Result<ReceivedGroupMessage> = runCatching {
        val canonical = CanonicalBytes.buildGroupMessage(sealed.meta, sealed.payload)
        if (!MessageSigner.verify(senderSigningPublic, canonical, sealed.signature)) {
            throw VerificationFailure("Подпись сообщения группы не прошла проверку")
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
        groupKey: ByteArray,
    ): Result<ReceivedGroupMessage> = open(
        sealed = SealedGroupMessage(
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
        ),
        senderSigningPublic = senderSigningPublic,
        groupKey = groupKey,
    )
}

/** Готовое к отправке сообщение группы: то, что уходит в `POST /groups/{id}/messages`. */
class SealedGroupMessage(
    val meta: GroupMessageMeta,
    /** `SecretBox(zstd(protobuf(body)), GK)`. */
    val payload: ByteArray,
    /** Ed25519 по `group_message_canonical_bytes`, 64 байта. */
    val signature: ByteArray,
)

/** Разобранное сообщение группы. [body] — байты тела как пришли, их и пишет хранилище. */
class ReceivedGroupMessage(
    val meta: GroupMessageMeta,
    val content: MessageContent,
    val body: ByteArray,
)
