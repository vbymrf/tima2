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
    private val имяУстройства: String = "Устройство",
) {

    /**
     * Свежая личность аккаунта, порождённая для этой попытки.
     *
     * Живёт в памяти до показа человеку и дальше не хранится нигде: слова — секрет, и
     * восстановить их можно только из его собственной записи. Это и есть смысл фразы.
     */
    private var свежая: NewAccountIdentity? = null

    private val _state = MutableStateFlow<AuthState>(AuthState.Телефон())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun номерИзменён(текст: String) {
        val текущее = _state.value as? AuthState.Телефон ?: return
        _state.value = текущее.copy(номер = текст, беда = null)
    }

    /**
     * Код страны. Плюс не принимается и не хранится: он нарисован на экране и в значение
     * не входит — иначе «+7» и «7» стали бы разными кодами одной страны.
     */
    fun кодСтраныИзменён(текст: String) {
        val текущее = _state.value as? AuthState.Телефон ?: return
        _state.value = текущее.copy(кодСтраны = текст.filter { it.isDigit() }.take(4), беда = null)
    }

    fun кодИзменён(текст: String) {
        val текущее = _state.value as? AuthState.Код ?: return
        _state.value = текущее.copy(код = текст, беда = null)
    }

    /** Человек нажал «Получить код». */
    fun запроситьКод() {
        val текущее = _state.value as? AuthState.Телефон ?: return
        // Занято — значит вызов уже идёт. Второе нажатие это вторая SMS.
        if (текущее.ждём) return
        _state.value = текущее.copy(ждём = true, беда = null)

        scope.launch {
            _state.value = when (val шаг = register.requestCode(текущее.полныйНомер)) {
                is CodeRequestStep.CodeRequested -> AuthState.Код(
                    requestId = шаг.requestId,
                    телефон = текущее.полныйНомер,
                    // Код в ответе приходит только со стенда, где включён TIMA_DEV_SMS.
                    // Решает это сервер, не мы: на боевом сервере поля нет вовсе.
                    подсказкаСтенда = шаг.devCode,
                )

                is CodeRequestStep.BadPhone -> текущее.копияСБедой("Номер не тот: ${шаг.reason}")
                is CodeRequestStep.Offline -> текущее.копияСБедой(нетСвязи(шаг.retryAfterMs))
                is CodeRequestStep.Refused -> текущее.копияСБедой(шаг.reason)
            }
        }
    }

    /** Человек набирает фразу возврата. */
    fun фразаИзменена(текст: String) {
        val текущее = _state.value as? AuthState.ВводФразы ?: return
        _state.value = текущее.copy(фраза = текст, беда = null)
    }

    /**
     * Человек нажал «Подтвердить».
     *
     * **Личность аккаунта порождается здесь, при первой попытке.** Иначе аккаунт остаётся
     * без фразы навсегда — и вернуться в него после потери телефона нечем: номер
     * подтверждает только номер, а номера перевыпускают. Ответ «у этого номера другая
     * личность» означает, что аккаунт существует, и человеку надо ввести свою фразу.
     */
    fun подтвердить() {
        val текущее = _state.value as? AuthState.Код ?: return
        if (текущее.ждём) return
        _state.value = текущее.copy(ждём = true, беда = null)

        scope.launch {
            val личность = identities.fresh().also { свежая = it }
            _state.value = when (
                val шаг = register.confirm(текущее.requestId, текущее.код, личность.identityPub)
            ) {
                // Личность приняли — значит у аккаунта теперь наша, и фразу надо показать.
                // Один раз: второго раза у неё не бывает.
                is RegistrationStep.Registered -> AuthState.Фраза(
                    слова = личность.words,
                    userId = шаг.userId,
                    deviceId = шаг.deviceId,
                )

                // Устройство уже заведено: секрет в хранилище платформы, сессия тоже.
                // Это не отказ — это «мы уже вошли», и приложение идёт дальше.
                RegistrationStep.AlreadyRegistered -> AuthState.УжеЗаведено

                // Код остаётся в поле. Отобрать набранное нельзя даже когда оно неверно:
                // человек чаще опечатался в одной цифре, чем набрал наугад.
                RegistrationStep.WrongCode -> текущее.копияСБедой("Код неверен или просрочен")

                // Токен регистрации живёт минуты. Начинать надо с запроса кода, и сказать
                // это надо явно — иначе человек будет повторять код, который уже не примут.
                RegistrationStep.CodeExpired -> AuthState.Телефон(
                    номер = текущее.телефон,
                    беда = "Код просрочен — запросите новый",
                )

                // Не отказ, а другой путь: аккаунт существует, и владение им доказывает
                // фраза. Номер этого не доказывает — его перевыпускают.
                RegistrationStep.IdentityMismatch -> AuthState.ВводФразы(
                    requestId = текущее.requestId,
                    телефон = текущее.телефон,
                    код = текущее.код,
                )

                is RegistrationStep.Offline -> текущее.копияСБедой(нетСвязи(шаг.retryAfterMs))
                is RegistrationStep.Refused -> текущее.копияСБедой(шаг.reason)
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
    fun войтиПоФразе() {
        val текущее = _state.value as? AuthState.ВводФразы ?: return
        if (текущее.ждём) return

        val слова = текущее.фраза.split(РАЗДЕЛИТЕЛЬ).filter { it.isNotBlank() }
        val ключ = identities.fromWords(слова)
        if (ключ == null) {
            _state.value = текущее.копияСБедой("Фраза не та — проверьте запись")
            return
        }
        _state.value = текущее.copy(ждём = true, беда = null)

        scope.launch {
            _state.value = when (val шаг = register.confirm(текущее.requestId, текущее.код, ключ)) {
                // Фразу показывать не надо: она у человека есть, он её только что ввёл.
                is RegistrationStep.Registered -> AuthState.Готово(шаг.userId, шаг.deviceId)
                RegistrationStep.AlreadyRegistered -> AuthState.УжеЗаведено
                RegistrationStep.IdentityMismatch -> текущее.копияСБедой("Фраза не та — проверьте запись")
                RegistrationStep.WrongCode -> текущее.копияСБедой("Код неверен или просрочен")
                RegistrationStep.CodeExpired -> AuthState.Телефон(
                    номер = текущее.телефон,
                    беда = "Код просрочен — запросите новый",
                )
                is RegistrationStep.Offline -> текущее.копияСБедой(нетСвязи(шаг.retryAfterMs))
                is RegistrationStep.Refused -> текущее.копияСБедой(шаг.reason)
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
    fun начатьЗаново() {
        val текущее = _state.value as? AuthState.ВводФразы ?: return
        if (текущее.ждём) return
        _state.value = текущее.copy(ждём = true, беда = null)

        scope.launch {
            val личность = identities.fresh().also { свежая = it }
            _state.value = when (
                val шаг = register.confirm(
                    requestId = текущее.requestId,
                    code = текущее.код,
                    identityPub = личность.identityPub,
                    forceNewIdentity = true,
                )
            ) {
                // Личность теперь новая — и фраза к ней новая. Показать обязательно: иначе
                // человек второй раз останется без способа вернуться.
                is RegistrationStep.Registered -> AuthState.Фраза(
                    слова = личность.words,
                    userId = шаг.userId,
                    deviceId = шаг.deviceId,
                )
                RegistrationStep.AlreadyRegistered -> AuthState.УжеЗаведено
                RegistrationStep.WrongCode -> текущее.копияСБедой("Код неверен или просрочен")
                RegistrationStep.CodeExpired -> AuthState.Телефон(
                    номер = текущее.телефон,
                    беда = "Код просрочен — запросите новый",
                )
                RegistrationStep.IdentityMismatch -> текущее.копияСБедой("Сервер отказал в смене личности")
                is RegistrationStep.Offline -> текущее.копияСБедой(нетСвязи(шаг.retryAfterMs))
                is RegistrationStep.Refused -> текущее.копияСБедой(шаг.reason)
            }
        }
    }

    /** Человек подтвердил, что фразу сохранил. Дальше — приложение. */
    fun фразаСохранена() {
        val текущее = _state.value as? AuthState.Фраза ?: return
        свежая = null
        _state.value = AuthState.Готово(текущее.userId, текущее.deviceId)
    }

    /**
     * «Подключить к аккаунту, который уже есть» — путь без SMS и без фразы.
     *
     * Доверие приносит телефон, который отсканирует код. Здесь только показ кода и
     * ожидание; всё остальное решается на том конце, и решается человеком.
     */
    fun подключиться() {
        val привязка = link ?: return
        // Повторно — только если прежний код уже мёртв: живой код рядом с кнопкой
        // «новый» означал бы, что человек может обнулить работающий код одним промахом.
        val текущее = _state.value
        if (текущее is AuthState.ПоказКода && !(текущее.код == null && текущее.беда != null)) return
        _state.value = AuthState.ПоказКода(код = null)

        scope.launch {
            when (val шаг = привязка.begin(имяУстройства)) {
                is LinkBeginStep.ShowCode -> {
                    _state.value = AuthState.ПоказКода(код = шаг.code)
                    ждать(привязка, шаг.sessionId, шаг.claimToken)
                }
                LinkBeginStep.AlreadyRegistered -> _state.value = AuthState.УжеЗаведено
                is LinkBeginStep.Offline -> _state.value =
                    AuthState.ПоказКода(код = null, беда = нетСвязи(шаг.retryAfterMs))
                is LinkBeginStep.Refused -> _state.value =
                    AuthState.ПоказКода(код = null, беда = шаг.reason)
            }
        }
    }

    /**
     * Ожидание подтверждения.
     *
     * Срок вышел — не беда, а «покажите новый код»: сессия живёт пять минут, и человек мог
     * просто не успеть дойти до телефона.
     */
    private suspend fun ждать(привязка: LinkNewDevice, sessionId: String, claimToken: String) {
        _state.value = when (val шаг = привязка.await(sessionId, claimToken)) {
            is LinkAwaitStep.Linked -> AuthState.Готово(шаг.userId, шаг.deviceId)
            LinkAwaitStep.Expired -> AuthState.ПоказКода(
                код = null,
                беда = "Срок кода вышел — попросите новый",
            )
            is LinkAwaitStep.Refused -> AuthState.ПоказКода(код = null, беда = шаг.reason)
        }
    }

    /** «Назад» с экрана кода: номер сохраняется — его уже набрали. */
    fun назад() {
        if (_state.value is AuthState.ПоказКода) {
            _state.value = AuthState.Телефон()
            return
        }
        val текущее = _state.value as? AuthState.Код ?: return
        _state.value = AuthState.Телефон(номер = текущее.телефон)
    }

    private companion object {

        /** Пробелы, переводы строк, табуляции: фразу вставляют как получится. */
        val РАЗДЕЛИТЕЛЬ = Regex("\\s+")

        /**
         * «Нет связи» с числом секунд.
         *
         * Число обязательно: «попробуйте позже» человек читает как «сломалось». Названный
         * срок — это обещание, которое можно проверить.
         */
        fun нетСвязи(retryAfterMs: Long): String {
            val секунды = (retryAfterMs / 1000).coerceAtLeast(1)
            return "Нет связи с сервером — повторим через $секунды с"
        }
    }
}

/** Где человек в этом пути. */
sealed interface AuthState {

    /** Отказ, который надо показать. `null` — показывать нечего. */
    val беда: String?

    data class Телефон(
        /**
         * Код страны без плюса: человек набирает «7», а не «+7».
         *
         * Отдельно от номера, потому что это разные вещи для того, кто вводит: код
         * страны почти всегда один и тот же и меняется раз в жизни, а номер набирают
         * каждый раз заново. Слитое поле заставляло стирать «+7» вместе с номером —
         * и, что хуже, «+7» в подсказке выглядел как уже введённый, хотя поле было
         * пустым, и человек набирал номер без кода страны.
         */
        val кодСтраны: String = "7",
        val номер: String = "",
        override val беда: String? = null,
        /** Вызов идёт: кнопка занята, второе нажатие не посылает вторую SMS. */
        val ждём: Boolean = false,
    ) : AuthState {
        /**
         * То, что уходит серверу: E.164 без пробелов и скобок.
         *
         * **Номер, начатый с плюса, берётся целиком.** Так выглядит вставка из буфера:
         * человек скопировал номер полностью, и приписать к нему код страны значит
         * получить «+77999…» — номер, которого нет. Поймано тестом, который вставлял
         * именно так.
         */
        val полныйНомер: String get() {
            val введённое = номер.trim()
            if (введённое.startsWith("+")) {
                return "+" + введённое.drop(1).filter { it.isDigit() }
            }
            return "+" + кодСтраны.filter { it.isDigit() } + введённое.filter { it.isDigit() }
        }

        fun копияСБедой(текст: String) = copy(беда = текст, ждём = false)
    }

    data class Код(
        val requestId: String,
        /** Номер помнится: с экрана кода можно вернуться, не набирая заново. */
        val телефон: String,
        val код: String = "",
        override val беда: String? = null,
        val ждём: Boolean = false,
        /**
         * Код, присланный сервером в ответе. Появляется **только на стенде**, где включён
         * `TIMA_DEV_SMS`; на боевом сервере поля нет вовсе, и решает это сервер, а не мы.
         */
        val подсказкаСтенда: String? = null,
    ) : AuthState {
        fun копияСБедой(текст: String) = copy(беда = текст, ждём = false)
    }

    /**
     * Аккаунт заведён, и **фраза показывается один раз**.
     *
     * Отдельное состояние, а не всплывающее сообщение: это единственный момент, когда
     * человек может её записать. Проскочить его нельзя — дальше только по кнопке.
     */
    data class Фраза(
        val слова: List<String>,
        val userId: String,
        val deviceId: String,
    ) : AuthState {
        override val беда: String? get() = null
    }

    /**
     * Номер занят другой личностью: аккаунт существует, и владение им доказывает фраза.
     *
     * Код и `requestId` помнятся: человек уже подтвердил номер, и просить SMS второй раз
     * значило бы наказать его за то, что у него есть аккаунт.
     */
    data class ВводФразы(
        val requestId: String,
        val телефон: String,
        val код: String,
        val фраза: String = "",
        override val беда: String? = null,
        val ждём: Boolean = false,
    ) : AuthState {
        fun копияСБедой(текст: String) = copy(беда = текст, ждём = false)
    }

    /**
     * Код привязки на экране: ждём телефон.
     *
     * @param код `null` — кода ещё нет: либо просим его у сервера, либо просить нечем
     *   (тогда сказано в [беда]). Пустой строки здесь не бывает намеренно: пустой QR
     *   человек попробует отсканировать.
     */
    data class ПоказКода(
        val код: String?,
        override val беда: String? = null,
    ) : AuthState

    /** Устройство заведено. Дальше — окно переписок. */
    data class Готово(val userId: String, val deviceId: String) : AuthState {
        override val беда: String? get() = null
    }

    /** Устройство было заведено раньше: секрет и сессия уже в хранилище платформы. */
    data object УжеЗаведено : AuthState {
        override val беда: String? get() = null
    }
}
