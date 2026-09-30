package io.tima.core.network

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.abs

/**
 * Часы сервера — поправка «сервер минус устройство» (ПЛАН-ВЫХОДА-ИЗ-АККАУНТА.md, А2).
 *
 * ── ЗАЧЕМ ───────────────────────────────────────────────────────────────────
 *
 * Токен обновляется подписью с меткой времени, и сервер принимает её при расхождении часов
 * не больше двух минут (`DeviceTokenWindow`). 2026-09-30 часы ПК спешили на 3 мин 10 с — и
 * ни одно обновление не проходило: `401 stale_signature`. Устройство выглядело отключённым,
 * хотя его никто не отключал.
 *
 * Часы устройства — не наши, и чинить их приложению нечем. Зато каждый ответ сервера несёт
 * его время в заголовке `Date` (RFC 7231, точность — секунда). По нему и считается поправка,
 * а подпись ставится **временем сервера**. Сервер при этом не меняется (решение 5,
 * `Plan.md §0.0`).
 *
 * Поправка общая на процесс: сервер один, и расхождение часов — свойство устройства, а не
 * запроса.
 */
object ServerClock {

    /** Сервер минус устройство, мс. Ноль — пока сервер не ответил ни разу. */
    var offsetMillis: Long = 0L
        private set

    /** Последнее расхождение, о котором сказано в журнал, — чтобы писать только перемены. */
    private var told: Long = 0L

    /** Время сервера по часам устройства и поправке, мс эпохи. */
    fun now(local: Long = Clock.System.now().toEpochMilliseconds()): Long = local + offsetMillis

    /**
     * Учесть ответ сервера. [date] — заголовок `Date` («Wed, 30 Sep 2026 13:48:09 GMT»);
     * не разобрался — поправка не меняется.
     *
     * Точность заголовка — секунда, а ответ идёт сколько-то миллисекунд: поправка честна с
     * точностью до секунды, чего для окна в две минуты с запасом хватает.
     */
    fun observe(date: String?, local: Long = Clock.System.now().toEpochMilliseconds()) {
        val server = parseHttpDate(date ?: return) ?: return
        val offset = server - local
        offsetMillis = offset
        // В журнал — когда расхождение больше минуты и заметно сменилось: иначе строка
        // шла бы на каждый ответ.
        if (abs(offset) >= SKEW_TELL && abs(offset - told) >= SKEW_STEP) {
            told = offset
            val seconds = abs(offset) / 1000
            Journal.trouble(
                LogCode.CLOCK_SKEW,
                "часы устройства расходятся с сервером — подписываю временем сервера",
                "часы" to if (offset < 0) "спешат" else "отстают",
                "на" to "${seconds / 60} мин ${seconds % 60} с",
            )
        } else if (abs(offset) < SKEW_TELL && abs(told) >= SKEW_TELL) {
            told = 0L
            Journal.note(LogCode.CLOCK_SKEW, "часы устройства снова сходятся с сервером")
        }
    }

    /** Для проверок: вернуть исходное состояние. */
    fun reset() {
        offsetMillis = 0L
        told = 0L
    }

    private const val SKEW_TELL = 60_000L
    private const val SKEW_STEP = 30_000L
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * Разобрать дату HTTP (IMF-fixdate): «Wed, 30 Sep 2026 13:48:09 GMT» → мс эпохи.
 * Другие устаревшие формы RFC 7231 сервер на Go не шлёт — они не разбираются.
 */
fun parseHttpDate(text: String): Long? {
    val m = Regex("""^\w{3}, (\d{1,2}) (\w{3}) (\d{4}) (\d{2}):(\d{2}):(\d{2}) GMT$""").find(text.trim()) ?: return null
    val (day, mon, year, h, min, s) = m.destructured
    val month = MONTHS.indexOf(mon) + 1
    if (month == 0) return null
    return runCatching {
        LocalDateTime(year.toInt(), month, day.toInt(), h.toInt(), min.toInt(), s.toInt())
            .toInstant(TimeZone.UTC).toEpochMilliseconds()
    }.getOrNull()
}
