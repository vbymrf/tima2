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
     * Словарь надписей — **ссылкой, а не значением** (ПЛАН-(Я)-ЯЗЫКА, Я2-беды).
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
    /** Перерегистрация подготовлена (ДУ9): выйти и войти тем же номером новой личностью. */
    private val onRereg: (io.tima.domain.account.PrepareRereg.Ready) -> Unit = {},
    /** Дата для текстов перерегистрации — по местным часам; формат — у того, кто знает время. */
    private val dateText: (Long) -> String = { it.toString() },
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
            // Закрыто ли «Начать заново» (ДУ10) — от этого зависит, что показать: кнопку или запрет.
            scope.launch {
                val banned = runCatching { actions.startAnewBanned() }.getOrNull()
                _state.value = _state.value.copy(startAnewBanned = banned)
            }
            checkCopyRotation()
            refreshRereg()
        }
    }

    /** Идёт ли перерегистрация (ДУ9) — от этого зависят панели и тексты. */
    fun refreshRereg() {
        val actions = trust ?: return
        scope.launch {
            val r = runCatching { actions.rereg() }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(rereg = r)
        }
    }

    /** Панель перерегистрации на момент [now]; `null` — не узнали, панели нет. */
    fun reregView(now: Long): ReregView? {
        val r = _state.value.rereg ?: return null
        if (!r.active) return ReregView(text = null, canStart = true)
        val open = now >= r.windowFrom && now < r.windowTo
        return ReregView(
            text = reregText(r, now),
            canClaim = !r.isNew && !r.disputed && now < r.windowTo,
            canConfirm = open && !r.confirmed && (r.isNew || r.disputed),
            twoPhrases = r.isNew,
        )
    }

    /** Текст перерегистрации для этой стороны — §2б; `null` — процесса нет. */
    fun reregText(r: io.tima.domain.account.Rereg?, now: Long): String? {
        if (r == null || !r.active) return null
        val w = words().auth
        val from = dateText(r.windowFrom)
        val to = dateText(r.windowTo)
        val open = now >= r.windowFrom
        return when {
            r.confirmed -> w.reregConfirmedWait(to)
            r.isNew && open -> w.reregWindowNew(to)
            r.isNew && r.disputed -> w.reregDisputedNewAbout(from, to)
            r.isNew -> w.reregNewAbout(from, to)
            r.disputed && open -> w.reregWindowOld(to)
            r.disputed -> w.reregClaimedAbout(from, to)
            else -> w.reregOldAbout
        }
    }

    /**
     * Начать перерегистрацию (ДУ9): фраза сверяется с аккаунтом, вызов подписывается — и
     * приложение выходит, чтобы войти тем же номером новой личностью. Слова дальше не живут.
     */
    fun startRereg(phrase: String) {
        val actions = trust ?: return
        if (_state.value.trusting) return
        val words = phrase.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        _state.value = _state.value.copy(trusting = true, trustNotice = null)
        scope.launch {
            val step = runCatching { actions.prepareRereg(words) }.getOrElse {
                io.tima.domain.account.PrepareRereg.Failed(TrustStep.Refused(it.message ?: "?"))
            }
            _state.value = _state.value.copy(trusting = false)
            when (step) {
                is io.tima.domain.account.PrepareRereg.Ready -> onRereg(step)
                is io.tima.domain.account.PrepareRereg.Failed -> _state.value = _state.value.copy(trustNotice = notice(step.step))
            }
        }
    }

    /**
     * Код из SMS для заявки или подтверждения (ДУ9) — на номер аккаунта. У каждого шага свой
     * предел SMS (Р53): заявка, подтверждение Н, подтверждение С.
     */
    fun sendReregCode() {
        val actions = trust ?: return
        if (_state.value.trusting) return
        val r = _state.value.rereg
        val purpose = when {
            r == null || !r.active -> return
            !r.isNew && !r.disputed -> "rereg_claim"
            r.isNew -> "rereg_confirm_new"
            else -> "rereg_confirm_old"
        }
        _state.value = _state.value.copy(trusting = true, trustNotice = null)
        scope.launch {
            val sent = runCatching { actions.sendCode(purpose) }.getOrDefault(io.tima.domain.account.CodeSend.Failed)
            val w = words().auth
            _state.value = _state.value.copy(
                trusting = false,
                reregCode = (sent as? io.tima.domain.account.CodeSend.Sent)?.code,
                trustNotice = when (sent) {
                    is io.tima.domain.account.CodeSend.Sent -> sent.code.devCode?.let { w.standSentCode(it) }
                    io.tima.domain.account.CodeSend.Limited -> w.tooManyCodes
                    io.tima.domain.account.CodeSend.Failed -> w.trustFailed(w.tryAgain)
                },
            )
        }
    }

    /**
     * «Аккаунт украден» ([oldPhrase] = `null`, своя фраза) или подтверждение в окне; у Н
     * подтверждение просит и прежнюю фразу — [oldPhrase].
     */
    fun rereg(claim: Boolean, phrase: String, oldPhrase: String?, code: String) {
        val actions = trust ?: return
        val sent = _state.value.reregCode ?: return
        if (_state.value.trusting) return
        fun split(text: String) = text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        _state.value = _state.value.copy(trusting = true, trustNotice = null)
        scope.launch {
            val step = runCatching {
                if (claim) actions.claimRereg(split(phrase), sent.requestId, code.trim())
                else actions.confirmRereg(split(phrase), oldPhrase?.let(::split), sent.requestId, code.trim())
            }.getOrElse { TrustStep.Refused(it.message ?: "?") }
            val w = words().auth
            _state.value = _state.value.copy(
                trusting = false,
                reregCode = if (step == TrustStep.Done) null else sent,
                trustNotice = when {
                    step == TrustStep.Done -> if (claim) w.reregClaimed else w.reregConfirmed
                    step is TrustStep.Refused && step.reason == "not_in_window" -> w.reregNotInWindow
                    else -> notice(step)
                },
            )
            if (step == TrustStep.Done) refreshRereg()
        }
    }

    /** Пора ли сменить ключ копии — после отключения своего устройства (М5). */
    private fun checkCopyRotation() {
        val actions = trust ?: return
        scope.launch {
            val due = runCatching { actions.copyRotationDue() }.getOrDefault(false)
            val missing = runCatching { actions.copyMissing() }.getOrNull() == true
            _state.value = _state.value.copy(copyRotationDue = due, copyMissing = missing)
        }
    }

    /** Завести копию ключей фразой (Р44) — работающее устройство фразу больше не вводит. */
    fun startCopy(phrase: String) {
        val actions = trust ?: return
        if (_state.value.trusting) return
        val words = phrase.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        _state.value = _state.value.copy(trusting = true, trustNotice = null)
        scope.launch {
            val step = runCatching { actions.startCopy(words) }.getOrElse { TrustStep.Refused(it.message ?: "?") }
            _state.value = _state.value.copy(
                trusting = false,
                trustNotice = if (step == TrustStep.Done) words().auth.copyStarted else notice(step),
                copyMissing = if (step == TrustStep.Done) false else _state.value.copyMissing,
            )
        }
    }

    /** Перевести копию ключей на новую пару фразой (М5). Слова не хранятся. */
    fun rotateCopy(phrase: String) {
        val actions = trust ?: return
        if (_state.value.trusting) return
        val words = phrase.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        _state.value = _state.value.copy(trusting = true, trustNotice = null)
        scope.launch {
            val step = runCatching { actions.rotateCopy(words) }.getOrElse { TrustStep.Refused(it.message ?: "?") }
            _state.value = _state.value.copy(
                trusting = false,
                trustNotice = if (step == TrustStep.Done) words().auth.copyRotated else notice(step),
                copyRotationDue = if (step == TrustStep.Done) false else _state.value.copyRotationDue,
            )
        }
    }

    /** Запрет «Начать заново»: сначала SMS на номер аккаунта (ДУ10). */
    fun sendBanCode() {
        val actions = trust ?: return
        if (_state.value.trusting) return
        _state.value = _state.value.copy(trusting = true, trustNotice = null)
        scope.launch {
            val sent = runCatching { actions.sendBanCode() }.getOrNull()
            _state.value = _state.value.copy(
                trusting = false,
                banCode = sent,
                trustNotice = if (sent == null) words().auth.trustFailed(words().auth.tryAgain) else sent.devCode?.let { words().auth.standSentCode(it) },
            )
        }
    }

    /**
     * Закрыть «Начать заново» навсегда (Р41): фраза и код из SMS. Слова не хранятся — уходят в
     * подпись и дальше не живут.
     */
    fun banStartAnew(phrase: String, code: String) {
        val actions = trust ?: return
        val sent = _state.value.banCode ?: return
        if (_state.value.trusting) return
        val words = phrase.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        _state.value = _state.value.copy(trusting = true, trustNotice = null)
        scope.launch {
            val step = runCatching { actions.banStartAnew(words, sent.requestId, code.trim()) }.getOrElse { TrustStep.Refused(it.message ?: "?") }
            _state.value = _state.value.copy(
                trusting = false,
                trustNotice = if (step == TrustStep.Done) words().auth.banDone else notice(step),
                startAnewBanned = if (step == TrustStep.Done) true else _state.value.startAnewBanned,
                banCode = if (step == TrustStep.Done) null else sent,
            )
        }
    }

    /** Показать код заверения этого устройства (Р32). */
    fun showCertifyCode() {
        val actions = trust ?: return
        scope.launch {
            val code = runCatching { actions.certifyCode() }.getOrNull()
            _state.value = _state.value.copy(certifyCode = code, trustNotice = if (code == null) words().auth.trustFailed(words().auth.tryAgain) else null)
        }
    }

    /** Заверить устройство по отсканированному коду (Р32). */
    fun certifyByCode(code: String, done: () -> Unit = {}) {
        val actions = trust ?: return
        if (_state.value.trusting) return
        _state.value = _state.value.copy(trusting = true, trustNotice = null)
        scope.launch {
            val step = runCatching { actions.certifyByCode(code) }.getOrElse { TrustStep.Refused(it.message ?: "?") }
            _state.value = _state.value.copy(trusting = false, trustNotice = notice(step))
            if (step == TrustStep.Done) {
                refresh()
                done()
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
            // Предел ожидания (ПЛАН-(А)-ВЫХОДА-ИЗ-АККАУНТА.md, А7). 2026-09-30 на ПК запрос ждал
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
        TrustStep.WrongCode -> words().auth.wrongCode
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
                    // Отключённое могло унести ключ копии — сервер ставит отметку (М5).
                    checkCopyRotation()
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

/** Что показать на панели перерегистрации (ДУ9). */
data class ReregView(
    val text: String?,
    /** Процесса нет — можно запустить. */
    val canStart: Boolean = false,
    /** Это С, спора ещё нет — «Аккаунт украден». */
    val canClaim: Boolean = false,
    /** Окно открыто, эта сторона ещё не подтвердила. */
    val canConfirm: Boolean = false,
    /** Подтверждает Н — нужны обе фразы. */
    val twoPhrases: Boolean = false,
)

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
    /** Код заверения этого устройства для показа QR (Р32); `null` — не показываем. */
    val certifyCode: String? = null,
    /** «Начать заново» закрыто на аккаунте (ДУ10); `null` — не узнали, панели нет. */
    val startAnewBanned: Boolean? = null,
    /** Код запрета отправлен — ждём его и фразу. */
    val banCode: io.tima.domain.account.BanCode? = null,
    /** Пора сменить ключ копии — отключили своё устройство (М5). */
    val copyRotationDue: Boolean = false,
    /** Копия ключей у личности ещё не заведена (Р44). */
    val copyMissing: Boolean = false,
    /** Перерегистрация (ДУ9); `null` — не узнали. */
    val rereg: io.tima.domain.account.Rereg? = null,
    /** Код для заявки или подтверждения перерегистрации отправлен. */
    val reregCode: io.tima.domain.account.BanCode? = null,
)
