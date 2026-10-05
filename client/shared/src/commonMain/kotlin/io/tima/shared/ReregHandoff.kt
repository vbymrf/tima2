package io.tima.shared

import io.tima.domain.account.PrepareRereg
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Перерегистрация (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ9) — от «Секретная фраза и устройства» до
 * экрана входа: номер аккаунта и доказательство прежней фразы. Как [PhraseOnce]: в памяти
 * процесса, берётся один раз и сам забывается — вызов, который в нём подписан, живёт минуты.
 */
object ReregHandoff {
    @kotlin.concurrent.Volatile
    private var held: PrepareRereg.Ready? = null

    @kotlin.concurrent.Volatile
    private var at = 0L

    fun hold(ready: PrepareRereg.Ready) {
        held = ready
        at = msNow()
    }

    fun take(): PrepareRereg.Ready? {
        val ready = held
        held = null
        return ready?.takeIf { msNow() - at <= KEEP_MS }
    }

    private const val KEEP_MS = 10 * 60 * 1000L
}

/** Дата и время по местным часам — для текстов перерегистрации: «05.10.2026 21:40». */
fun reregDate(ms: Long): String {
    val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${two(t.dayOfMonth)}.${two(t.monthNumber)}.${t.year} ${two(t.hour)}:${two(t.minute)}"
}
