package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Видео собеседника нет — только настоящее «нет» (заказчик 2026-09-29, Honor и Redmi). */
class RemoteVideoWatchTest {

    private fun poll(bytes: Long?, frames: Long? = bytes, published: Boolean = true, excused: Boolean = false, codec: String? = "VP8") =
        RemoteVideoWatch.Poll(published, excused, bytes, frames, codec)

    @Test
    fun дорожки_нет_два_опроса_подряд_значит_не_приходит() {
        // Honor объявил H.264 и послал VP8 — сервер дорожку выбросил, до Redmi не дошло ничего.
        val watch = RemoteVideoWatch()
        assertNull(watch.next(poll(null)), "один пустой опрос — ещё не беда")
        assertEquals(RemoteVideoLoss.NotArriving, watch.next(poll(null)))
    }

    @Test
    fun байты_идут_кадров_нет_значит_не_раскодируется() {
        val watch = RemoteVideoWatch()
        watch.next(poll(1000, 10))
        assertNull(watch.next(poll(5000, 10)))
        assertEquals(RemoteVideoLoss.NotDecoding("VP8"), watch.next(poll(9000, 10)))
    }

    @Test
    fun замерло_после_первого_кадра_значит_не_приходит() {
        // Запасной VP8: картинка появилась и замерла — байты перестали идти.
        val watch = RemoteVideoWatch()
        watch.next(poll(1000, 10))
        assertNull(watch.next(poll(1000, 10)))
        assertEquals(RemoteVideoLoss.NotArriving, watch.next(poll(1000, 10)))
    }

    @Test
    fun кадры_идут_событий_нет_даже_после_смены_кодека() {
        val watch = RemoteVideoWatch()
        watch.next(poll(1000, 10, codec = "H264"))
        assertNull(watch.next(poll(5000, 50, codec = "H264")))
        assertNull(watch.next(poll(9000, 90, codec = "VP8")), "перешли на запасной — видео есть, беды нет")
    }

    @Test
    fun скрытое_нами_или_погашенное_сервером_не_беда_этого_события() {
        val watch = RemoteVideoWatch()
        repeat(3) { assertNull(watch.next(poll(null, excused = true))) }
        repeat(3) { assertNull(watch.next(poll(null, published = false))) }
    }

    @Test
    fun вернулось_видео_беда_снята() {
        val watch = RemoteVideoWatch()
        watch.next(poll(null))
        assertEquals(RemoteVideoLoss.NotArriving, watch.next(poll(null)))
        watch.next(poll(1000, 10))
        assertNull(watch.next(poll(5000, 50)))
    }
}
