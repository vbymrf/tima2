package io.tima.domain.account

/**
 * Что человек делает с доверием к своим устройствам на экране «Устройства»
 * (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ5).
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

    /** Код заверения этого устройства для QR (Р32); `null` — не знаем своего `device_id`. */
    suspend fun certifyCode(): String? = null

    /**
     * Заверить устройство по отсканированному коду (Р32): код сверяется с ключами устройства
     * у сервера в своём аккаунте, подписывает ключ подписи устройств этого телефона.
     */
    suspend fun certifyByCode(code: String): TrustStep = TrustStep.Refused("не умеем")

    /** Закрыто ли «Начать заново» на аккаунте (ДУ10, Р41); `null` — не узнали. */
    suspend fun startAnewBanned(): Boolean? = null

    /** SMS на номер аккаунта — для запрета «Начать заново». `null` — не ушло. */
    suspend fun sendBanCode(): BanCode? = null

    /**
     * Закрыть «Начать заново» навсегда (Р41): фраза доказывает личность, код из SMS — что
     * номер сейчас у того же человека. Снять запрет нельзя.
     */
    suspend fun banStartAnew(words: List<String>, requestId: String, code: String): TrustStep =
        TrustStep.Refused("не умеем")

    /** Отключили своё устройство — пора сменить ключ копии (модель Matrix, М5). */
    suspend fun copyRotationDue(): Boolean = false

    /** Перевести копию ключей на новую пару — фразой (М5). */
    suspend fun rotateCopy(words: List<String>): TrustStep = TrustStep.Refused("не умеем")

    /** Копия ключей у личности ещё не заведена (Р44: она обязана быть) — `null`, не узнали. */
    suspend fun copyMissing(): Boolean? = null

    /** Завести копию ключей фразой — для устройств, которые фразу больше не вводят (Р44). */
    suspend fun startCopy(words: List<String>): TrustStep = TrustStep.Refused("не умеем")

    /** Перерегистрация глазами этой личности (ДУ9); `null` — не узнали, нет — `Rereg.NONE`. */
    suspend fun rereg(): Rereg? = null

    /**
     * Подготовить перерегистрацию (ДУ9, Р34): фраза текущей личности сверена с аккаунтом, вызов
     * подписан. Дальше — вход тем же номером: код из SMS и новая личность с новой фразой.
     */
    suspend fun prepareRereg(words: List<String>): PrepareRereg = PrepareRereg.Failed(TrustStep.Refused("не умеем"))

    /**
     * Код из SMS на номер аккаунта для шага перерегистрации (Р53): у каждого шага свой предел.
     * [purpose] — `rereg_claim`, `rereg_confirm_new`, `rereg_confirm_old`.
     */
    suspend fun sendCode(purpose: String): CodeSend = sendBanCode()?.let { CodeSend.Sent(it) } ?: CodeSend.Failed

    /** «Аккаунт украден» (Р34): фраза С и код из SMS на номер аккаунта. */
    suspend fun claimRereg(words: List<String>, requestId: String, code: String): TrustStep = TrustStep.Refused("не умеем")

    /** Подтверждение в окне (Р34): Н — обе фразы ([oldWords] — прежняя), С — своя. */
    suspend fun confirmRereg(words: List<String>, oldWords: List<String>?, requestId: String, code: String): TrustStep =
        TrustStep.Refused("не умеем")
}

/** Перерегистрация глазами стороны (ДУ9). Времена — мс. */
data class Rereg(
    val active: Boolean,
    /** Эта личность — заведённая перерегистрацией (Н); иначе прежняя (С). */
    val isNew: Boolean = false,
    val windowFrom: Long = 0,
    val windowTo: Long = 0,
    val disputed: Boolean = false,
    val confirmed: Boolean = false,
    val round: Int = 0,
) {
    companion object {
        val NONE = Rereg(active = false)
    }
}

/** Чем кончилась отправка кода. */
sealed interface CodeSend {
    class Sent(val code: BanCode) : CodeSend

    /** Предел SMS на номер исчерпан — не «нет связи», а «подождите». */
    data object Limited : CodeSend

    data object Failed : CodeSend
}

/** Чем кончилась подготовка перерегистрации. */
sealed interface PrepareRereg {
    /** Можно входить тем же номером [phone] с доказательством [proof]. */
    class Ready(val phone: String, val proof: ReregProof) : PrepareRereg

    class Failed(val step: TrustStep) : PrepareRereg
}

/** Отправленный код запрета (ДУ10). `devCode` — код в ответе стенда (`TIMA_DEV_SMS`). */
data class BanCode(val requestId: String, val devCode: String? = null)

/** Чем кончилось действие доверия. */
sealed interface TrustStep {
    data object Done : TrustStep

    /** Слова не складываются в личность или это личность не этого аккаунта. */
    data object WrongPhrase : TrustStep

    /** У этого телефона нет своего ключа подписи устройств — сначала фраза. */
    data object NoKey : TrustStep

    /** Отменять нечего: заново с номера не начинали или уже отменено. */
    data object NothingToCancel : TrustStep

    /** Код из SMS неверен или просрочен. */
    data object WrongCode : TrustStep
    data class Offline(val retryAfterMs: Long) : TrustStep
    data class Refused(val reason: String) : TrustStep
}
