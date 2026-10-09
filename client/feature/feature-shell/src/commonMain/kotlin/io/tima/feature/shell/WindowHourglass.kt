package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Песочные часы, пока строится окно (решение заказчика 2026-10-08, ПЛАН-(БС) Э4).
 *
 * Смена окна строит новое окно с нуля, и на слабом телефоне кадр перехода долгий (отладочная
 * сборка на Redmi A3 — около секунды). Экран стоял, человек решал, что свайп не сработал, и
 * свайпал снова — проскакивал на окно дальше. Часы говорят «пойман, жди».
 *
 * Рисуются поверх прежнего окна **кадром раньше** перехода — тяжёлый кадр и есть тот, в
 * котором их надо видеть (см. [WindowSwitchClock]).
 */
@Composable
fun WindowHourglass(modifier: Modifier = Modifier) {
    val colors = Tima.colors
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(HOURGLASS_SIDE)
                .background(colors.surface, RoundedCornerShape(HOURGLASS_ROUNDING))
                .border(1.dp, colors.line, RoundedCornerShape(HOURGLASS_ROUNDING)),
            contentAlignment = Alignment.Center,
        ) {
            Caption("⌛", fontSize = TimaType.sz1)
        }
    }
}

/**
 * Сколько длилась прошлая смена окна — решает, нужны ли часы.
 *
 * Часы стоят кадр: их рисуют, и только следующим кадром строят окно. На быстром телефоне
 * переход и так короткий, и часы бы только мигали — поэтому после быстрого перехода их нет,
 * и окно меняется сразу. До первого перехода длительность не известна — часы есть: первый
 * переход после запуска самый долгий (Redmi, отладочная: 2,4 с).
 */
class WindowSwitchClock(
    private val slowMs: Long = SLOW_SWITCH_MS,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    private var started: TimeMark? = null
    private var lastMs: Long? = null

    /** Нужны ли часы на следующий переход. */
    val needed: Boolean get() = lastMs?.let { it >= slowMs } ?: true

    /** Окно начали строить. */
    fun started() {
        started = time.markNow()
    }

    /** Новое окно нарисовано: сколько это длилось, мс; `null` — переход не засекался. */
    fun finished(): Long? {
        val mark = started ?: return null
        started = null
        return mark.elapsedNow().inWholeMilliseconds.also { lastMs = it }
    }
}

/**
 * Порог «долгого» перехода. Кадр — 16 мс; переход в 100 мс глазом уже заметен как остановка,
 * а часы, которые стоят 120 мс и больше, уже не мигание. Замер Redmi A3: отладочная — 1000–1300,
 * быстрая без компиляции — 150–200, быстрая скомпилированная — 80–105.
 */
const val SLOW_SWITCH_MS: Long = 120

private val HOURGLASS_SIDE = 72.dp
private val HOURGLASS_ROUNDING = 16.dp
