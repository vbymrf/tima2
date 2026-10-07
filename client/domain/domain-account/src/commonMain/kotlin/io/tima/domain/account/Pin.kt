package io.tima.domain.account

/**
 * Пин-код — пароль этого устройства к этому аккаунту (ПЛАН-(ПН)-ПИН-КОДА, Р1).
 *
 * Хранится только на устройстве и работает без сети (Р13): ввод, счёт ошибок и паузы — здесь.
 * Это замок, а не шифрование (Р8): данные на диске защищает ключ устройства, как раньше.
 */
interface PinPort {
    /** Задан ли пин у этого аккаунта на этом устройстве. */
    fun isOn(): Boolean

    /** Задать или сменить. Ошибки ввода при этом обнуляются. */
    fun set(pin: String)

    /** Убрать пин совсем. */
    fun remove()

    /** Проверить введённое; ошибка считается и переживает перезапуск (Р10). */
    fun check(pin: String): PinCheck

    /** Что сейчас с вводом, не вводя: пауза, только фраза или можно вводить. */
    fun state(): PinCheck

    /** Фраза подошла (Р3): счёт ошибок и пауза снимаются. Сам пин остаётся до выбора человека. */
    fun phraseAccepted()
}

/** Чем кончился ввод пина. */
sealed interface PinCheck {
    /** Подошёл — или вводить можно (из [PinPort.state]). */
    data object Ok : PinCheck

    /** Не тот. [leftBeforePause] — сколько ещё ошибок до паузы; 0 — следующая уже с паузой. */
    data class Wrong(val leftBeforePause: Int) : PinCheck

    /** Пауза после ошибок (Р10): вводить нельзя до [untilMs] по часам устройства (Р15). */
    data class Paused(val untilMs: Long) : PinCheck

    /** Ошибок 10 (Р10): войти можно только секретной фразой, а для неё нужна сеть (Р14). */
    data object PhraseOnly : PinCheck
}

/** Правила пин-кода — одно место, из которого их читают и хранилище, и экраны. */
object PinRules {
    const val LENGTH = 4

    /** После стольких ошибок — пауза. */
    const val PAUSE_AFTER = 5

    /** После стольких — только фраза. */
    const val PHRASE_AFTER = 10

    /** Первая пауза; каждая следующая ошибка — вдвое дольше. */
    const val FIRST_PAUSE_MS = 30_000L

    /** Столько в фоне — и пин спрашивается снова (Р9). */
    const val AWAY_MS = 5 * 60_000L

    fun valid(pin: String): Boolean = pin.length == LENGTH && pin.all { it in '0'..'9' }

    /** Пауза после [fails] ошибок; 0 — паузы нет. */
    fun pauseAfter(fails: Int): Long =
        if (fails < PAUSE_AFTER) 0L else FIRST_PAUSE_MS shl (fails - PAUSE_AFTER).coerceAtMost(20)
}

/** Чем кончилась проверка фразы для сброса пина (Р3, Р13). */
sealed interface PinPhraseVerdict {
    data object Ok : PinPhraseVerdict

    /** Слова сложились, но это не фраза этого аккаунта (и не владельца — у временного). */
    data object Wrong : PinPhraseVerdict

    /** Слова не складываются во фразу — сказать до сервера, что не так. */
    data class Fault(val fault: PhraseFault) : PinPhraseVerdict

    /** Нет связи с сервером: сверить не с чем (Р13). Пин остаётся прежним. */
    data object Offline : PinPhraseVerdict
}
