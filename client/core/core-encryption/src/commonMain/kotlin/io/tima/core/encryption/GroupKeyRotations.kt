package io.tima.core.encryption

import io.tima.crypto.DeviceAddress
import io.tima.crypto.EscrowModule
import io.tima.crypto.GroupKeyManager

/**
 * Ротация группового ключа — то, что уходит в `POST /groups/{id}/keys`.
 *
 * **Новый ключ порождает клиент-инициатор, а не сервер** (`crypto-protocol §4.2`). Сервер
 * получает только обёртки — по одной на устройство участника — и escrow-блоб; сам ключ он
 * не видит ни в одной из этих частей и восстановить его не может.
 *
 * **Список получателей — единственный источник того, кто ключ получит.** Устройствам
 * исключённых участников обёртки просто не создаются, и в этом весь смысл ротации при
 * выходе: исключённый остаётся со старыми версиями и не получает новую. Поэтому передать
 * сюда список «на всякий случай пошире» значит выдать доступ, а не подстраховаться.
 *
 * **Escrow один на версию ключа, а не на сообщение.** У личного сообщения escrow-блоб свой
 * на каждое; здесь ордер раскрывает ключ группы, то есть сразу всю её переписку под этой
 * версией. Отсюда и требование ключа эпохи в конструкторе: собрать ротацию без escrow
 * нельзя, и это должно быть видно по подписи, а не выясняться в рантайме.
 */
class GroupKeyRotations(escrowKey: EscrowEpochKey) {

    private val manager = GroupKeyManager(
        EscrowModule(escrowPublicKey = escrowKey.publicKey, escrowKeyVersion = escrowKey.version),
    )

    /**
     * @param currentVersion версия, известная СЕРВЕРУ, а не нам. Новая будет `current + 1`;
     *   взяв свою, отставшую, мы получим `version_conflict` — и это правильный отказ, а не
     *   поломка: значит, кто-то ротировал раньше и надо перечитать состояние.
     * @param recipients активные устройства всех участников **после** события: вход или
     *   выход уже применён к списку.
     */
    fun rotate(currentVersion: Int, recipients: List<RecipientDevice>): Result<MintedGroupKey> =
        runCatching {
            require(recipients.isNotEmpty()) { "ротация без получателей оставит группу без ключа" }
            val ротация = manager.rotate(
                currentVersion = currentVersion,
                devices = recipients.map { DeviceAddress(it.deviceId, it.encryptionPublic) },
            ).getOrThrow()
            MintedGroupKey(
                gkVersion = ротация.gkVersion,
                groupKey = ротация.groupKey,
                senderEphemeralPub = ротация.senderEphemeralPub,
                wrappedKeys = ротация.wrappedKeys,
                escrowMlkemCt = ротация.escrow.mlkemCt,
                escrowWrappedKey = ротация.escrow.wrappedMessageKey,
                escrowKeyVersion = ротация.escrow.escrowKeyVersion,
            )
        }
}

/**
 * Порождённая версия ключа: что отправить серверу и что оставить себе.
 *
 * [groupKey] наружу не уходит **никогда** — он кладётся в местное хранилище под шифром
 * покоя. На сервер едет всё остальное.
 */
class MintedGroupKey(
    val gkVersion: Int,
    val groupKey: ByteArray,
    val senderEphemeralPub: ByteArray,
    /** Обёртки по `device_id`. */
    val wrappedKeys: Map<String, ByteArray>,
    val escrowMlkemCt: ByteArray,
    val escrowWrappedKey: ByteArray,
    val escrowKeyVersion: Int,
)
