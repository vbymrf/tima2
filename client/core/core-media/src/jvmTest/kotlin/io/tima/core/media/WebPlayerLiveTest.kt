package io.tima.core.media

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Frame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Браузерный движок ПК вживую (ПЛАН-(ВМ) ВМ6): WebView2 по COM поднимается в окне, страница с
 * `<video>` и с проигрывателем YouTube отдают время через мост, перемотка доходит. Нужны Windows,
 * WebView2 Runtime и сеть — включается `TIMA_LIVE_MEDIA=1`; на экране на время проверки окно.
 */
class WebPlayerLiveTest {

    private fun frame(): Frame {
        var f: Frame? = null
        SwingUtilities.invokeAndWait {
            f = Frame("TIMA WebView2").apply { setSize(800, 480); isVisible = true }
        }
        return f!!
    }

    private suspend fun runPage(page: WebPage, seekTo: Double): Pair<Long, Long> {
        val f = frame()
        val p = WindowsWebPlayer()
        p.attach(f)
        val insets = f.insets
        p.bounds(insets.left, insets.top, 780, 440)
        p.open(page)
        val started = withTimeoutOrNull(30_000) {
            while (!p.state.value.started && p.state.value.error == null) delay(200)
            true
        }
        assertNull(p.state.value.error, "ошибка страницы: ${p.state.value.error}")
        assertTrue(started == true, "за 30 с время не пошло: ${p.state.value}")
        val before = p.state.value.positionMs
        p.seek((seekTo * 1000).toLong())
        delay(4000)
        val after = p.state.value.positionMs
        println("${page.kind}: до перемотки $before мс, после $after мс, длительность ${p.state.value.durationMs}")
        p.release()
        SwingUtilities.invokeAndWait { f.dispose() }
        return before to after
    }

    @Test
    fun файл_и_youtube_в_webview2() = runBlocking {
        if (System.getenv("TIMA_LIVE_MEDIA") != "1") return@runBlocking
        val (_, fileAfter) = runPage(WebPages.file(OwnPlayerLiveTest.URL, 0), 60.0)
        assertTrue(fileAfter in 59_000..70_000, "файл: после перемотки $fileAfter")
        val (_, ytAfter) = runPage(WebPages.youtube("https://www.youtube.com/watch?v=aqz-KE-bpKQ", "aqz-KE-bpKQ", 0), 120.0)
        assertTrue(ytAfter in 119_000..130_000, "YouTube: после перемотки $ytAfter")
    }

    @Test
    fun rutube_и_vk_в_webview2() = runBlocking {
        if (System.getenv("TIMA_LIVE_MEDIA") != "1") return@runBlocking
        val (_, rt) = runPage(WebPages.rutube("https://rutube.ru/video/9d606361c5c4d6c7491ac2b6104c6031/", "9d606361c5c4d6c7491ac2b6104c6031", 0), 120.0)
        assertTrue(rt in 119_000..130_000, "Rutube: после перемотки $rt")
        val (_, vk) = runPage(WebPages.vk("https://vkvideo.ru/video-65886767_456239913", "-65886767", "456239913", "", 0), 120.0)
        assertTrue(vk in 119_000..130_000, "VK: после перемотки $vk")
    }
}
