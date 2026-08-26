package io.tima.domain.account

/**
 * Заведение устройства — К4.3, склейка без экранов.
 *
 * **Почему это Domain, а не сетевой слой.** Порядок шагов здесь — правило продукта, а
 * не свойство HTTP: что порождается раньше, что записывается до вызова, когда можно
 * перезаписать чужой секрет. Оставь этот порядок в сетевом слое, и его придётся
 * повторить в каждом входе — на экране, в харнессе, в отладочной команде.
 *
 * Слой ничего не знает ни о Ktor, ни о Keychain: всё приходит портами
 * ([AccountApi], [DeviceKeyFactory], [DeviceSecretStore]), а реализуют их модули,
 * владеющие своей техникой. То же направление, что у `core-database`, реализующего
 * `OutboxStore`.
 */
class RegisterDevice(
    private val api: AccountApi,
    private val keys: DeviceKeyFactory,
    private val secrets: DeviceSecretStore,
    /** Показывается человеку в списке его устройств. Сервер значение не проверяет. */
    private val platform: String,
) {

    /**
     * Шаг первый: попросить код.
     *
     * @return [CodeRequested.devCode] заполнен только на стенде с `TIMA_DEV_SMS`.
     *   Харнесс К4 живёт этим: иначе сквозной путь требовал бы настоящей SMS.
     */
    suspend fun requestCode(phone: String): CodeRequestStep = api.requestCode(phone)

    /**
     * Шаги второй и третий: проверить код и завести устройство.
     *
     * Объединены намеренно. `registration_token` живёт минуты и не нужен никому,
     * кроме следующего вызова; отдать его наружу значило бы попросить вызывающего
     * подержать секрет, у которого нет других применений.
     *
     * @param identityPub ключ личности из фразы аккаунта — для возврата на новом
     *   устройстве. `null` для нового аккаунта.
     * @param replaceExisting разрешить перезапись уже заведённого устройства. По
     *   умолчанию **запрещено**: случайный повторный вызов оставил бы на сервере
     *   устройство, к которому у нас больше нет закрытого ключа, — то есть тихого
     *   зомби в списке устройств человека.
     * @param forceNewIdentity «Начать заново»: у человека нет прежней фразы, и он согласен,
     *   что прежняя переписка станет недоступна. Собеседники увидят предупреждение о смене
     *   личности, поэтому флаг ставится **только по прямому подтверждению**.
     */
    suspend fun confirm(
        requestId: String,
        code: String,
        identityPub: ByteArray? = null,
        replaceExisting: Boolean = false,
        forceNewIdentity: Boolean = false,
    ): RegistrationStep {
        if (!replaceExisting && secrets.hasDevice()) {
            return RegistrationStep.AlreadyRegistered
        }

        when (val check = api.submitCode(requestId, code)) {
            is CodeSubmitStep.Accepted ->
                return create(check.registrationToken, identityPub, forceNewIdentity)
            CodeSubmitStep.WrongCode -> return RegistrationStep.WrongCode
            is CodeSubmitStep.Offline -> return RegistrationStep.Offline(check.retryAfterMs)
            is CodeSubmitStep.Refused -> return RegistrationStep.Refused(check.reason)
        }
    }

    /**
     * Продолжить с УЖЕ полученным `registration_token`, не трогая код.
     *
     * **Код одноразовый**, и это правильно: он гасится в `/auth/verify` первым же
     * обращением. Отсюда следует, что второй шаг того же входа — возврат по фразе или
     * «начать заново» — обязан идти с токеном, а не с кодом.
     *
     * Найдено живым прогоном: вход по фразе отвечал «код неверен или просрочен» и не мог
     * сработать никогда. Симптом указывал на срок, а причина была в повторном
     * использовании: первый confirm сжигал код, второй приходил с ним же.
     */
    suspend fun continueWithToken(
        registrationToken: String,
        identityPub: ByteArray? = null,
        forceNewIdentity: Boolean = false,
    ): RegistrationStep = create(registrationToken, identityPub, forceNewIdentity)

    private suspend fun create(
        registrationToken: String,
        identityPub: ByteArray?,
        forceNewIdentity: Boolean,
    ): RegistrationStep {
        val material = keys.newDeviceKeys()

        // Секрет пишется ДО вызова, и это главное решение здесь.
        //
        // Умри процесс между «сервер завёл устройство» и «мы сохранили закрытый ключ» —
        // и устройство останется на сервере навсегда без ключа: расшифровать
        // адресованное ему нельзя, снять его человек может только вручную, а выглядит
        // это как «сообщения не приходят». Обратный порядок стоит ровно ничего: секрет
        // от неудавшейся регистрации перезапишется следующей попыткой.
        secrets.saveDeviceSecret(material.secret)

        return when (val answer = api.createDevice(
            registrationToken = registrationToken,
            encryptionPub = material.encryptionPub,
            signingPub = material.signingPub,
            identityPub = identityPub,
            platform = platform,
            forceNewIdentity = forceNewIdentity,
        )) {
            is DeviceCreateStep.Created -> {
                // Токен — после успеха: до него он не существует, а его наличие и есть
                // признак «устройство заведено».
                secrets.saveSession(
                    Session(userId = answer.userId, deviceId = answer.deviceId, accessToken = answer.accessToken),
                )
                RegistrationStep.Registered(answer.userId, answer.deviceId)
            }
            // Телефон связан с другой личностью. Секрет оставляем: ключи ещё понадобятся
            // тому пути, который человек выберет дальше (возврат по фразе).
            // Токен отдаётся наружу: следующий шаг (фраза или «начать заново») пойдёт с
            // ним, потому что кода больше нет — он погашен при проверке.
            DeviceCreateStep.IdentityMismatch -> RegistrationStep.IdentityMismatch(registrationToken)
            DeviceCreateStep.TokenExpired -> RegistrationStep.CodeExpired
            is DeviceCreateStep.Offline -> RegistrationStep.Offline(answer.retryAfterMs)
            is DeviceCreateStep.Refused -> RegistrationStep.Refused(answer.reason)
        }
    }
}

/** Чем закончился запрос кода. */
sealed interface CodeRequestStep {
    data class CodeRequested(val requestId: String, val devCode: String? = null) : CodeRequestStep

    /** Телефон не в формате E.164 — отсечён до сети. */
    data class BadPhone(val reason: String) : CodeRequestStep
    data class Offline(val retryAfterMs: Long) : CodeRequestStep
    data class Refused(val reason: String) : CodeRequestStep
}

/** Чем закончилось заведение устройства целиком. */
sealed interface RegistrationStep {
    data class Registered(val userId: String, val deviceId: String) : RegistrationStep

    /** Устройство уже заведено, и перезапись не разрешена. */
    data object AlreadyRegistered : RegistrationStep

    /** Код неверен, просрочен или уже использован — сервер эти три не различает. */
    data object WrongCode : RegistrationStep

    /** `registration_token` истёк: начинать с запроса кода. */
    data object CodeExpired : RegistrationStep

    /**
     * Телефон связан с другой личностью: путь возврата по фразе, а не ошибка.
     *
     * @param registrationToken то, с чем идти дальше. Код к этому моменту уже погашен
     *   проверкой и второй раз не сработает — это и ломало вход по фразе.
     */
    data class IdentityMismatch(val registrationToken: String) : RegistrationStep
    data class Offline(val retryAfterMs: Long) : RegistrationStep
    data class Refused(val reason: String) : RegistrationStep
}

/** Что вернула проверка кода. */
sealed interface CodeSubmitStep {
    data class Accepted(val registrationToken: String) : CodeSubmitStep
    data object WrongCode : CodeSubmitStep
    data class Offline(val retryAfterMs: Long) : CodeSubmitStep
    data class Refused(val reason: String) : CodeSubmitStep
}

/** Что вернуло заведение устройства. */
sealed interface DeviceCreateStep {
    data class Created(val userId: String, val deviceId: String, val accessToken: String) : DeviceCreateStep
    data object IdentityMismatch : DeviceCreateStep
    data object TokenExpired : DeviceCreateStep
    data class Offline(val retryAfterMs: Long) : DeviceCreateStep
    data class Refused(val reason: String) : DeviceCreateStep
}

/**
 * Порт к серверу. Реализуется в `core-network` — там, где живёт HTTP.
 *
 * Типы здесь **свои, а не сетевые**: правило слоёв не украшение, а то, из-за чего
 * правка адреса ручки не становится правкой правил продукта.
 */
interface AccountApi {
    suspend fun requestCode(phone: String): CodeRequestStep
    suspend fun submitCode(requestId: String, code: String): CodeSubmitStep
    suspend fun createDevice(
        registrationToken: String,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        identityPub: ByteArray?,
        platform: String,
        forceNewIdentity: Boolean = false,
    ): DeviceCreateStep
}

/** Ключи устройства. Порождает `core-encryption`. */
data class DeviceKeyMaterial(
    /** X25519, 32 байта — для расшифровки адресованного этому устройству. */
    val encryptionPub: ByteArray,
    /** Ed25519, 32 байта — им подписаны исходящие. */
    val signingPub: ByteArray,
    /**
     * Закрытая часть, 32 байта. Из неё выводятся оба ключа выше и ключ покоя базы.
     * Потеря невосстановима — отсюда порядок записи в [RegisterDevice].
     */
    val secret: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is DeviceKeyMaterial &&
        encryptionPub.contentEquals(other.encryptionPub) &&
        signingPub.contentEquals(other.signingPub) &&
        secret.contentEquals(other.secret)

    override fun hashCode(): Int {
        var h = encryptionPub.contentHashCode()
        h = 31 * h + signingPub.contentHashCode()
        h = 31 * h + secret.contentHashCode()
        return h
    }
}

/** Порт к криптографии. */
fun interface DeviceKeyFactory {
    fun newDeviceKeys(): DeviceKeyMaterial
}

/**
 * Личность аккаунта — **секретная фраза**. Реализуется `core-encryption` над
 * `AccountMnemonic` (ADR-0010).
 *
 * Зачем она вообще: устройство теряется, и без личности аккаунта доказать серверу, что
 * аккаунт твой, нечем — телефон подтверждает только номер, а номер бывает перевыпущен.
 * Фраза же выводит тот же ключ на любом устройстве и не хранится нигде, кроме головы и
 * бумажки человека.
 *
 * **Слова — секрет.** Их не пишут в журнал, не сохраняют в хранилище и не отправляют на
 * сервер: серверу уходит только публичная часть.
 */
interface AccountIdentities {

    /** Новая личность: слова человеку, публичный ключ серверу. */
    fun fresh(): NewAccountIdentity

    /**
     * Возврат по фразе.
     *
     * @return публичный ключ личности, либо `null` — фраза не та: не то число слов, слово
     *   не из списка, контрольная сумма не сошлась. Различать эти случаи человеку незачем,
     *   ему надо перепроверить фразу.
     */
    fun fromWords(words: List<String>): ByteArray?
}

/** Свежая личность аккаунта: слова показать один раз, публичный ключ отправить серверу. */
class NewAccountIdentity(val words: List<String>, val identityPub: ByteArray)

/** Кто мы для сервера после регистрации. */
data class Session(val userId: String, val deviceId: String, val accessToken: String)

/**
 * Порт к хранилищу секретов. Реализуется в `core-secrets` — Keychain, DPAPI,
 * Keystore.
 */
interface DeviceSecretStore {
    /**
     * Есть ли уже заведённое устройство — то есть **есть ли сессия**.
     *
     * Одного секрета для этого мало: он пишется до вызова сервера (см. [RegisterDevice]),
     * и без сессии остался от прерванной попытки, которую следующая перезапишет. Ответь
     * здесь «секрет есть — значит заведено», и вход по секретной фразе оборвётся на
     * «Устройство уже заведено»: фразу вводят как раз после неудавшейся попытки, чей
     * секрет уже лежит в хранилище.
     */
    fun hasDevice(): Boolean
    fun saveDeviceSecret(secret: ByteArray)
    fun saveSession(session: Session)
    fun session(): Session?
}
