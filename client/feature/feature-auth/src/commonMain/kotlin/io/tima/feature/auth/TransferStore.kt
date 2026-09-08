package io.tima.feature.auth

import io.tima.core.ui.CurrentWords
import io.tima.core.ui.Words
import io.tima.core.ui.RussianWords
import io.tima.domain.account.TransferAcceptStep
import io.tima.domain.account.TransferStartStep
import io.tima.domain.account.TransferVirtual
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Передача виртуального аккаунта — ПЛАН-КОНТАКТОВ.md, Д12.
 *
 * **Две стороны в одном магазине, и это не экономия.** Одно устройство бывает и той, и
 * другой: человек отдаёт один аккаунт и в тот же день принимает другой. Разделять их
 * значило бы завести два одинаковых набора состояний и дважды написать один разбор
 * ответов.
 *
 * **Фраза не живёт в состоянии дольше вызова.** Как и при заведении аккаунта: приходит из
 * поля, уходит в подпись, стирается. Здесь это важнее вдвойне — фраза чужая, её дали на
 * время передачи.
 */
class TransferStore(
    private val transfer: TransferVirtual,
    /** Как называть код в ссылке для QR. Формат наш, поэтому и сборка наша. */
    private val payloadOf: (String) -> String,
    /** Разбор принесённого: ссылка, голый код или мусор. */
    private val parse: (String) -> String?,
    private val scope: CoroutineScope,
    /**
     * Словарь надписей — **ссылкой, а не значением** (ПЛАН-ЯЗЫКА, Я2-беды).
     *
     * Store не `@Composable`, и `Tima.words` ему недоступен. Лямбда зовётся в момент
     * беды, поэтому язык всегда текущий: переданный значением, он запомнился бы на всю
     * жизнь store, и после смены языка беда пришла бы на прежнем.
     */
    private val words: () -> Words = { CurrentWords.value },
) {
    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()

    // ── сторона передающего ─────────────────────────────────────────────────

    /**
     * Выдать код на аккаунт.
     *
     * Повторный вызов гасит прежний код — так решает сервер, и это правильно: иначе
     * аккаунт был бы обещан двоим и достался бы тому, кто быстрее.
     */
    fun giveCode(virtualUserId: String) {
        if (_state.value.working) return
        scope.launch {
            _state.value = _state.value.copy(
                side = TransferSide.Giving,
                virtualUserId = virtualUserId,
                working = true,
                trouble = null,
                code = null,
            )
            _state.value = when (val step = transfer.start(virtualUserId)) {
                is TransferStartStep.Code -> _state.value.copy(
                    working = false,
                    code = step.code,
                    payload = payloadOf(step.code),
                    minutesLeft = step.secondsLeft / 60,
                    attempts = step.attempts,
                )
                TransferStartStep.NotYours -> _state.value.copy(
                    working = false,
                    trouble = words().auth.notYourVirtual,
                )
                TransferStartStep.Offline -> _state.value.copy(
                    working = false,
                    trouble = words().trouble.didNotReach,
                )
            }
        }
    }

    /** Передумал. Пока код не предъявлен, передача не состоялась. */
    fun cancel(onCancelled: () -> Unit = {}) {
        val virtualUserId = _state.value.virtualUserId ?: return
        scope.launch {
            _state.value = _state.value.copy(working = true, trouble = null)
            val ушло = transfer.cancel(virtualUserId)
            _state.value = if (ушло) {
                _state.value.copy(working = false, code = null, payload = null, cancelled = true)
            } else {
                // Не сказать об этом нельзя: человек, уверенный, что отменил, не станет
                // следить за кодом — а тот ещё жив.
                _state.value.copy(
                    working = false,
                    trouble = words().auth.cancelDidNotReach,
                )
            }
            if (ушло) onCancelled()
        }
    }

    // ── сторона принимающего ────────────────────────────────────────────────

    /**
     * Встать на сторону принимающего.
     *
     * @param brought код, принесённый снаружи камерой. Ссылка целиком, а не разобранный
     *   код: разбор один и живёт в [take] — иначе строгая проверка формата окажется в
     *   двух местах и однажды разойдётся.
     */
    fun takingSide(brought: String = "") {
        _state.value = TransferState(side = TransferSide.Taking, brought = brought)
    }

    fun changedCode(line: String) {
        _state.value = _state.value.copy(brought = line, trouble = null)
    }

    fun changedPhrase(line: String) {
        _state.value = _state.value.copy(phrase = line, trouble = null)
    }

    /** Предъявить код с фразой. */
    fun take() {
        val state = _state.value
        if (state.working) return
        val code = parse(state.brought)
        if (code == null) {
            _state.value = state.copy(trouble = words().auth.notTransferCode)
            return
        }
        if (state.phrase.isBlank()) {
            _state.value = state.copy(trouble = words().auth.needAccountPhrase)
            return
        }
        val words = state.phrase.trim().split(' ', '\n', '\t').filter { it.isNotEmpty() }
        scope.launch {
            _state.value = state.copy(working = true, trouble = null)
            _state.value = when (val step = transfer.accept(code, words)) {
                is TransferAcceptStep.Taken -> _state.value.copy(
                    working = false,
                    phrase = "",
                    taken = step,
                )
                TransferAcceptStep.BadPhrase -> _state.value.copy(
                    working = false,
                    phrase = "",
                    trouble = words().auth.phraseDoesNotFit,
                )
                TransferAcceptStep.Burned -> _state.value.copy(
                    working = false,
                    phrase = "",
                    trouble = words().auth.threeTriesBurned,
                )
                TransferAcceptStep.CodeGone -> _state.value.copy(
                    working = false,
                    phrase = "",
                    trouble = words().auth.codeNotValid,
                )
                TransferAcceptStep.Offline -> _state.value.copy(
                    working = false,
                    phrase = "",
                    trouble = words().trouble.didNotReach,
                )
            }
        }
    }
}

/** Кто мы в этой передаче. */
enum class TransferSide { Giving, Taking }

data class TransferState(
    val side: TransferSide = TransferSide.Giving,
    val virtualUserId: String? = null,
    /** Выданный код. Показывается один раз: второй раз его негде взять. */
    val code: String? = null,
    /** Он же в виде ссылки — для QR. */
    val payload: String? = null,
    val minutesLeft: Int = 0,
    val attempts: Int = 0,
    val cancelled: Boolean = false,
    /** Что принесли: ссылка из камеры, вставленный текст или продиктованное. */
    val brought: String = "",
    /** Фраза передаваемого аккаунта. Живёт только пока её вводят. */
    val phrase: String = "",
    /** Аккаунт перешёл. `null`, пока не перешёл. */
    val taken: TransferAcceptStep.Taken? = null,
    val working: Boolean = false,
    val trouble: String? = null,
)
