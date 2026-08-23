package io.tima.domain.account

import kotlinx.coroutines.delay

/**
 * Привязка устройства по коду — **два случая использования, по одному на роль**.
 *
 * Роли не симметричны, и это правило продукта, а не устройство API:
 *
 * - [LinkNewDevice] — новое устройство. Аккаунта у него нет, доказывать ему нечем;
 *   всё, что он делает, — показывает код и ждёт. Ключи и секрет порождаются здесь,
 *   в том же порядке, что при регистрации: **секрет пишется до вызова сервера**.
 * - [ConfirmDeviceLink] — уже доверенное устройство. Оно единственное, кто вносит
 *   доверие, и сервер разрешает это **только телефону** (`key-lifecycle.md §2`):
 *   якорь доверия аттестуемый, ПК своё доверие наследует, а дальше не раздаёт.
 *
 * **Чего у привязанного устройства не будет.** Истории переписок. Ключи сообщений
 * оборачивались на устройства, существовавшие в момент отправки, и новое среди них
 * не значилось; перезавернуть их обязано подтвердившее устройство (ADR-0010, этап 2),
 * и это отдельная работа. Пока привязанное устройство начинает с пустого списка — и
 * сказать это человеку надо заранее, иначе он прочтёт пустоту как потерю переписки.
 */
class LinkNewDevice(
    private val api: DeviceLinkStart,
    private val keys: DeviceKeyFactory,
    private val secrets: DeviceSecretStore,
) {

    /**
     * Попросить код.
     *
     * Секрет устройства пишется **до** вызова сервера — та же причина, что в
     * [RegisterDevice]: умри процесс между «сервер завёл устройство» и «мы сохранили
     * закрытый ключ», и устройство останется на сервере навсегда без ключа. Секрет от
     * неудавшейся попытки не стоит ничего: следующая перезапишет.
     *
     * @param deviceName как устройство назовёт себя человеку на том конце. Он увидит это
     *   имя перед подтверждением, и «Устройство» ему ничего не скажет.
     */
    suspend fun begin(deviceName: String): LinkBeginStep {
        if (secrets.hasDevice()) return LinkBeginStep.AlreadyRegistered

        val материал = keys.newDeviceKeys()
        secrets.saveDeviceSecret(материал.secret)

        return when (val ответ = api.start(материал.encryptionPub, материал.signingPub, deviceName)) {
            is LinkStartStep.Started -> LinkBeginStep.ShowCode(
                sessionId = ответ.sessionId,
                code = ответ.qrPayload,
                claimToken = ответ.claimToken,
            )
            is LinkStartStep.Offline -> LinkBeginStep.Offline(ответ.retryAfterMs)
            is LinkStartStep.Refused -> LinkBeginStep.Refused(ответ.reason)
        }
    }

    /**
     * Ждать подтверждения.
     *
     * Опрос, а не живой канал: канала у неавторизованного устройства нет — его нечем
     * авторизовать. Раз в две секунды и не дольше срока сессии: сервер сам так и задуман
     * (`link/claim` держит лимит под 150 попыток на сессию), и опрашивать чаще значило бы
     * тратить лимит, который понадобится второй попытке.
     *
     * Отказ связи ожидание **не прерывает**: код на экране, человек с телефоном рядом, и
     * упавшая на секунду сеть — не причина заставлять его начинать заново.
     */
    suspend fun await(sessionId: String, claimToken: String): LinkAwaitStep {
        var прошло = 0L
        while (прошло < СРОК_СЕССИИ_МС) {
            when (val ответ = api.claim(sessionId, claimToken)) {
                is LinkClaimStep.Claimed -> {
                    // Токен — после успеха, как при регистрации: его наличие и есть
                    // признак «устройство заведено».
                    secrets.saveSession(
                        Session(userId = ответ.userId, deviceId = ответ.deviceId, accessToken = ответ.accessToken),
                    )
                    return LinkAwaitStep.Linked(ответ.userId, ответ.deviceId)
                }
                LinkClaimStep.NotReady -> Unit
                is LinkClaimStep.Offline -> Unit
                is LinkClaimStep.Refused -> return LinkAwaitStep.Refused(ответ.reason)
            }
            delay(МЕЖДУ_ОПРОСАМИ_МС)
            прошло += МЕЖДУ_ОПРОСАМИ_МС
        }
        return LinkAwaitStep.Expired
    }

    private companion object {
        /** Сервер держит сессию привязки пять минут (`linkSessionTTL`). */
        const val СРОК_СЕССИИ_МС = 5 * 60 * 1000L
        const val МЕЖДУ_ОПРОСАМИ_МС = 2_000L
    }
}

/**
 * Подтверждение привязки доверенным устройством.
 *
 * Подпись делается над данными **из кода**, а не над тем, что мы думаем о сессии: сервер
 * сверит подписанное с тем, что сам положил в сессию при `start`. Разойдись разбор кода с
 * действительностью — подпись не сойдётся, и это лучше тихой привязки не того устройства.
 */
class ConfirmDeviceLink(
    private val api: DeviceLinkConfirm,
    private val signer: LinkSigner,
) {

    /**
     * @param code строка из QR. Разбирать её умеет [DeviceLinkConfirm.parse] — реализация
     *   знает формат, потому что формат сетевой.
     */
    suspend fun confirm(code: String): LinkConfirmStep {
        val данные = api.parse(code) ?: return LinkConfirmStep.NotOurCode
        val подпись = signer.sign(
            sessionId = данные.sessionId,
            secret = данные.secret,
            encryptionPub = данные.encryptionPub,
            signingPub = данные.signingPub,
        ) ?: return LinkConfirmStep.CannotSign

        return api.confirm(данные.sessionId, данные.secret, подпись)
    }
}

// ── порты ───────────────────────────────────────────────────────────────────

/** Роль нового устройства: попросить код и спрашивать, подтвердили ли. Без авторизации. */
interface DeviceLinkStart {
    suspend fun start(encryptionPub: ByteArray, signingPub: ByteArray, deviceName: String): LinkStartStep
    suspend fun claim(sessionId: String, claimToken: String): LinkClaimStep
}

/** Роль доверенного устройства: разобрать код и подтвердить. Требует токена. */
interface DeviceLinkConfirm {
    /** @return `null` — код не наш или испорчен. */
    fun parse(code: String): LinkCode?
    suspend fun confirm(sessionId: String, secret: String, signature: ByteArray): LinkConfirmStep
}

/**
 * Разобранный код. Домену нужны ровно эти пять значений: четыре идут в подпись, пятое —
 * человеку.
 */
class LinkCode(
    val sessionId: String,
    val secret: String,
    val encryptionPub: ByteArray,
    val signingPub: ByteArray,
    val deviceName: String?,
)

/**
 * Подпись привязки ключом **этого** устройства. Реализует `core-encryption`.
 *
 * @return `null` — подписать нечем: ключа устройства нет. Не исключение, потому что это
 *   состояние, а не поломка: подтверждать привязку с устройства без ключа нельзя.
 */
interface LinkSigner {
    fun sign(sessionId: String, secret: String, encryptionPub: ByteArray, signingPub: ByteArray): ByteArray?
}

// ── исходы ──────────────────────────────────────────────────────────────────

/** Что вернул `link/start`. */
sealed interface LinkStartStep {
    data class Started(val sessionId: String, val qrPayload: String, val claimToken: String) : LinkStartStep
    data class Offline(val retryAfterMs: Long) : LinkStartStep
    data class Refused(val reason: String) : LinkStartStep
}

/** Что вернул `link/claim`. */
sealed interface LinkClaimStep {
    data class Claimed(val userId: String, val deviceId: String, val accessToken: String) : LinkClaimStep

    /** Ещё не подтвердили: ожидание, а не отказ. */
    data object NotReady : LinkClaimStep
    data class Offline(val retryAfterMs: Long) : LinkClaimStep
    data class Refused(val reason: String) : LinkClaimStep
}

/** Чем закончилась просьба показать код. */
sealed interface LinkBeginStep {
    data class ShowCode(val sessionId: String, val code: String, val claimToken: String) : LinkBeginStep

    /** Устройство уже заведено: привязывать нечего, надо входить. */
    data object AlreadyRegistered : LinkBeginStep
    data class Offline(val retryAfterMs: Long) : LinkBeginStep
    data class Refused(val reason: String) : LinkBeginStep
}

/** Чем закончилось ожидание подтверждения. */
sealed interface LinkAwaitStep {
    data class Linked(val userId: String, val deviceId: String) : LinkAwaitStep

    /** Срок кода вышел: показать новый. */
    data object Expired : LinkAwaitStep
    data class Refused(val reason: String) : LinkAwaitStep
}

/** Чем закончилось подтверждение. Каждый исход — своё действие человека. */
sealed interface LinkConfirmStep {
    data class Confirmed(val deviceId: String) : LinkConfirmStep

    /** Это не наш код: отсканировано что-то другое. */
    data object NotOurCode : LinkConfirmStep

    /** Подписать нечем: у этого устройства нет своего ключа. */
    data object CannotSign : LinkConfirmStep

    /** Подтверждать вправе только телефон. */
    data object NotAPhone : LinkConfirmStep

    /** Сессия просрочена или уже подтверждена: на том конце надо показать код заново. */
    data object SessionGone : LinkConfirmStep

    /** Подпись не сошлась: разобранный код расходится с тем, что лежит на сервере. */
    data object BadSignature : LinkConfirmStep
    data class Offline(val retryAfterMs: Long) : LinkConfirmStep
    data class Refused(val reason: String) : LinkConfirmStep
}
