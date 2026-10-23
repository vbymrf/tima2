package io.tima.core.media

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Свой движок ПК вживую (ПЛАН-(ВМ) ВМ5): mp4 по ссылке заказчика открывается, кадры идут, позиция
 * растёт, перемотка работает. Ходит в сеть и нужна Windows — включается `TIMA_LIVE_MEDIA=1`,
 * в обычном прогоне молчит.
 */
class OwnPlayerLiveTest {

    @Test
    fun mp4_по_ссылке_играет_и_перематывается() = runBlocking {
        if (System.getenv("TIMA_LIVE_MEDIA") != "1") return@runBlocking
        val p = WindowsOwnPlayer()
        p.open(URL)
        val started = withTimeoutOrNull(20_000) {
            while (!p.state.value.started && p.state.value.error == null) delay(100)
            true
        }
        assertNull(p.state.value.error, "ошибка движка")
        assertNotNull(started, "за 20 с кадра нет")
        assertNotNull(p.frame.value, "кадр есть")
        val s = p.state.value
        println("размер ${s.width}x${s.height}, длительность ${s.durationMs} мс")
        assertTrue(s.durationMs > 60_000, "длительность ${s.durationMs}")
        delay(2000)
        val before = p.state.value.positionMs
        assertTrue(before > 500, "позиция идёт: $before")
        p.seek(60_000)
        delay(3000)
        val after = p.state.value.positionMs
        println("позиция до перемотки $before, после $after")
        assertTrue(after in 59_000..70_000, "после перемотки $after")
        p.release()
    }

    companion object {
        /** Ссылка заказчика из обсуждения 2026-10-23: mp4 H.264, 199 МБ. */
        const val URL = "https://u-laguna.ru/wp-content/uploads/2026/04/%D0%9C%D0%BE%D0%B1-%D0%94%D0%BE%D1%80%D0%BE%D0%B3%D0%B0-%D0%9E%D0%B1%D0%B7%D0%BE%D1%80-%D0%9F%D0%BB%D1%8F%D0%B6%D0%B0-%D0%93%D0%BE%D1%80%D0%BD%D1%8B%D0%B9-%D0%92%D0%BE%D0%B7%D0%B4%D1%83%D1%85-%D0%92-%D0%A1%D0%BE%D1%87%D0%B8-%D0%9F%D0%BE%D1%81.-%D0%9B%D0%BE%D0%BE-%D0%92%D0%B8%D0%B4%D0%B8%D0%BE-%D0%9E%D1%82-%D0%93%D0%BE%D1%81%D1%82%D0%B8%D0%BD%D0%B8%D1%86%D1%8B-%D0%A3%D1%8E%D1%821.mp4"
    }
}
