package io.tima.feature.auth

import io.tima.domain.account.ConfirmDeviceLink
import io.tima.domain.account.LinkConfirmStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Подтверждение привязки на телефоне.
 *
 * **Скан и подтверждение — два разных действия.** В v1 их однажды объединили: человек
 * наводил камеру, и устройство добавлялось к аккаунту немедленно. Прав оказался макет
 * (`doc_UI/24`), а не код: скан — это не решение. Код можно прислать в переписке, наклеить
 * на стену, показать на своём экране под видом чужого. Между сканом и доверием обязан
 * стоять экран, который называет устройство и говорит, какой доступ выдаётся.
 *
 * **Показывается имя, а не ключ.** Сверять тридцать два байта на глаз человек не станет, а
 * имя он сам видел минуту назад на своём же компьютере.
 */
class LinkStore(
    private val confirm: ConfirmDeviceLink,
    private val scope: CoroutineScope,
    code: String,
) {

    private val _state = MutableStateFlow<LinkState>(parse(code))
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val code: String = code

    /** Человек нажал «Доверить». */
    fun trust() {
        val current = _state.value as? LinkState.Ask ?: return
        if (current.expect) return
        _state.value = current.copy(expect = true, trouble = null)

        scope.launch {
            _state.value = when (val step = confirm.confirm(code)) {
                is LinkConfirmStep.Confirmed -> LinkState.Done(step.deviceId)

                // Каждый отказ — своё действие человека, поэтому и текст свой.
                LinkConfirmStep.NotAPhone -> current.copyWithTrouble(
                    "Подтвердить подключение может только телефон — на компьютере это не работает",
                )
                LinkConfirmStep.SessionGone -> current.copyWithTrouble(
                    "Код больше не действует — попросите на том устройстве новый",
                )
                LinkConfirmStep.BadSignature -> current.copyWithTrouble(
                    "Код прочитан неверно — отсканируйте заново",
                )
                LinkConfirmStep.NotOurCode -> LinkState.NotOurCode
                LinkConfirmStep.CannotSign -> current.copyWithTrouble(
                    "Это устройство не может подтверждать: у него нет своего ключа",
                )
                is LinkConfirmStep.Offline -> current.copyWithTrouble(
                    "Нет связи — попробуйте ещё раз",
                )
                is LinkConfirmStep.Refused -> current.copyWithTrouble(step.reason)
            }
        }
    }

    private fun parse(code: String): LinkState {
        val read = confirm.read(code) ?: return LinkState.NotOurCode
        return LinkState.Ask(name = read.deviceName)
    }
}

/** Что видит человек на телефоне. */
sealed interface LinkState {

    /**
     * Спрашиваем разрешение.
     *
     * @param имя как устройство себя назвало. `null` — имени в коде не было; тогда так и
     *   говорим, а не подставляем «Устройство»: подставленное имя человек примет за
     *   настоящее.
     */
    data class Ask(
        val name: String?,
        val expect: Boolean = false,
        val trouble: String? = null,
    ) : LinkState {
        fun copyWithTrouble(text: String) = copy(trouble = text, expect = false)
    }

    /** Подключено. */
    data class Done(val deviceId: String) : LinkState

    /** Отсканировано что-то другое: не наш код или испорченный. */
    data object NotOurCode : LinkState
}
