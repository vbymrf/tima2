package io.tima.shared

import io.tima.core.encryption.EscrowKeyVerifier
import io.tima.core.encryption.EscrowTrust
import io.tima.core.encryption.GroupKeyRotations
import io.tima.core.encryption.RecipientDevice
import io.tima.core.network.DeviceKeysResult
import io.tima.core.network.EscrowApi
import io.tima.core.network.EscrowKeyResult
import io.tima.core.network.GroupKeysApi
import io.tima.core.network.GroupKeysResult
import io.tima.core.network.GroupsApi
import io.tima.core.network.KeysApi
import io.tima.core.network.MembersResult
import io.tima.core.network.RotateResult
import io.tima.domain.chat.GroupKeyBook
import io.tima.domain.chat.GroupKeyRotator
import io.tima.domain.chat.RotateStep

/**
 * Ротация группового ключа — составление, а не логика.
 *
 * Домен объявляет, **когда** ротировать (вход и выход участника), и не может выполнить это
 * сам: ротации нужны разом ключ эпохи escrow, устройства всех участников, крипта и сеть —
 * то есть ровно то, чего слой Domain не видит. Поэтому здесь только сведение, по образцу
 * [Отправитель].
 *
 * ── ПОРЯДОК, КОТОРЫЙ ВАЖНЕЕ КОДА ────────────────────────────────────────────
 *
 * 1. **Состав и устройства спрашиваются заново, у сервера.** Свой список, собранный из
 *    догадок, разойдётся с настоящим на первой же гонке — а ошибиться здесь значит либо
 *    оставить исключённого с ключом, либо не выдать ключ тому, кто в группе.
 * 2. **Ключ эпохи проверяется подписью анклава до всякого шифрования.** Escrow у группы
 *    один на версию ключа: ордер раскрывает не письмо, а всю переписку под этой версией.
 *    Тем строже требование, чтобы ключ был проверенным, а не просто пришедшим.
 * 3. **Версия берётся серверная.** Своя могла отстать; ротация от отставшей получит
 *    `version_conflict` — законный отказ, означающий «кто-то успел раньше».
 * 4. **Свой экземпляр ключа кладётся в базу только после успеха сервера.** Записав раньше,
 *    мы получили бы версию, которой нет ни у кого: писать ею — значит писать в пустоту.
 */
class GroupKeyRotation(
    private val groups: GroupsApi,
    private val deviceKeys: KeysApi,
    private val escrow: EscrowApi,
    private val groupKeys: GroupKeysApi,
    private val book: GroupKeyBook,
    private val msNow: () -> Long,
) : GroupKeyRotator {

    override suspend fun rotate(groupId: String): RotateStep {
        val enclave = EscrowTrust.enclaveSigningPub
            ?: return RotateStep.Refused("нет ключа подписи анклава: ротация отказана")

        val members = when (val answer = groups.members(groupId)) {
            is MembersResult.Members -> answer.members.map { it.userId }
            is MembersResult.NoConnection -> return RotateStep.Offline(answer.link.retryDelayMs)
            is MembersResult.Refused -> return RotateStep.Refused(answer.code)
        }
        if (members.isEmpty()) {
            // Группа без участников ключа не требует, и ротация без получателей оставила бы
            // версию, которой ни у кого нет.
            return RotateStep.Refused("в группе нет участников: ротировать не для кого")
        }

        val recipients = mutableListOf<RecipientDevice>()
        for (who in members) {
            when (val answer = deviceKeys.devicesOf(who)) {
                is DeviceKeysResult.Devices ->
                    recipients += answer.devices.map { RecipientDevice(it.deviceId, it.encryptionPub) }

                is DeviceKeysResult.Offline -> return RotateStep.Offline(answer.link.retryDelayMs)

                // Пропустить участника нельзя: он остался бы без ключа и перестал читать
                // группу — молча, потому что для него это выглядело бы как «нет сообщений».
                is DeviceKeysResult.Refused ->
                    return RotateStep.Refused("не удалось узнать устройства участника: ${answer.code}")
            }
        }
        val all = recipients.distinctBy { it.deviceId }
        if (all.isEmpty()) return RotateStep.Refused("ни у одного участника нет устройств")

        val current = when (val answer = groupKeys.mine(groupId, book.latestVersion(groupId) ?: 0)) {
            is GroupKeysResult.Keys -> answer.currentVersion
            is GroupKeysResult.NoConnection -> return RotateStep.Offline(answer.link.retryDelayMs)
            is GroupKeysResult.Refused -> return RotateStep.Refused(answer.code)
        }

        val epochKey = when (val outcome = escrow.keyForChat(groupId)) {
            is EscrowKeyResult.Keys -> outcome.current
            EscrowKeyResult.NoEnclave -> return RotateStep.Refused("сервер без анклава escrow")
            is EscrowKeyResult.Offline -> return RotateStep.Offline(outcome.link.retryDelayMs)
            is EscrowKeyResult.Refused -> return RotateStep.Refused("ключ эпохи: ${outcome.code}")
        }
        val verified = EscrowKeyVerifier.verify(
            enclaveSigningPub = enclave,
            id = epochKey.id,
            region = epochKey.region,
            chatId = epochKey.chatId,
            epoch = epochKey.epoch,
            publicKey = epochKey.publicKey,
            signature = epochKey.signature,
            validFromMs = epochKey.validFromMs,
            validToMs = epochKey.validToMs,
            destroyAtMs = epochKey.destroyAtMs,
            nowMs = msNow(),
        ).getOrElse {
            // Не «повторим позже»: подпись не сошлась — это подмена или наша ошибка.
            return RotateStep.Refused("подпись анклава не сошлась: ${it.message}")
        }

        val issue = GroupKeyRotations(verified).rotate(current, all).getOrElse {
            return RotateStep.Refused("не удалось выпустить ключ: ${it.message}")
        }

        val send = groupKeys.rotate(
            groupId = groupId,
            gkVersion = issue.gkVersion,
            senderEphemeralPub = issue.senderEphemeralPub,
            escrowMlkemCt = issue.escrowMlkemCt,
            escrowWrappedKey = issue.escrowWrappedKey,
            escrowKeyVersion = issue.escrowKeyVersion,
            wrappedKeys = issue.wrappedKeys,
            reason = "member_change",
        )

        return when (send) {
            RotateResult.Rotated -> {
                // Только теперь — и своим ключом мы будем шифровать исходящее.
                book.put(groupId, issue.gkVersion, issue.groupKey)
                RotateStep.Rotated
            }
            RotateResult.VersionConflict -> RotateStep.VersionConflict
            RotateResult.NotAdmin -> RotateStep.NotAdmin

            // Состав у нас устарел: в получателях оказалось устройство не-участника.
            // Это не отказ в правах, а повод перечитать состав и повторить.
            RotateResult.StaleMembers -> RotateStep.Refused("состав изменился — перечитайте и повторите")
            is RotateResult.Refused -> RotateStep.Refused(send.code)
            is RotateResult.NoConnection -> RotateStep.Offline(send.link.retryDelayMs)
        }
    }
}
