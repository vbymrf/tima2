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
class ПривязкаStore(
    private val confirm: ConfirmDeviceLink,
    private val scope: CoroutineScope,
    код: String,
) {

    private val _state = MutableStateFlow<ПривязкаState>(разобрать(код))
    val state: StateFlow<ПривязкаState> = _state.asStateFlow()

    private val код: String = код

    /** Человек нажал «Доверить». */
    fun доверить() {
        val текущее = _state.value as? ПривязкаState.Спрашиваем ?: return
        if (текущее.ждём) return
        _state.value = текущее.copy(ждём = true, беда = null)

        scope.launch {
            _state.value = when (val шаг = confirm.confirm(код)) {
                is LinkConfirmStep.Confirmed -> ПривязкаState.Готово(шаг.deviceId)

                // Каждый отказ — своё действие человека, поэтому и текст свой.
                LinkConfirmStep.NotAPhone -> текущее.копияСБедой(
                    "Подтвердить подключение может только телефон — на компьютере это не работает",
                )
                LinkConfirmStep.SessionGone -> текущее.копияСБедой(
                    "Код больше не действует — попросите на том устройстве новый",
                )
                LinkConfirmStep.BadSignature -> текущее.копияСБедой(
                    "Код прочитан неверно — отсканируйте заново",
                )
                LinkConfirmStep.NotOurCode -> ПривязкаState.НеНашКод
                LinkConfirmStep.CannotSign -> текущее.копияСБедой(
                    "Это устройство не может подтверждать: у него нет своего ключа",
                )
                is LinkConfirmStep.Offline -> текущее.копияСБедой(
                    "Нет связи — попробуйте ещё раз",
                )
                is LinkConfirmStep.Refused -> текущее.копияСБедой(шаг.reason)
            }
        }
    }

    private fun разобрать(код: String): ПривязкаState {
        val прочитанное = confirm.прочитать(код) ?: return ПривязкаState.НеНашКод
        return ПривязкаState.Спрашиваем(имя = прочитанное.deviceName)
    }
}

/** Что видит человек на телефоне. */
sealed interface ПривязкаState {

    /**
     * Спрашиваем разрешение.
     *
     * @param имя как устройство себя назвало. `null` — имени в коде не было; тогда так и
     *   говорим, а не подставляем «Устройство»: подставленное имя человек примет за
     *   настоящее.
     */
    data class Спрашиваем(
        val имя: String?,
        val ждём: Boolean = false,
        val беда: String? = null,
    ) : ПривязкаState {
        fun копияСБедой(текст: String) = copy(беда = текст, ждём = false)
    }

    /** Подключено. */
    data class Готово(val deviceId: String) : ПривязкаState

    /** Отсканировано что-то другое: не наш код или испорченный. */
    data object НеНашКод : ПривязкаState
}
