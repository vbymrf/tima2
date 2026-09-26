package io.tima.core.notify

import io.tima.core.diag.LogCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Фон в журнале — ВЗ0в: событийно, чтобы не перегрузить журнал (заказчик 2026-09-26).
 */
class BackgroundWatchTest {

    private val всёХорошо = BackgroundFacts(notices = true, calls = true, awake = true)

    @Test
    fun первая_проверка_пишет_всё_известное() {
        val строки = BackgroundWatch.changes(null, BackgroundFacts(notices = false, calls = null, awake = true))
        assertEquals(listOf(LogCode.BG_NOTICES, LogCode.BG_POWER), строки.map { it.code })
        assertTrue(строки[0].bad, "запрет уведомлений — беда, со знаком")
    }

    @Test
    fun то_же_значение_молчит() {
        // Главное решение: проверка на каждом возврате окна не пишет ничего, если ничего
        // не поменялось.
        assertEquals(0, BackgroundWatch.changes(всёХорошо, всёХорошо).size)
    }

    @Test
    fun пишется_только_изменившееся() {
        val строки = BackgroundWatch.changes(всёХорошо, всёХорошо.copy(awake = false))
        assertEquals(1, строки.size)
        assertEquals(LogCode.BG_POWER, строки[0].code)
        assertTrue(строки[0].bad)
    }

    @Test
    fun неизвестное_не_пишется_и_не_затирает() {
        // ПК: системе нечего сказать — строк нет вовсе.
        assertEquals(0, BackgroundWatch.changes(null, BackgroundFacts()).size)
    }

    @Test
    fun снимок_называет_беду_заглавными() {
        val строка = BackgroundWatch.describe(
            BackgroundFacts(notices = false, calls = true, awake = false),
            serviceFor = 135.minutes,
        )
        assertEquals(
            "уведомления ЗАПРЕЩЕНЫ; канал «Звонки» включён; экономия батареи ОГРАНИЧИВАЕТ; служба канала жива 2 ч 15 мин",
            строка,
        )
    }

    @Test
    fun на_пк_снимок_пуст() {
        assertEquals("", BackgroundWatch.describe(BackgroundFacts(), serviceFor = null))
    }
}
