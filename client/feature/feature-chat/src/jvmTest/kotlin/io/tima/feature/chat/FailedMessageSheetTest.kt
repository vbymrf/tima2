package io.tima.feature.chat

import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Подокно неотправленного в двух видах (заказчик 2026-09-19): у отказанного есть «Отправить
 * ещё раз», у ждущего его нет — очередь повторяет сама. Картинки для глаз лежат в
 * `build/снимки`.
 */
class FailedMessageSheetTest {

    @Test
    fun у_ждущего_повтора_нет_а_у_отказанного_есть() {
        val waiting = capture("подокно-ждёт", 360, 640, dark = false) {
            FailedMessageSheet(
                text = "Привет! Собираемся в семь",
                reason = "нет связи с сервером",
                onDelete = {},
                onReport = {},
                onClose = {},
                waiting = true,
                attempts = 3,
                secondsLeft = 120,
            )
        }
        val failed = capture("подокно-не-ушло", 360, 640, dark = false) {
            FailedMessageSheet(
                text = "Привет! Собираемся в семь",
                reason = "level_in_private",
                onDelete = {},
                onReport = {},
                onClose = {},
                onRetry = {},
            )
        }
        assertTrue(waiting.difference(failed) > 0.0, "два вида подокна нарисованы одинаково")
    }
}
