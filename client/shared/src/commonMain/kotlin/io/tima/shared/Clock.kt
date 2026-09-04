package io.tima.shared

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Текущее время в миллисекундах.
 *
 * `kotlinx-datetime`, а не `System.currentTimeMillis()`: последнего нет ни на iOS, ни в
 * общем коде вообще. Именно такие вызовы и держали сборку приложения платформенной — по
 * одному на каждую очередь и каждый повтор.
 */
internal fun msNow(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Эпоха escrow через столько месяцев — «2026-10».
 *
 * Тот же формат, что у сервера и у ротации ключа: сроки живут месяцами, потому что месяц
 * уже двигает ключ, а срок в днях потребовал бы ежедневной фоновой работы ради того же.
 */
internal fun epochAfter(months: Int): String {
    val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
    val total = now.year * 12 + (now.monthNumber - 1) + months
    val month = total % 12 + 1
    return (total / 12).toString() + "-" + month.toString().padStart(2, '0')
}
