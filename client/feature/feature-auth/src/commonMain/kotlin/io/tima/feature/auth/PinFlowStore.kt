package io.tima.feature.auth

import io.tima.domain.account.PhraseFault
import io.tima.domain.account.PinCheck
import io.tima.domain.account.PinPhraseVerdict
import io.tima.domain.account.PinPort
import io.tima.domain.account.PinRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Зачем открыт экран пин-кода (ПЛАН-(ПН)). */
enum class PinMode { Enable, Change, Remove, Forgot, Unlock }

/** Шаг экрана пин-кода. */
enum class PinStep { Current, Create, Repeat, Phrase, Choice, Done }

/** Чем кончилось — для ответа в настройках и для снятия замка. */
enum class PinResult { TurnedOn, Changed, TurnedOff, Unlocked }

/**
 * Что случилось с пин-кодом — для журнала (`PIN-LOCK`). Сам журнал ведёт `shared`: в `feature`
 * его нет, и текст строки живёт там же. Ни пин, ни фраза сюда не попадают никогда.
 */
enum class PinEvent { PhraseAccepted, RemovedByPhrase, Removed, Unlocked, Wrong, Paused, PhraseOnly, TurnedOn, SetAgain }

/** Что сказать под точками или под полем фразы. */
sealed interface PinMessage {
    data object Mismatch : PinMessage
    data class Wrong(val leftBeforePause: Int) : PinMessage
    data class Fault(val fault: PhraseFault) : PinMessage
    data object WrongPhrase : PinMessage
    data object Offline : PinMessage
}

data class PinFlowState(
    val mode: PinMode,
    val step: PinStep,
    /** Сколько цифр набрано — точки; сами цифры в состоянии не лежат. */
    val typed: Int = 0,
    val message: PinMessage? = null,
    /** Пауза после ошибок (Р10) — до этого времени по часам устройства (Р15); 0 — нет. */
    val pausedUntil: Long = 0,
    /** После 10 ошибок — только фраза (Р10, Р14). */
    val phraseOnly: Boolean = false,
    /** Фраза сверяется — нужна сеть (Р13). */
    val checking: Boolean = false,
    val result: PinResult? = null,
)

/**
 * Экран пин-кода — один на все случаи: включить, сменить, убрать, забыли, разблокировать
 * (ПЛАН-(ПН)-ПИН-КОДА, пробы «д»–«з»).
 *
 * **Цифры не лежат в состоянии**: набранное живёт в этом объекте до четвёртой цифры и сразу
 * стирается. Состояние знает только, сколько точек закрасить.
 *
 * **Неверный нынешний пин считается и здесь** — в настройках так же, как на замке: иначе
 * «Сменить пин-код» был бы перебором без пауз.
 *
 * @param phrase сверка фразы для сброса (Р3): своя фраза аккаунта или, у временного, владельца.
 *   Нужна сеть (Р13): без неё — [PinPhraseVerdict.Offline], пин остаётся прежним.
 */
class PinFlowStore(
    mode: PinMode,
    private val pin: PinPort,
    private val phrase: suspend (String) -> PinPhraseVerdict,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    /** Событие для журнала (`PIN-LOCK`) и число при нём: сколько до паузы, секунд паузы. */
    private val note: (PinEvent, Long) -> Unit = { _, _ -> },
) {
    private val typed = StringBuilder()
    private var first: String? = null

    private val _state = MutableStateFlow(start(mode))
    val state: StateFlow<PinFlowState> = _state.asStateFlow()

    private fun start(mode: PinMode): PinFlowState = when (mode) {
        PinMode.Enable -> PinFlowState(mode, PinStep.Create)
        PinMode.Forgot -> PinFlowState(mode, PinStep.Phrase)
        else -> blockedOr(PinFlowState(mode, PinStep.Current))
    }

    /** Пауза или «только фраза», если они уже стоят — до первой цифры. */
    private fun blockedOr(s: PinFlowState): PinFlowState = when (val b = pin.state()) {
        is PinCheck.Paused -> s.copy(pausedUntil = b.untilMs)
        PinCheck.PhraseOnly -> s.copy(step = PinStep.Phrase, phraseOnly = true)
        else -> s
    }

    fun digit(d: Char) {
        val s = _state.value
        if (d !in '0'..'9' || s.step !in setOf(PinStep.Current, PinStep.Create, PinStep.Repeat)) return
        if (s.pausedUntil > now()) return
        if (typed.length >= PinRules.LENGTH) return
        typed.append(d)
        _state.value = s.copy(typed = typed.length, message = if (typed.length == 1) null else s.message, pausedUntil = 0)
        if (typed.length == PinRules.LENGTH) complete()
    }

    fun erase() {
        if (typed.isEmpty()) return
        typed.setLength(typed.length - 1)
        _state.value = _state.value.copy(typed = typed.length)
    }

    /** «Забыли пин-код?» — к фразе. */
    fun forgot() {
        clear()
        _state.value = _state.value.copy(step = PinStep.Phrase, message = null, typed = 0)
    }

    /** Фраза введена — сверить (нужна сеть). Текст дальше не хранится. */
    fun submitPhrase(text: String) {
        if (_state.value.checking || text.isBlank()) return
        _state.value = _state.value.copy(checking = true, message = null)
        scope.launch {
            val verdict = phrase(text)
            val s = _state.value
            _state.value = when (verdict) {
                PinPhraseVerdict.Ok -> {
                    pin.phraseAccepted()
                    note(PinEvent.PhraseAccepted, 0)
                    s.copy(step = PinStep.Choice, checking = false, phraseOnly = false, pausedUntil = 0)
                }
                PinPhraseVerdict.Wrong -> s.copy(checking = false, message = PinMessage.WrongPhrase)
                PinPhraseVerdict.Offline -> s.copy(checking = false, message = PinMessage.Offline)
                is PinPhraseVerdict.Fault -> s.copy(checking = false, message = PinMessage.Fault(verdict.fault))
            }
        }
    }

    /** Фраза подошла: задать новый пин или убрать совсем. */
    fun choose(newPin: Boolean) {
        if (_state.value.step != PinStep.Choice) return
        if (newPin) {
            _state.value = _state.value.copy(step = PinStep.Create, message = null, typed = 0)
        } else {
            pin.remove()
            note(PinEvent.RemovedByPhrase, 0)
            _state.value = _state.value.copy(step = PinStep.Done, result = PinResult.TurnedOff)
        }
    }

    /** Пауза могла кончиться — экран зовёт раз в секунду, пока она идёт. */
    fun tick() {
        val s = _state.value
        if (s.pausedUntil != 0L && s.pausedUntil <= now()) _state.value = s.copy(pausedUntil = 0, message = null)
    }

    private fun complete() {
        val entered = typed.toString()
        clear()
        val s = _state.value
        when (s.step) {
            PinStep.Current -> when (val r = pin.check(entered)) {
                PinCheck.Ok -> when (s.mode) {
                    PinMode.Remove -> {
                        pin.remove()
                        note(PinEvent.Removed, 0)
                        _state.value = s.copy(step = PinStep.Done, typed = 0, result = PinResult.TurnedOff)
                    }
                    PinMode.Unlock -> {
                        note(PinEvent.Unlocked, 0)
                        _state.value = s.copy(step = PinStep.Done, typed = 0, result = PinResult.Unlocked)
                    }
                    else -> _state.value = s.copy(step = PinStep.Create, typed = 0, message = null)
                }
                is PinCheck.Wrong -> {
                    note(PinEvent.Wrong, r.leftBeforePause.toLong())
                    _state.value = s.copy(typed = 0, message = PinMessage.Wrong(r.leftBeforePause))
                }
                is PinCheck.Paused -> {
                    note(PinEvent.Paused, (r.untilMs - now()) / 1000)
                    _state.value = s.copy(typed = 0, message = null, pausedUntil = r.untilMs)
                }
                PinCheck.PhraseOnly -> {
                    note(PinEvent.PhraseOnly, 0)
                    _state.value = s.copy(typed = 0, message = null, step = PinStep.Phrase, phraseOnly = true)
                }
            }
            PinStep.Create -> {
                first = entered
                _state.value = s.copy(step = PinStep.Repeat, typed = 0, message = null)
            }
            PinStep.Repeat -> {
                if (entered == first) {
                    pin.set(entered)
                    val result = if (s.mode == PinMode.Enable) PinResult.TurnedOn else PinResult.Changed
                    note(if (result == PinResult.TurnedOn) PinEvent.TurnedOn else PinEvent.SetAgain, 0)
                    _state.value = s.copy(step = PinStep.Done, typed = 0, result = result)
                } else {
                    _state.value = s.copy(step = PinStep.Create, typed = 0, message = PinMessage.Mismatch)
                }
                first = null
            }
            else -> Unit
        }
    }

    private fun clear() {
        typed.setLength(0)
    }
}
