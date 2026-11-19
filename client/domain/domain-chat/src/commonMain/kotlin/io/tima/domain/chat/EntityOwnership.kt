package io.tima.domain.chat

/**
 * Владение сущностью по ключу личности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §6, СЦ11): цепочка звеньев, предложение и
 * принятие передачи. Подписывает человек фразой — ключ личности на устройстве не хранится (ADR-0010).
 */
interface EntityOwnership {
    suspend fun chain(kind: EntityKind, id: String): OwnershipView?
    suspend fun offer(kind: EntityKind, id: String, toUserId: String, expiresUnix: Long, signature: ByteArray): OwnershipStep
    suspend fun accept(kind: EntityKind, id: String, signature: ByteArray): OwnershipStep
    suspend fun drop(kind: EntityKind, id: String): OwnershipStep
    suspend fun offersToMe(): List<OwnershipOffer>?
}

/** Цепочка глазами просящего. */
data class OwnershipView(
    val links: List<OwnershipLink>,
    /** Может ли просящий передать: он владелец и его ключ — ключ последнего звена (§6.3). */
    val canTransfer: Boolean,
    val nextLink: Int,
    val offer: OwnershipOffer?,
)

data class OwnershipLink(val linkNo: Int, val ownerId: String, val kind: String)

/** Предложение передачи. */
data class OwnershipOffer(
    val kind: EntityKind,
    val entityId: String,
    val linkNo: Int,
    val fromId: String,
    val toId: String,
    val expiresUnix: Long,
    val title: String,
)

sealed interface OwnershipStep {
    data object Done : OwnershipStep

    /** Фраза не та: подпись не прошла. [dropped] — третья ошибка, предложение погашено. */
    data class BadPhrase(val dropped: Boolean) : OwnershipStep

    /** Принимающий ниже модератора (в сообществе — не админ). */
    data object ReceiverRole : OwnershipStep

    /** Предложения нет или срок прошёл. */
    data object Gone : OwnershipStep

    /** У звена или аккаунта нет ключа личности. */
    data object NoIdentity : OwnershipStep
    data object Offline : OwnershipStep
    data class Refused(val reason: String) : OwnershipStep
}

/**
 * Передать и принять — подпись акта фразой. [sign] — ключом личности из слов (`IdentitySigner`); [act] — байты акта
 * (`EntityTransfer.bytes`), оба внедряются сверху: домену незачем знать криптографию.
 */
class TransferOwnership(
    private val ownership: EntityOwnership,
    private val sign: (words: List<String>, bytes: ByteArray) -> ByteArray?,
    private val act: (kind: String, entityId: String, linkNo: Int, from: String, to: String, expiresUnix: Long) -> ByteArray,
    private val nowUnix: () -> Long,
) {
    /** Владелец предлагает: акт на 30 минут, подписанный фразой. `null` от подписи — фраза не складывается. */
    suspend fun offer(kind: EntityKind, id: String, me: String, to: String, nextLink: Int, words: List<String>): OwnershipStep {
        val expires = nowUnix() + TTL_SECONDS
        val signature = sign(words, act(kind.wire, id, nextLink, me, to, expires)) ?: return OwnershipStep.BadPhrase(false)
        return ownership.offer(kind, id, to, expires, signature)
    }

    /** Принимающий подписывает тот же акт своим ключом. */
    suspend fun accept(offer: OwnershipOffer, words: List<String>): OwnershipStep {
        val signature = sign(words, act(offer.kind.wire, offer.entityId, offer.linkNo, offer.fromId, offer.toId, offer.expiresUnix))
            ?: return OwnershipStep.BadPhrase(false)
        return ownership.accept(offer.kind, offer.entityId, signature)
    }

    companion object {
        /** Срок акта — 30 минут (§6.2, 3); сервер принимает не больше. */
        const val TTL_SECONDS: Long = 29 * 60
    }
}
