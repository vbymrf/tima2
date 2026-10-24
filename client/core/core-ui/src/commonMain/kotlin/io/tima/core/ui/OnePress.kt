package io.tima.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Кнопка, которая вставляет или отправляет, срабатывает **один раз** (заказчик 2026-10-24, «а»).
 *
 * Нажатие закрывает окно и вставляет, но с экрана окно уходит только на следующей отрисовке. Телефон
 * притормозил — нажатия, успевшие до неё, выполнились бы снова: два виджета, два сообщения. Первое нажатие
 * ставит флажок сразу, в обработчике, — остальные до отрисовки ничего не делают. Окно не закрылось
 * (проверка не прошла, ссылки нет) — после отрисовки флажок снят, нажать можно снова.
 */
class OnePress internal constructor() {
    internal var held by mutableStateOf(false)

    /** Выполнить [action], если в этом кадре ещё не нажимали. */
    fun press(action: () -> Unit) {
        if (held) return
        held = true
        action()
    }
}

/** Одно нажатие на кадр — для «Готово», «Вставить», «Отправить» окна. */
@Composable
fun rememberOnePress(): OnePress {
    val press = remember { OnePress() }
    // Чтение здесь: после нажатия окно перерисуется, и если оно осталось — флажок снимется.
    val held = press.held
    SideEffect { if (held) press.held = false }
    return press
}
