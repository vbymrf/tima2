package io.tima.domain.account

/**
 * Что человек делает с доверием к своим устройствам на экране «Устройства»
 * (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ДУ5).
 *
 * Устройства, заведённые до ДУ1, ничем не заверены. Заверить их без фразы нельзя — иначе
 * заверять умел бы и вор: фраза доказывает, что это хозяин. Поэтому путь такой: на телефоне
 * человек вводит фразу — телефон заводит свой ключ подписи устройств и заверяет себя; дальше
 * он заверяет другие устройства **по выбору человека**, а не всё подряд из списка сервера.
 */
interface DeviceTrustActions {

    /** Это устройство держит ли свой ключ подписи устройств — может ли заверять другие. */
    fun holdsKey(): Boolean

    /** Подтвердить это устройство фразой: телефон заводит ключ, ПК заверяется ключом личности. */
    suspend fun confirmWithPhrase(words: List<String>): TrustStep

    /** Заверить другое своё устройство ключом этого телефона. */
    suspend fun certify(deviceId: String): TrustStep

    /**
     * С номера начали заново, а это устройство — прежней личности (ДУ6): отменить новые
     * личности аккаунта, подтвердив фразой (Р27, Р31).
     */
    suspend fun cancelNewIdentity(words: List<String>): TrustStep = TrustStep.Refused("не умеем")

    /** Начинали ли с номера заново после этой личности — и не отменено ли это (ДУ6). */
    suspend fun replaced(): Boolean = false
}

/** Чем кончилось действие доверия. */
sealed interface TrustStep {
    data object Done : TrustStep

    /** Слова не складываются в личность или это личность не этого аккаунта. */
    data object WrongPhrase : TrustStep

    /** У этого телефона нет своего ключа подписи устройств — сначала фраза. */
    data object NoKey : TrustStep

    /** Отменять нечего: заново с номера не начинали или уже отменено. */
    data object NothingToCancel : TrustStep
    data class Offline(val retryAfterMs: Long) : TrustStep
    data class Refused(val reason: String) : TrustStep
}
