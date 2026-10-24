package io.tima.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.time.TimeSource

/**
 * Кнопка, которая вставляет или отправляет, срабатывает **один раз** (заказчик 2026-10-24, «а»).
 *
 * Нажатие закрывает окно и вставляет, но с экрана окно уходит только на следующей отрисовке. Телефон
 * притормозил — нажатия, успевшие до неё, выполнились бы снова: два виджета, два сообщения. Первое нажатие
 * ставит флажок сразу, в обработчике, — остальные до отрисовки ничего не делают. Окно не закрылось
 * (проверка не прошла, ссылки нет) — после отрисовки флажок снят, нажать можно снова.
 *
 * Отброшенные нажатия — одной строкой на серию в [OnePressLog] (заказчик 2026-10-24, 2#): какая кнопка
 * ([tag]), сколько лишних, за сколько миллисекунд.
 */
class OnePress internal constructor(private val tag: String = "") {
    internal var held by mutableStateOf(false)
    private var skipped = 0
    private var first: TimeSource.Monotonic.ValueTimeMark? = null
    private var lastMs = 0L

    /** Выполнить [action], если в этом кадре ещё не нажимали. */
    fun press(action: () -> Unit) {
        if (held) {
            skipped++
            lastMs = first?.elapsedNow()?.inWholeMilliseconds ?: 0
            return
        }
        held = true
        first = TimeSource.Monotonic.markNow()
        action()
    }

    /** Серия кончилась (перерисовка или окно закрылось): лишние — в журнал. */
    internal fun flush() {
        if (skipped > 0) OnePressLog.repeat(tag, skipped, lastMs)
        skipped = 0
        first = null
    }
}

/** Одно нажатие на кадр — для «Готово», «Вставить», «Отправить» окна; [tag] — метка кнопки для журнала. */
@Composable
fun rememberOnePress(tag: String): OnePress {
    val press = remember(tag) { OnePress(tag) }
    // Чтение здесь: после нажатия окно перерисуется, и если оно осталось — флажок снимется.
    val held = press.held
    SideEffect {
        if (held) {
            press.held = false
            press.flush()
        }
    }
    // Окно закрылось по нажатию — серия кончилась вместе с ним.
    DisposableEffect(press) { onDispose { press.flush() } }
    return press
}

/**
 * Куда говорить об отброшенных нажатиях — слушателей ставит приложение: журнал (`TAP-REPEAT`) и шаги
 * черновика редактора. `core-ui` журнала не знает.
 */
object OnePressLog {
    private val listeners = mutableListOf<(tag: String, skipped: Int, spanMs: Long) -> Unit>()

    /** Слушать; вернёт «перестать». */
    fun listen(listener: (tag: String, skipped: Int, spanMs: Long) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    internal fun repeat(tag: String, skipped: Int, spanMs: Long) {
        for (l in listeners.toList()) l(tag, skipped, spanMs)
    }
}
