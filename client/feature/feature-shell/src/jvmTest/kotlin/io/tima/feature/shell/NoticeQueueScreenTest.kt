package io.tima.feature.shell

import io.tima.core.ui.ButtonKind
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Экран очереди событий — ПЛАН-(СБ)-СОБЫТИЙ С3, решения заказчика 2026-09-27.
 *
 * Одно событие — прежний экран; несколько — открытое, «Следующее», «Пропустить все» и
 * список «Ещё». Снимки кладутся в `build/снимки`: их смотрят глазами, а проверка здесь —
 * что это действительно два разных экрана, а не один и тот же.
 */
class NoticeQueueScreenTest {

    private val обновление = NoticeEntry(
        notice = Notice(
            title = "Вышло важное обновление",
            text = "Доступна версия 2.0.90.",
            details = listOf("Прошлая установка не завершилась: хотели 2.0.88, осталась 2.0.87."),
        ),
        actions = listOf(
            NoticeAction("Перейти к обновлению") {},
            NoticeAction("Позже", ButtonKind.Quiet) {},
        ),
    )

    @Test
    fun одно_событие_и_очередь_из_трёх_различаются() {
        val одно = capture("события-одно", 380, 800, dark = false) {
            NoticeQueueScreen(обновление, position = 1, total = 1, rest = emptyList(), onNext = null, onSkipAll = {})
        }
        val три = capture("события-три", 380, 800, dark = false) {
            NoticeQueueScreen(
                обновление,
                position = 1,
                total = 3,
                rest = listOf(
                    NoticeLine("Канал «Звонки» выключен", fresh = false) {},
                    NoticeLine("Экономия батареи ограничивает TIMA", fresh = true) {},
                ),
                onNext = {},
                onSkipAll = {},
            )
        }
        capture("события-три", 380, 800, dark = true) {
            NoticeQueueScreen(
                обновление,
                position = 1,
                total = 3,
                rest = listOf(NoticeLine("Канал «Звонки» выключен", fresh = false) {}, NoticeLine("Экономия батареи ограничивает TIMA", fresh = true) {}),
                onNext = {},
                onSkipAll = {},
            )
        }
        assertTrue(одно.difference(три) > 0.01, "очередь из трёх выглядит как одно событие — списка и кнопок очереди нет")
    }
}
