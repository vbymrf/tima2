package io.tima.domain.account

/**
 * Заведение виртуального аккаунта — ПЛАН-КОНТАКТОВ.md, Д10 и Д11.
 *
 * **Виртуальный аккаунт — отдельный пользователь, а не второе имя.** Своя фраза, свои
 * ключи, своя переписка; со стороны собеседника он неотличим от обычного человека. Отличий
 * два: у него нет показываемого телефона (находят по нику — поэтому ник обязателен) и он
 * привязан к основному аккаунту на сервере.
 *
 * ── ПОЧЕМУ ЗДЕСЬ СПРАШИВАЮТ ФРАЗУ ВЛАДЕЛЬЦА ─────────────────────────────────
 *
 * Кода из SMS для нового аккаунта не будет никогда: своего номера у него нет. Значит
 * заверить создание нечем, кроме подписи владельца ключом личности, — а этот ключ живёт
 * только в двенадцати словах и на устройстве не хранится (ADR-0010). Отсюда фраза
 * владельца в этом сценарии: не строгость ради строгости, а единственное доказательство,
 * которое у сервера есть. Опирайся создание на один токен устройства — укравший телефон
 * заводил бы аккаунты от чужого имени.
 *
 * **Слова не сохраняются нигде.** Ни владельца, ни нового аккаунта: первые превращаются в
 * подпись и уходят, вторые показываются человеку один раз. Сервер не видит ни тех, ни
 * других — ему уходит только публичная часть.
 *
 * ── ПОРЯДОК ШАГОВ ЗДЕСЬ — ПРАВИЛО, А НЕ СВОЙСТВО HTTP ───────────────────────
 *
 * Ключи устройства порождаются **до** вызова, а сохраняются вызывающим **после** успеха.
 * В отличие от [RegisterDevice], секрет здесь не пишется заранее: там он один на
 * устройство и следующая попытка его перезапишет, а здесь у каждого аккаунта свой, и до
 * появления `userId` записать его попросту некуда — имя ящика содержит `userId`. Ценой
 * этому — оборванное на середине создание оставляет на сервере аккаунт без ключа; он
 * виден в списке виртуальных ([VirtualsApi.mine]) и ничем, кроме удаления, не лечится.
 */
class CreateVirtual(
    private val api: VirtualsApi,
    private val keys: DeviceKeyFactory,
    private val identities: AccountIdentities,
    private val signer: IdentitySigner,
    /** Показывается человеку в списке устройств. Сервер значение не проверяет. */
    private val platform: String,
) {

    /**
     * @param nickname ник нового аккаунта — обязателен: по нему его и находят.
     * @param ownerWords фраза **владельца**, а не нового аккаунта. Ею заверяется создание.
     */
    suspend fun create(nickname: String, ownerWords: List<String>): VirtualStep {
        val nick = nickname.trim()
        if (!nicknameFits(nick)) return VirtualStep.BadNickname

        val fresh = identities.fresh()
        // Фраза владельца разбирается на месте: не сложились слова — сети не касаемся.
        // Неверная фраза это обычная опечатка человека, а не поломка.
        val signature = signer.sign(ownerWords, virtualSigned(nick, fresh.identityPub))
            ?: return VirtualStep.BadPhrase

        val material = keys.newDeviceKeys()
        return when (
            val answer = api.create(
                nickname = nick,
                identityPub = fresh.identityPub,
                signature = signature,
                encryptionPub = material.encryptionPub,
                signingPub = material.signingPub,
                platform = platform,
            )
        ) {
            is VirtualCreateStep.Created -> VirtualStep.Created(
                session = Session(answer.userId, answer.deviceId, answer.accessToken),
                deviceSecret = material.secret,
                nickname = nick,
                // Слова показываются один раз и только здесь: потерявший их потеряет и
                // аккаунт вместе с устройством — восстановить его будет нечем.
                words = fresh.words,
            )
            VirtualCreateStep.NicknameTaken -> VirtualStep.NicknameTaken
            VirtualCreateStep.BadNickname -> VirtualStep.BadNickname
            VirtualCreateStep.TooMany -> VirtualStep.TooMany
            VirtualCreateStep.BadSignature -> VirtualStep.BadPhrase
            VirtualCreateStep.NotAllowed -> VirtualStep.NotAllowed
            VirtualCreateStep.Offline -> VirtualStep.Offline
        }
    }

    private companion object {

        /**
         * Что именно подписывает владелец — байт в байт как считает сервер
         * (`api/virtuals.go`, `virtualSigned`).
         *
         * Ник и ключ вместе, через перевод строки. Подпись только над ником позволила бы
         * подменить ключ и завести аккаунт, которым владелец не управляет; подпись только
         * над ключом — занять чужим ключом любой свободный ник.
         */
        fun virtualSigned(nickname: String, identityPub: ByteArray): ByteArray =
            nickname.encodeToByteArray() + NEWLINE + identityPub

        const val NEWLINE: Byte = 10
    }
}

/** Чем кончилось заведение виртуального аккаунта — словами продукта, а не HTTP. */
sealed interface VirtualStep {

    /**
     * Готово. Всё, чем в него войти, — и слова, которые надо показать.
     *
     * Записать сессию и секрет — дело вызывающего: список аккаунтов живёт в хранилище
     * платформы, а `domain` про хранилище не знает.
     */
    class Created(
        val session: Session,
        val deviceSecret: ByteArray,
        val nickname: String,
        /** Двенадцать слов нового аккаунта. Показать один раз, не сохранять. */
        val words: List<String>,
    ) : VirtualStep

    /** Ник занят. Это не ошибка написания: границы он прошёл. */
    data object NicknameTaken : VirtualStep

    /** 10…20 знаков, латиница, цифры, подчёркивание — что-то нарушено. */
    data object BadNickname : VirtualStep

    /** Пять виртуальных на номер уже есть. Запрос верный, кончилось место. */
    data object TooMany : VirtualStep

    /**
     * Фраза владельца не та — либо слова не складываются, либо подпись не сошлась.
     *
     * Два случая слиты намеренно: человеку в обоих надо перепроверить фразу, а различие
     * между «слово не из списка» и «ключ не тот» ему ничего не даёт.
     */
    data object BadPhrase : VirtualStep

    /** Виртуальный не заводит виртуальных; либо у владельца нет ключа личности. */
    data object NotAllowed : VirtualStep

    data object Offline : VirtualStep
}

/**
 * Подпись ключом личности — тем, что выводится из фразы, а не ключом устройства.
 *
 * Порт, потому что вывод ключа из слов — крипто (`core-encryption` над `AccountMnemonic`),
 * а порядок шагов — правило продукта.
 *
 * @return `null`, если слова не складываются в личность. Опечатка в фразе выглядит именно
 *   так, и это не поломка.
 */
fun interface IdentitySigner {
    fun sign(words: List<String>, bytes: ByteArray): ByteArray?
}

/** Порт к серверу: две ручки Д10. Реализуется в `core-network`. */
interface VirtualsApi {

    suspend fun create(
        nickname: String,
        identityPub: ByteArray,
        signature: ByteArray,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        platform: String,
    ): VirtualCreateStep

    /**
     * Свои виртуальные аккаунты — с сервера, а не из списка на устройстве.
     *
     * Списки не совпадают: аккаунт заводили на другом устройстве либо на этом же, но
     * приложение переустановлено. `null` — до сервера не дошли.
     */
    suspend fun mine(): List<VirtualAccount>?
}

/** Строка списка виртуальных аккаунтов на сервере. */
data class VirtualAccount(val userId: String, val nickname: String)

/** Что ответил сервер на создание. */
sealed interface VirtualCreateStep {
    data class Created(val userId: String, val deviceId: String, val accessToken: String) : VirtualCreateStep
    data object NicknameTaken : VirtualCreateStep
    data object BadNickname : VirtualCreateStep
    data object TooMany : VirtualCreateStep
    data object BadSignature : VirtualCreateStep
    data object NotAllowed : VirtualCreateStep
    data object Offline : VirtualCreateStep
}
