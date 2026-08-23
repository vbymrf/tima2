package io.tima.feature.auth

import io.tima.domain.account.CodeRequestStep
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
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Телефон())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun номерИзменён(текст: String) {
        val текущее = _state.value as? AuthState.Телефон ?: return
        _state.value = текущее.copy(номер = текст, беда = null)
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
            _state.value = when (val шаг = register.requestCode(текущее.номер)) {
                is CodeRequestStep.CodeRequested -> AuthState.Код(
                    requestId = шаг.requestId,
                    телефон = текущее.номер,
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

    /** Человек нажал «Подтвердить». */
    fun подтвердить() {
        val текущее = _state.value as? AuthState.Код ?: return
        if (текущее.ждём) return
        _state.value = текущее.copy(ждём = true, беда = null)

        scope.launch {
            _state.value = when (val шаг = register.confirm(текущее.requestId, текущее.код)) {
                is RegistrationStep.Registered -> AuthState.Готово(шаг.userId, шаг.deviceId)

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

                RegistrationStep.IdentityMismatch -> текущее.копияСБедой(
                    "Этот номер уже связан с другой личностью: нужен вход по секретной фразе",
                )

                is RegistrationStep.Offline -> текущее.копияСБедой(нетСвязи(шаг.retryAfterMs))
                is RegistrationStep.Refused -> текущее.копияСБедой(шаг.reason)
            }
        }
    }

    /** «Назад» с экрана кода: номер сохраняется — его уже набрали. */
    fun назад() {
        val текущее = _state.value as? AuthState.Код ?: return
        _state.value = AuthState.Телефон(номер = текущее.телефон)
    }

    private companion object {
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
        val номер: String = "",
        override val беда: String? = null,
        /** Вызов идёт: кнопка занята, второе нажатие не посылает вторую SMS. */
        val ждём: Boolean = false,
    ) : AuthState {
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

    /** Устройство заведено. Дальше — окно переписок. */
    data class Готово(val userId: String, val deviceId: String) : AuthState {
        override val беда: String? get() = null
    }

    /** Устройство было заведено раньше: секрет и сессия уже в хранилище платформы. */
    data object УжеЗаведено : AuthState {
        override val беда: String? get() = null
    }
}
