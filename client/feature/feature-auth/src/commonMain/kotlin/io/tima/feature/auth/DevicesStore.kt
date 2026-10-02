package io.tima.feature.auth

import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.core.words.RussianWords
import io.tima.domain.account.AccountDevice
import io.tima.domain.account.TrustStep
import io.tima.domain.account.DeviceTrustActions
import io.tima.domain.account.DevicesStep
import io.tima.domain.account.MyDevices
import io.tima.domain.account.RevokeStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Свои устройства: список и отключение.
 *
 * **Отключение спрашивает подтверждение.** Отозванное устройство обратно не вернуть — на
 * нём придётся заводиться заново, — а строки в списке похожи друг на друга: «Телефон» и
 * «Телефон». Нажатие без вопроса означает, что человек однажды выкинет то устройство, с
 * которого читает.
 */
class DevicesStore(
    private val devices: MyDevices,
    private val scope: CoroutineScope,
    /**
     * Словарь надписей — **ссылкой, а не значением** (ПЛАН-ЯЗЫКА, Я2-беды).
     *
     * Store не `@Composable`, и `Tima.words` ему недоступен. Лямбда зовётся в момент
     * беды, поэтому язык всегда текущий: переданный значением, он запомнился бы на всю
     * жизнь store, и после смены языка беда пришла бы на прежнем.
     */
    private val words: () -> Words = { CurrentWords.value },
    /** Доверие к своим устройствам (ДУ5); `null` — действий доверия нет (проверки, снимки). */
    private val trust: DeviceTrustActions? = null,
    /** Новая личность отменена — снять событие «начали заново». */
    private val onIdentityRestored: () -> Unit = {},
) {

    private val _state = MutableStateFlow(DevicesState(expect = true))
    val state: StateFlow<DevicesState> = _state.asStateFlow()

    init {
        refresh()
        // Начинали ли с номера заново (ДУ6) — спрашиваем при каждом открытии экрана.
        trust?.let { actions ->
            scope.launch {
                val r = runCatching { actions.replaced() }.getOrDefault(false)
                _state.value = _state.value.copy(replaced = r)
            }
        }
    }

    /** Отменить новую личность фразой (ДУ6, Р27, Р31). */
    fun cancelNewIdentity(phrase: String) {
        val actions = trust ?: return
        if (_state.value.trusting) return
        val words = phrase.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        _state.value = _state.value.copy(trusting = true, trustNotice = null)
        scope.launch {
            val step = runCatching { actions.cancelNewIdentity(words) }.getOrElse { TrustStep.Refused(it.message ?: "?") }
            _state.value = _state.value.copy(
                trusting = false,
                trustNotice = if (step == TrustStep.Done) words().auth.replacedCancelled else notice(step),
                replaced = if (step == TrustStep.Done || step == TrustStep.NothingToCancel) false else _state.value.replaced,
            )
            if (step == TrustStep.Done) onIdentityRestored()
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(expect = true, trouble = null)
        scope.launch {
            // Предел ожидания (ПЛАН-ВЫХОДА-ИЗ-АККАУНТА.md, А7). 2026-09-30 на ПК запрос ждал
            // токен, которого сервер не выдавал, и экран держал «Смотрим…» без конца.
            // Упавший вызов — тоже не вечное ожидание: исключение раньше убивало корутину,
            // и состояние оставалось «ждём».
            val step = withTimeoutOrNull(LIST_TIMEOUT_MS) { runCatching { devices.list() }.getOrNull() }
            _state.value = when (step) {
                null -> _state.value.copy(expect = false, trouble = words().auth.listTimedOut)
                is DevicesStep.Devices -> _state.value.copy(
                    devices = step.devices,
                    expect = false,
                    trouble = null,
                )
                is DevicesStep.Offline -> _state.value.copy(
                    expect = false,
                    trouble = words().auth.listHasNothing,
                )
                is DevicesStep.Refused -> _state.value.copy(expect = false, trouble = step.reason)
            }
        }
    }

    private companion object {
        const val LIST_TIMEOUT_MS = 20_000L
    }

    /** Держит ли это устройство свой ключ подписи устройств — показывать ли «Заверить». */
    fun holdsKey(): Boolean = trust?.holdsKey() == true

    /**
     * Подтвердить это устройство фразой (ДУ5). Слова не хранятся: приходят, превращаются в
     * подпись и уходят.
     */
    fun confirmWithPhrase(phrase: String) {
        val actions = trust ?: return
        if (_state.value.trusting) return
        val words = phrase.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        _state.value = _state.value.copy(trusting = true, trustNotice = null)
        scope.launch {
            val step = runCatching { actions.confirmWithPhrase(words) }.getOrElse { TrustStep.Refused(it.message ?: "?") }
            _state.value = _state.value.copy(trusting = false, trustNotice = notice(step))
            if (step == TrustStep.Done) refresh()
        }
    }

    /** Заверить другое своё устройство ключом этого телефона (ДУ5). */
    fun certify(deviceId: String) {
        val actions = trust ?: return
        if (_state.value.trusting) return
        _state.value = _state.value.copy(trusting = true, trustNotice = null)
        scope.launch {
            val step = runCatching { actions.certify(deviceId) }.getOrElse { TrustStep.Refused(it.message ?: "?") }
            _state.value = _state.value.copy(trusting = false, trustNotice = notice(step))
            if (step == TrustStep.Done) refresh()
        }
    }

    private fun notice(step: TrustStep): String = when (step) {
        TrustStep.Done -> words().auth.trustDone
        TrustStep.WrongPhrase -> words().auth.wrongPhrase
        TrustStep.NoKey -> words().auth.trustNoKey
        TrustStep.NothingToCancel -> words().auth.nothingToCancel
        is TrustStep.Offline -> words().auth.trustFailed(words().auth.tryAgain)
        is TrustStep.Refused -> words().auth.trustFailed(step.reason)
    }

    /** Человек нажал «Отключить» у строки: спрашиваем. */
    fun ask(deviceId: String) {
        _state.value = _state.value.copy(ask = deviceId, trouble = null)
    }

    /** Передумал. */
    fun changedMind() {
        _state.value = _state.value.copy(ask = null)
    }

    /** Подтвердил отключение. */
    fun revoke() {
        val id = _state.value.ask ?: return
        if (_state.value.expect) return
        _state.value = _state.value.copy(expect = true, trouble = null)

        scope.launch {
            when (val step = devices.revoke(id)) {
                // Список перечитываем, а не правим на месте: сервер мог отозвать не только
                // это устройство (например, чужая привязка отвалилась), и правка по памяти
                // разошлась бы с действительностью.
                RevokeStep.Revoked, RevokeStep.Gone -> {
                    _state.value = _state.value.copy(ask = null, expect = false)
                    refresh()
                }
                RevokeStep.LastDevice -> _state.value = _state.value.copy(
                    ask = null,
                    expect = false,
                    trouble = words().auth.lastDevice,
                )
                is RevokeStep.Offline -> _state.value = _state.value.copy(
                    expect = false,
                    trouble = words().auth.deviceNotDisconnected,
                )
                is RevokeStep.Refused -> _state.value = _state.value.copy(
                    ask = null,
                    expect = false,
                    trouble = step.reason,
                )
            }
        }
    }
}

/**
 * Что видит человек в списке устройств.
 *
 * @param спрашиваем `device_id`, про который задан вопрос «отключить?». `null` — вопроса
 *   нет. Хранится здесь, а не в экране: вопрос — это состояние, и после поворота телефона
 *   он должен остаться тем же.
 */
data class DevicesState(
    val devices: List<AccountDevice> = emptyList(),
    val expect: Boolean = false,
    val ask: String? = null,
    val trouble: String? = null,
    /** Идёт действие доверия (ДУ5). */
    val trusting: Boolean = false,
    /** Что сказать о последнем действии доверия. */
    val trustNotice: String? = null,
    /** С номера начали заново, а это устройство — прежней личности (ДУ6). */
    val replaced: Boolean = false,
)
