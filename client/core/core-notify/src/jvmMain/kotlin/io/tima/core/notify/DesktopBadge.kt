package io.tima.core.notify

/**
 * Число на значке ПК — сумма чисел вкладок (ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ4, решение
 * заказчика 2026-09-30, 1а): и на значке в трее, и на значке в панели задач.
 *
 * Трей и окно заводит приложение (`app-desktop`), а число считает общий код — здесь
 * встреча: общий код ставит число, приложение подписывается и рисует. Подписчика ещё нет —
 * число запоминается и отдаётся ему при подписке.
 */
object DesktopBadge {

    @Volatile
    private var listener: ((Int) -> Unit)? = null

    @Volatile
    var total: Int = 0
        private set

    fun set(value: Int) {
        if (value == total) return
        total = value
        listener?.invoke(value)
    }

    /** Рисовать число: трей и панель задач. Зовётся сразу с нынешним числом. */
    fun listen(draw: (Int) -> Unit) {
        listener = draw
        draw(total)
    }
}
