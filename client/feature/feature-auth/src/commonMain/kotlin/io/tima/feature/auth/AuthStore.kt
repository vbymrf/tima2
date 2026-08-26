package io.tima.feature.auth

import io.tima.domain.account.AccountIdentities
import io.tima.domain.account.CodeRequestStep
import io.tima.domain.account.LinkAwaitStep
import io.tima.domain.account.LinkBeginStep
import io.tima.domain.account.LinkNewDevice
import io.tima.domain.account.NewAccountIdentity
import io.tima.domain.account.RegisterDevice
import io.tima.domain.account.RegistrationStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Вход: телефон → код → заведено (К5.1).
 *
 * Порядок шагов живёт в [RegisterDevice] — это правило продукта, а не свойство экрана.
 * Здесь правила **поведения экрана**, и их три:
 *
 * **1. Набранное человеком не теряется.** Ни номер при отказе сети, ни код при неверном
 * коде. То же правило, что в переписке: текст написан человеком, и восстановить его нам
 * нечем.
 *
 * **2. Второе нажатие не посылает второй запрос.** Пока идёт вызов, кнопка занята.
 * Иначе двойное нажатие означает две SMS — человек платит за них не деньгами, а
 * доверием: «оно шлёт мне код дважды».
 *
 * **3. Отказ называется словами, а не пропадает.** «Нет связи», «код неверен», «номер не
 * тот» — каждое состояние имеет текст, потому что человек на этом экране не может ни
 * подождать, ни обойти: без входа приложения нет.
 */
class AuthStore(
    private val register: RegisterDevice,
    private val identities: AccountIdentities,
    private val scope: CoroutineScope,
    /**
     * Привязка к уже существующему аккаунту. `null` — путь недоступен, и кнопки не будет:
     * обещать человеку то, чего нет, дороже, чем не обещать.
     */
    private val link: LinkNewDevice? = null,
    /** Как это устройство назовётся человеку на телефоне. Он увидит имя перед «Доверить». */
    private val deviceName: String = "Устройство",
) {

    /**
     * Свежая личность аккаунта, порождённая для этой попытки.
     *
     * Живёт в памяти до показа человеку и дальше не хранится нигде: слова — секрет, и
     * восстановить их можно только из его собственной записи. Это и есть смысл фразы.
     */
    private var fresh: NewAccountIdentity? = null

    private val _state = MutableStateFlow<AuthState>(AuthState.Phone())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun changedNumber(text: String) {
        val current = _state.value as? AuthState.Phone ?: return
        _state.value = current.copy(number = text, trouble = null)
    }

    /**
     * Код страны. Плюс не принимается и не хранится: он нарисован на экране и в значение
     * не входит — иначе «+7» и «7» стали бы разными кодами одной страны.
     */
    fun changedCountryCode(text: String) {
        val current = _state.value as? AuthState.Phone ?: return
        _state.value = current.copy(countryCode = text.filter { it.isDigit() }.take(4), trouble = null)
    }

    fun changedCode(text: String) {
        val current = _state.value as? AuthState.Code ?: return
        _state.value = current.copy(code = text, trouble = null)
    }

    /** Человек нажал «Получить код». */
    fun requestCode() {
        val current = _state.value as? AuthState.Phone ?: return
        // Занято — значит вызов уже идёт. Второе нажатие это вторая SMS.
        if (current.expect) return
        _state.value = current.copy(expect = true, trouble = null)

        scope.launch {
            _state.value = when (val step = register.requestCode(current.fullNumber)) {
                is CodeRequestStep.CodeRequested -> AuthState.Code(
                    requestId = step.requestId,
                    phone = current.fullNumber,
                    // Код в ответе приходит только со стенда, где включён TIMA_DEV_SMS.
                    // Решает это сервер, не мы: на боевом сервере поля нет вовсе.
                    standHint = step.devCode,
                )

                is CodeRequestStep.BadPhone -> current.copyWithTrouble("Номер не тот: ${step.reason}")
                is CodeRequestStep.Offline -> current.copyWithTrouble(noLinks(step.retryAfterMs))
                is CodeRequestStep.Refused -> current.copyWithTrouble(step.reason)
            }
        }
    }

    /** Человек набирает фразу возврата. */
    fun changedPhrase(text: String) {
        val current = _state.value as? AuthState.PhraseInput ?: return
        _state.value = current.copy(phrase = text, trouble = null)
    }

    /**
     * Человек нажал «Подтвердить».
     *
     * **Личность аккаунта порождается здесь, при первой попытке.** Иначе аккаунт остаётся
     * без фразы навсегда — и вернуться в него после потери телефона нечем: номер
     * подтверждает только номер, а номера перевыпускают. Ответ «у этого номера другая
     * личность» означает, что аккаунт существует, и человеку надо ввести свою фразу.
     */
    fun confirm() {
        val current = _state.value as? AuthState.Code ?: return
        if (current.expect) return
        _state.value = current.copy(expect = true, trouble = null)

        scope.launch {
            val identity = identities.fresh().also { fresh = it }
            _state.value = when (
                val step = register.confirm(current.requestId, current.code, identity.identityPub)
            ) {
                // Личность приняли — значит у аккаунта теперь наша, и фразу надо показать.
                // Один раз: второго раза у неё не бывает.
                is RegistrationStep.Registered -> AuthState.Phrase(
                    words = identity.words,
                    userId = step.userId,
                    deviceId = step.deviceId,
                )

                // Устройство уже заведено: секрет в хранилище платформы, сессия тоже.
                // Это не отказ — это «мы уже вошли», и приложение идёт дальше.
                RegistrationStep.AlreadyRegistered -> AuthState.CreatedAlready

                // Код остаётся в поле. Отобрать набранное нельзя даже когда оно неверно:
                // человек чаще опечатался в одной цифре, чем набрал наугад.
                RegistrationStep.WrongCode -> current.copyWithTrouble("Код неверен или просрочен")

                // Токен регистрации живёт минуты. Начинать надо с запроса кода, и сказать
                // это надо явно — иначе человек будет повторять код, который уже не примут.
                RegistrationStep.CodeExpired -> AuthState.Phone(
                    number = current.phone,
                    trouble = "Код просрочен — запросите новый",
                )

                // Не отказ, а другой путь: аккаунт существует, и владение им доказывает
                // фраза. Номер этого не доказывает — его перевыпускают.
                is RegistrationStep.IdentityMismatch -> AuthState.PhraseInput(
                    requestId = current.requestId,
                    phone = current.phone,
                    // Дальше идём с токеном: код погашен проверкой и второй раз не
                    // сработает. Именно это и ломало вход по фразе.
                    registrationToken = step.registrationToken,
                )

                is RegistrationStep.Offline -> current.copyWithTrouble(noLinks(step.retryAfterMs))
                is RegistrationStep.Refused -> current.copyWithTrouble(step.reason)
            }
        }
    }

    /**
     * Человек ввёл фразу возврата.
     *
     * Фраза не та — так и говорим, не уточняя, что именно не сошлось: число слов, слово не
     * из списка или контрольная сумма. Человеку во всех трёх случаях надо перепроверить
     * запись, а подробность подсказывала бы подбирающему.
     */
    fun enterByPhrase() {
        val current = _state.value as? AuthState.PhraseInput ?: return
        if (current.expect) return

        val words = current.phrase.split(SEPARATOR).filter { it.isNotBlank() }
        val key = identities.fromWords(words)
        if (key == null) {
            _state.value = current.copyWithTrouble("Фраза не та — проверьте запись")
            return
        }
        _state.value = current.copy(expect = true, trouble = null)

        scope.launch {
            _state.value = when (val step = register.continueWithToken(current.registrationToken, key)) {
                // Фразу показывать не надо: она у человека есть, он её только что ввёл.
                is RegistrationStep.Registered -> AuthState.Done(step.userId, step.deviceId)
                RegistrationStep.AlreadyRegistered -> AuthState.CreatedAlready
                is RegistrationStep.IdentityMismatch -> current.copyWithTrouble("Фраза не та — проверьте запись")
                RegistrationStep.WrongCode -> current.copyWithTrouble("Код неверен или просрочен")
                // Токен живёт десять минут. Истёк — начинать с запроса кода, и сказать об
                // этом надо именно так: «введите фразу заново» здесь бесполезно.
                RegistrationStep.CodeExpired -> AuthState.Phone(
                    number = current.phone,
                    trouble = "Время истекло — запросите код заново",
                )
                is RegistrationStep.Offline -> current.copyWithTrouble(noLinks(step.retryAfterMs))
                is RegistrationStep.Refused -> current.copyWithTrouble(step.reason)
            }
        }
    }

    /**
     * «Начать заново»: фразы нет, и человек согласен потерять прежнюю переписку.
     *
     * **Только по прямому подтверждению.** Сервер форкает цепочку личности административно,
     * без доказательства владения прежним ключом, и собеседники увидят предупреждение о
     * смене личности (ADR-0014 §3). Поставить этот флаг «чтобы прошло» значит молча забрать
     * у человека историю и напугать его собеседников.
     */
    fun startAnew() {
        val current = _state.value as? AuthState.PhraseInput ?: return
        if (current.expect) return
        _state.value = current.copy(expect = true, trouble = null)

        scope.launch {
            val identity = identities.fresh().also { fresh = it }
            _state.value = when (
                // Тем же токеном, что и вход по фразе: код погашен проверкой, и «начать
                // заново» с ним упиралось бы в ту же ошибку.
                val step = register.continueWithToken(
                    registrationToken = current.registrationToken,
                    identityPub = identity.identityPub,
                    forceNewIdentity = true,
                )
            ) {
                // Личность теперь новая — и фраза к ней новая. Показать обязательно: иначе
                // человек второй раз останется без способа вернуться.
                is RegistrationStep.Registered -> AuthState.Phrase(
                    words = identity.words,
                    userId = step.userId,
                    deviceId = step.deviceId,
                )
                RegistrationStep.AlreadyRegistered -> AuthState.CreatedAlready
                RegistrationStep.WrongCode -> current.copyWithTrouble("Код неверен или просрочен")
                RegistrationStep.CodeExpired -> AuthState.Phone(
                    number = current.phone,
                    trouble = "Время истекло — запросите код заново",
                )
                is RegistrationStep.IdentityMismatch -> current.copyWithTrouble("Сервер отказал в смене личности")
                is RegistrationStep.Offline -> current.copyWithTrouble(noLinks(step.retryAfterMs))
                is RegistrationStep.Refused -> current.copyWithTrouble(step.reason)
            }
        }
    }

    /** Человек подтвердил, что фразу сохранил. Дальше — приложение. */
    fun savedPhrase() {
        val current = _state.value as? AuthState.Phrase ?: return
        fresh = null
        _state.value = AuthState.Done(current.userId, current.deviceId)
    }

    /**
     * «Подключить к аккаунту, который уже есть» — путь без SMS и без фразы.
     *
     * Доверие приносит телефон, который отсканирует код. Здесь только показ кода и
     * ожидание; всё остальное решается на том конце, и решается человеком.
     */
    fun connect() {
        val link = link ?: return
        // Повторно — только если прежний код уже мёртв: живой код рядом с кнопкой
        // «новый» означал бы, что человек может обнулить работающий код одним промахом.
        val current = _state.value
        if (current is AuthState.DisplayCode && !(current.code == null && current.trouble != null)) return
        _state.value = AuthState.DisplayCode(code = null)

        scope.launch {
            when (val step = link.begin(deviceName)) {
                is LinkBeginStep.ShowCode -> {
                    _state.value = AuthState.DisplayCode(code = step.code)
                    wait(link, step.sessionId, step.claimToken)
                }
                LinkBeginStep.AlreadyRegistered -> _state.value = AuthState.CreatedAlready
                is LinkBeginStep.Offline -> _state.value =
                    AuthState.DisplayCode(code = null, trouble = noLinks(step.retryAfterMs))
                is LinkBeginStep.Refused -> _state.value =
                    AuthState.DisplayCode(code = null, trouble = step.reason)
            }
        }
    }

    /**
     * Ожидание подтверждения.
     *
     * Срок вышел — не беда, а «покажите новый код»: сессия живёт пять минут, и человек мог
     * просто не успеть дойти до телефона.
     */
    private suspend fun wait(link: LinkNewDevice, sessionId: String, claimToken: String) {
        _state.value = when (val step = link.await(sessionId, claimToken)) {
            is LinkAwaitStep.Linked -> AuthState.Done(step.userId, step.deviceId)
            LinkAwaitStep.Expired -> AuthState.DisplayCode(
                code = null,
                trouble = "Срок кода вышел — попросите новый",
            )
            is LinkAwaitStep.Refused -> AuthState.DisplayCode(code = null, trouble = step.reason)
        }
    }

    /** «Назад» с экрана кода: номер сохраняется — его уже набрали. */
    fun back() {
        if (_state.value is AuthState.DisplayCode) {
            _state.value = AuthState.Phone()
            return
        }
        // Из ввода фразы возврат тоже обязан работать, и это была настоящая ловушка:
        // человек, набравший чужой или свой старый номер, оказывался заперт между
        // «введите фразу» и «начать заново». Второе стирает прежнюю личность — то есть
        // опечатка в номере стоила бы аккаунта.
        (_state.value as? AuthState.PhraseInput)?.let {
            _state.value = AuthState.Phone(number = it.phone)
            return
        }
        val current = _state.value as? AuthState.Code ?: return
        _state.value = AuthState.Phone(number = current.phone)
    }

    private companion object {

        /** Пробелы, переводы строк, табуляции: фразу вставляют как получится. */
        val SEPARATOR = Regex("\\s+")

        /**
         * «Нет связи» с числом секунд.
         *
         * Число обязательно: «попробуйте позже» человек читает как «сломалось». Названный
         * срок — это обещание, которое можно проверить.
         */
        fun noLinks(retryAfterMs: Long): String {
            val seconds = (retryAfterMs / 1000).coerceAtLeast(1)
            return "Нет связи с сервером — повторим через $seconds с"
        }
    }
}

/** Где человек в этом пути. */
sealed interface AuthState {

    /** Отказ, который надо показать. `null` — показывать нечего. */
    val trouble: String?

    data class Phone(
        /**
         * Код страны без плюса: человек набирает «7», а не «+7».
         *
         * Отдельно от номера, потому что это разные вещи для того, кто вводит: код
         * страны почти всегда один и тот же и меняется раз в жизни, а номер набирают
         * каждый раз заново. Слитое поле заставляло стирать «+7» вместе с номером —
         * и, что хуже, «+7» в подсказке выглядел как уже введённый, хотя поле было
         * пустым, и человек набирал номер без кода страны.
         */
        val countryCode: String = "7",
        val number: String = "",
        override val trouble: String? = null,
        /** Вызов идёт: кнопка занята, второе нажатие не посылает вторую SMS. */
        val expect: Boolean = false,
    ) : AuthState {
        /**
         * То, что уходит серверу: E.164 без пробелов и скобок.
         *
         * **Номер, начатый с плюса, берётся целиком.** Так выглядит вставка из буфера:
         * человек скопировал номер полностью, и приписать к нему код страны значит
         * получить «+77999…» — номер, которого нет. Поймано тестом, который вставлял
         * именно так.
         */
        val fullNumber: String get() {
            val entered = number.trim()
            if (entered.startsWith("+")) {
                return "+" + entered.drop(1).filter { it.isDigit() }
            }
            return "+" + countryCode.filter { it.isDigit() } + entered.filter { it.isDigit() }
        }

        fun copyWithTrouble(text: String) = copy(trouble = text, expect = false)
    }

    data class Code(
        val requestId: String,
        /** Номер помнится: с экрана кода можно вернуться, не набирая заново. */
        val phone: String,
        val code: String = "",
        override val trouble: String? = null,
        val expect: Boolean = false,
        /**
         * Код, присланный сервером в ответе. Появляется **только на стенде**, где включён
         * `TIMA_DEV_SMS`; на боевом сервере поля нет вовсе, и решает это сервер, а не мы.
         */
        val standHint: String? = null,
    ) : AuthState {
        fun copyWithTrouble(text: String) = copy(trouble = text, expect = false)
    }

    /**
     * Аккаунт заведён, и **фраза показывается один раз**.
     *
     * Отдельное состояние, а не всплывающее сообщение: это единственный момент, когда
     * человек может её записать. Проскочить его нельзя — дальше только по кнопке.
     */
    data class Phrase(
        val words: List<String>,
        val userId: String,
        val deviceId: String,
    ) : AuthState {
        override val trouble: String? get() = null
    }

    /**
     * Номер занят другой личностью: аккаунт существует, и владение им доказывает фраза.
     *
     * Код и `requestId` помнятся: человек уже подтвердил номер, и просить SMS второй раз
     * значило бы наказать его за то, что у него есть аккаунт.
     */
    data class PhraseInput(
        val requestId: String,
        val phone: String,
        /**
         * `registration_token`, полученный при проверке кода.
         *
         * Именно он, а не код: код одноразовый и гасится проверкой. Вход по фразе с
         * кодом отвечал «неверен или просрочен» и не мог сработать никогда — найдено
         * живым прогоном.
         */
        val registrationToken: String,
        val phrase: String = "",
        override val trouble: String? = null,
        val expect: Boolean = false,
    ) : AuthState {
        fun copyWithTrouble(text: String) = copy(trouble = text, expect = false)
    }

    /**
     * Код привязки на экране: ждём телефон.
     *
     * @param код `null` — кода ещё нет: либо просим его у сервера, либо просить нечем
     *   (тогда сказано в [беда]). Пустой строки здесь не бывает намеренно: пустой QR
     *   человек попробует отсканировать.
     */
    data class DisplayCode(
        val code: String?,
        override val trouble: String? = null,
    ) : AuthState

    /** Устройство заведено. Дальше — окно переписок. */
    data class Done(val userId: String, val deviceId: String) : AuthState {
        override val trouble: String? get() = null
    }

    /** Устройство было заведено раньше: секрет и сессия уже в хранилище платформы. */
    data object CreatedAlready : AuthState {
        override val trouble: String? get() = null
    }
}
