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
class РотацияГрупповогоКлюча(
    private val группы: GroupsApi,
    private val ключиУстройств: KeysApi,
    private val escrow: EscrowApi,
    private val ключиГрупп: GroupKeysApi,
    private val книга: GroupKeyBook,
    private val сейчасМс: () -> Long,
) : GroupKeyRotator {

    override suspend fun ротировать(groupId: String): RotateStep {
        val анклав = EscrowTrust.enclaveSigningPub
            ?: return RotateStep.Refused("нет ключа подписи анклава: ротация отказана")

        val участники = when (val ответ = группы.members(groupId)) {
            is MembersResult.Members -> ответ.members.map { it.userId }
            is MembersResult.NoConnection -> return RotateStep.Offline(ответ.link.retryDelayMs)
            is MembersResult.Refused -> return RotateStep.Refused(ответ.code)
        }
        if (участники.isEmpty()) {
            // Группа без участников ключа не требует, и ротация без получателей оставила бы
            // версию, которой ни у кого нет.
            return RotateStep.Refused("в группе нет участников: ротировать не для кого")
        }

        val получатели = mutableListOf<RecipientDevice>()
        for (кто in участники) {
            when (val ответ = ключиУстройств.devicesOf(кто)) {
                is DeviceKeysResult.Devices ->
                    получатели += ответ.devices.map { RecipientDevice(it.deviceId, it.encryptionPub) }

                is DeviceKeysResult.Offline -> return RotateStep.Offline(ответ.link.retryDelayMs)

                // Пропустить участника нельзя: он остался бы без ключа и перестал читать
                // группу — молча, потому что для него это выглядело бы как «нет сообщений».
                is DeviceKeysResult.Refused ->
                    return RotateStep.Refused("не удалось узнать устройства участника: ${ответ.code}")
            }
        }
        val все = получатели.distinctBy { it.deviceId }
        if (все.isEmpty()) return RotateStep.Refused("ни у одного участника нет устройств")

        val текущая = when (val ответ = ключиГрупп.mine(groupId, книга.latestVersion(groupId) ?: 0)) {
            is GroupKeysResult.Keys -> ответ.currentVersion
            is GroupKeysResult.NoConnection -> return RotateStep.Offline(ответ.link.retryDelayMs)
            is GroupKeysResult.Refused -> return RotateStep.Refused(ответ.code)
        }

        val ключЭпохи = when (val исход = escrow.keyForChat(groupId)) {
            is EscrowKeyResult.Keys -> исход.current
            EscrowKeyResult.NoEnclave -> return RotateStep.Refused("сервер без анклава escrow")
            is EscrowKeyResult.Offline -> return RotateStep.Offline(исход.link.retryDelayMs)
            is EscrowKeyResult.Refused -> return RotateStep.Refused("ключ эпохи: ${исход.code}")
        }
        val проверенный = EscrowKeyVerifier.verify(
            enclaveSigningPub = анклав,
            id = ключЭпохи.id,
            region = ключЭпохи.region,
            chatId = ключЭпохи.chatId,
            epoch = ключЭпохи.epoch,
            publicKey = ключЭпохи.publicKey,
            signature = ключЭпохи.signature,
            validFromMs = ключЭпохи.validFromMs,
            validToMs = ключЭпохи.validToMs,
            destroyAtMs = ключЭпохи.destroyAtMs,
            nowMs = сейчасМс(),
        ).getOrElse {
            // Не «повторим позже»: подпись не сошлась — это подмена или наша ошибка.
            return RotateStep.Refused("подпись анклава не сошлась: ${it.message}")
        }

        val выпуск = GroupKeyRotations(проверенный).rotate(текущая, все).getOrElse {
            return RotateStep.Refused("не удалось выпустить ключ: ${it.message}")
        }

        val отправка = ключиГрупп.rotate(
            groupId = groupId,
            gkVersion = выпуск.gkVersion,
            senderEphemeralPub = выпуск.senderEphemeralPub,
            escrowMlkemCt = выпуск.escrowMlkemCt,
            escrowWrappedKey = выпуск.escrowWrappedKey,
            escrowKeyVersion = выпуск.escrowKeyVersion,
            wrappedKeys = выпуск.wrappedKeys,
            reason = "member_change",
        )

        return when (отправка) {
            RotateResult.Rotated -> {
                // Только теперь — и своим ключом мы будем шифровать исходящее.
                книга.put(groupId, выпуск.gkVersion, выпуск.groupKey)
                RotateStep.Rotated
            }
            RotateResult.VersionConflict -> RotateStep.VersionConflict
            RotateResult.NotAdmin -> RotateStep.NotAdmin

            // Состав у нас устарел: в получателях оказалось устройство не-участника.
            // Это не отказ в правах, а повод перечитать состав и повторить.
            RotateResult.StaleMembers -> RotateStep.Refused("состав изменился — перечитайте и повторите")
            is RotateResult.Refused -> RotateStep.Refused(отправка.code)
            is RotateResult.NoConnection -> RotateStep.Offline(отправка.link.retryDelayMs)
        }
    }
}
