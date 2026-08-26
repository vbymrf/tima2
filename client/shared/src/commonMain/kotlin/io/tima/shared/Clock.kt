package io.tima.shared

import kotlinx.datetime.Clock

/**
 * Текущее время в миллисекундах.
 *
 * `kotlinx-datetime`, а не `System.currentTimeMillis()`: последнего нет ни на iOS, ни в
 * общем коде вообще. Именно такие вызовы и держали сборку приложения платформенной — по
 * одному на каждую очередь и каждый повтор.
 */
internal fun msNow(): Long = Clock.System.now().toEpochMilliseconds()
