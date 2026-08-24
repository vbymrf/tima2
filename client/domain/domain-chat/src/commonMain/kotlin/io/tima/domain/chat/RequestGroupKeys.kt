package io.tima.domain.chat

/**
 * Попросить недостающие версии группового ключа у тех, у кого они есть.
 *
 * **Зачем это человеку.** Устройство, добавленное в группу после ротации, обёрток
 * прошлых версий не получит никогда: сервер раскладывает их только тем, кто был в
 * получателях на момент ротации. Поэтому часть истории у нового участника лежит
 * нечитаемой — законно, без всякой поломки. Единственный способ прочесть её — попросить
 * у участника, у которого ключ есть.
 *
 * **Почему это делает человек, а не приложение само.** Просьба уходит чужим устройствам и
 * означает «дайте мне историю до моего прихода». Делать это молча, фоном, за человека —
 * значит решать за него, что он хочет читать чужую переписку до себя, и одновременно
 * будить чужие устройства без его ведома.
 */
class RequestGroupKeys(private val recovery: GroupKeyRecovery) {

    /**
     * @return что сказать человеку. Ноль помощников — не ошибка: значит, нужных версий нет
     *   ни у кого из участников, и ждать бесполезно.
     */
    /**
     * @param фраза двенадцать слов аккаунта. `null` — пробуем без подписи: у аккаунта
     *   без секретной фразы восстановление идёт по членству. Слова сюда попадают из
     *   поля ввода и дальше не сохраняются нигде: держать их значило бы отдать вместе с
     *   устройством и заслон против угона номера.
     */
    suspend fun запросить(groupId: String, фраза: List<String>? = null): RequestKeysStep =
        when (val ответ = recovery.request(groupId, фраза)) {
            is RecoveryStep.Requested -> when {
                ответ.versions == 0 -> RequestKeysStep.NothingMissing
                ответ.helpers == 0 -> RequestKeysStep.NoHelpers
                else -> RequestKeysStep.Asked(ответ.helpers)
            }
            RecoveryStep.NeedsSecretPhrase -> RequestKeysStep.NeedsSecretPhrase
            RecoveryStep.NotMember -> RequestKeysStep.NotMember
            is RecoveryStep.Offline -> RequestKeysStep.Offline(ответ.retryAfterMs)
            is RecoveryStep.Refused -> RequestKeysStep.Refused(ответ.reason)
        }
}

/** Порт запроса ключей. Реализуется `core-network`. */
fun interface GroupKeyRecovery {
    /** @param фраза слова аккаунта для подписи запроса; `null` — без подписи. */
    suspend fun request(groupId: String, фраза: List<String>?): RecoveryStep
}

sealed interface RecoveryStep {
    data class Requested(val versions: Int, val helpers: Int) : RecoveryStep

    /** У аккаунта заведена секретная фраза: без подписи ею историю не отдадут. */
    data object NeedsSecretPhrase : RecoveryStep
    data object NotMember : RecoveryStep
    data class Offline(val retryAfterMs: Long) : RecoveryStep
    data class Refused(val reason: String) : RecoveryStep
}

/** Что показать человеку после нажатия «запросить ключ». */
sealed interface RequestKeysStep {
    /** Просьба ушла [устройствам] участникам. Ключи приедут, когда кто-то из них ответит. */
    data class Asked(val устройствам: Int) : RequestKeysStep

    /** Недостающих версий нет — значит, дело не в ключе. */
    data object NothingMissing : RequestKeysStep

    /**
     * Просить некого: ни у кого из участников этих версий нет.
     *
     * Отдельный исход, а не «ошибка»: ждать бесполезно, и человеку надо сказать именно
     * это, а не «попробуйте позже».
     */
    data object NoHelpers : RequestKeysStep

    data object NeedsSecretPhrase : RequestKeysStep
    data object NotMember : RequestKeysStep
    data class Offline(val retryAfterMs: Long) : RequestKeysStep
    data class Refused(val reason: String) : RequestKeysStep
}
