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
     */
    suspend fun confirm(
        requestId: String,
        code: String,
        identityPub: ByteArray? = null,
        replaceExisting: Boolean = false,
    ): RegistrationStep {
        if (!replaceExisting && secrets.hasDevice()) {
            return RegistrationStep.AlreadyRegistered
        }

        when (val проверка = api.submitCode(requestId, code)) {
            is CodeSubmitStep.Accepted -> return завести(проверка.registrationToken, identityPub)
            CodeSubmitStep.WrongCode -> return RegistrationStep.WrongCode
            is CodeSubmitStep.Offline -> return RegistrationStep.Offline(проверка.retryAfterMs)
            is CodeSubmitStep.Refused -> return RegistrationStep.Refused(проверка.reason)
        }
    }

    private suspend fun завести(
        registrationToken: String,
        identityPub: ByteArray?,
    ): RegistrationStep {
        val материал = keys.newDeviceKeys()

        // Секрет пишется ДО вызова, и это главное решение здесь.
        //
        // Умри процесс между «сервер завёл устройство» и «мы сохранили закрытый ключ» —
        // и устройство останется на сервере навсегда без ключа: расшифровать
        // адресованное ему нельзя, снять его человек может только вручную, а выглядит
        // это как «сообщения не приходят». Обратный порядок стоит ровно ничего: секрет
        // от неудавшейся регистрации перезапишется следующей попыткой.
        secrets.saveDeviceSecret(материал.secret)

        return when (val ответ = api.createDevice(
            registrationToken = registrationToken,
            encryptionPub = материал.encryptionPub,
            signingPub = материал.signingPub,
            identityPub = identityPub,
            platform = platform,
        )) {
            is DeviceCreateStep.Created -> {
                // Токен — после успеха: до него он не существует, а его наличие и есть
                // признак «устройство заведено».
                secrets.saveSession(
                    Session(userId = ответ.userId, deviceId = ответ.deviceId, accessToken = ответ.accessToken),
                )
                RegistrationStep.Registered(ответ.userId, ответ.deviceId)
            }
            // Телефон связан с другой личностью. Секрет оставляем: ключи ещё понадобятся
            // тому пути, который человек выберет дальше (возврат по фразе).
            DeviceCreateStep.IdentityMismatch -> RegistrationStep.IdentityMismatch
            DeviceCreateStep.TokenExpired -> RegistrationStep.CodeExpired
            is DeviceCreateStep.Offline -> RegistrationStep.Offline(ответ.retryAfterMs)
            is DeviceCreateStep.Refused -> RegistrationStep.Refused(ответ.reason)
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

    /** Телефон связан с другой личностью: путь возврата по фразе, а не ошибка. */
    data object IdentityMismatch : RegistrationStep
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

/** Кто мы для сервера после регистрации. */
data class Session(val userId: String, val deviceId: String, val accessToken: String)

/**
 * Порт к хранилищу секретов. Реализуется в `core-secrets` — Keychain, DPAPI,
 * Keystore.
 */
interface DeviceSecretStore {
    /** Есть ли уже заведённое устройство. */
    fun hasDevice(): Boolean
    fun saveDeviceSecret(secret: ByteArray)
    fun saveSession(session: Session)
    fun session(): Session?
}
