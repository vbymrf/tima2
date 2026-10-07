package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.encryption.PinCrypto
import io.tima.core.network.DeviceKeysResult
import io.tima.domain.account.PinPhraseVerdict
import io.tima.domain.account.PinPort
import io.tima.domain.account.PinRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Замок пин-кода на процесс (ПЛАН-(ПН)-ПИН-КОДА): какие аккаунты открыты пином в этом запуске и
 * когда окно ушло из виду.
 *
 * **Запуск — замок** (Р2): процесс начинается с пустого списка открытых. **5 минут в фоне — снова
 * замок** (Р9): платформа говорит [visible] — телефон из `onStop`/`onStart`, ПК — когда окно
 * спрятано в трей или свёрнуто. Канал, звонки и приём сообщений под замком работают: он закрывает
 * экран, а не работу.
 */
object PinGate {
    private val open = mutableSetOf<String>()
    private var hiddenAt: Long? = null
    private val _relock = MutableStateFlow(0)

    /** Растёт, когда открытые надо закрыть снова — после 5 минут в фоне. */
    val relock: StateFlow<Int> = _relock.asStateFlow()

    /** Аккаунт, с которого перешли на аккаунт с пином: «Отмена» на замке возвращает к нему. */
    var returnTo: String? = null

    fun isOpen(userId: String): Boolean = userId in open

    fun opened(userId: String) {
        open += userId
    }

    /** Окно на экране или нет — зовёт платформа. */
    fun visible(visible: Boolean, now: Long = msNow()) {
        if (!visible) {
            if (hiddenAt == null) hiddenAt = now
            return
        }
        val since = hiddenAt ?: return
        hiddenAt = null
        if (now - since >= PinRules.AWAY_MS && open.isNotEmpty()) {
            open.clear()
            _relock.value += 1
            Journal.note(LogCode.PIN_LOCK, "замок снова — окно было скрыто", "минут" to (now - since) / 60_000)
        }
    }
}

/** Строка журнала о пин-коде (`PIN-LOCK`); пин и фраза в неё не попадают никогда. */
internal fun notePin(event: io.tima.feature.auth.PinEvent, value: Long) {
    val text = when (event) {
        io.tima.feature.auth.PinEvent.PhraseAccepted -> "фраза подошла — пин-код можно сменить или убрать"
        io.tima.feature.auth.PinEvent.RemovedByPhrase -> "пин-код убран — по фразе"
        io.tima.feature.auth.PinEvent.Removed -> "пин-код убран"
        io.tima.feature.auth.PinEvent.Unlocked -> "замок снят пин-кодом"
        io.tima.feature.auth.PinEvent.Wrong -> "неверный пин-код"
        io.tima.feature.auth.PinEvent.Paused -> "неверный пин-код — пауза"
        io.tima.feature.auth.PinEvent.PhraseOnly -> "пин-код неверно 10 раз — только фраза"
        io.tima.feature.auth.PinEvent.TurnedOn -> "пин-код включён"
        io.tima.feature.auth.PinEvent.SetAgain -> "пин-код задан заново"
    }
    when (event) {
        io.tima.feature.auth.PinEvent.PhraseOnly -> Journal.trouble(LogCode.PIN_LOCK, text)
        io.tima.feature.auth.PinEvent.Wrong -> Journal.note(LogCode.PIN_LOCK, text, "до паузы" to value)
        io.tima.feature.auth.PinEvent.Paused -> Journal.note(LogCode.PIN_LOCK, text, "секунд" to value)
        else -> Journal.note(LogCode.PIN_LOCK, text)
    }
}

/**
 * Пин-код открытого аккаунта для экранов: сам замок и сверка фразы для сброса (Р3, Р13).
 *
 * @param temporary временный аккаунт: фраза владельца тоже подходит.
 */
class PinHost(
    val lock: PinPort,
    val temporary: Boolean,
    val verify: suspend (String) -> PinPhraseVerdict,
)

/**
 * Сверить фразу для сброса пина (Р3, Р13): ключ личности аккаунта — с сервера, у временного ещё и
 * ключи владельца. Нет связи — [PinPhraseVerdict.Offline], пин остаётся прежним.
 */
internal suspend fun pinPhraseVerdict(network: Network, userId: String, temporary: Boolean, text: String): PinPhraseVerdict {
    val own = (network.keys.devicesOf(userId) as? DeviceKeysResult.Devices)?.identityPub
    val owners = if (temporary) network.virtuals.ownerIdentities() else emptyList()
    val keys = if (own == null && owners == null) null else listOfNotNull(own) + owners.orEmpty()
    val verdict = PinCrypto.verdict(text, keys)
    Journal.note(LogCode.PIN_LOCK, "сверка фразы для сброса пина", "итог" to verdict::class.simpleName, "временный" to temporary)
    return verdict
}
