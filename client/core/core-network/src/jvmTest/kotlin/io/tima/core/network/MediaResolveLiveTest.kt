package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Поток сайтов для своего движка вживую (ПЛАН-(ВМ) ВМ8): Rutube — по их запросу, VK — со страницы
 * встраивания; оба отдают список HLS. Ходит в сеть — включается `TIMA_LIVE_MEDIA=1`.
 */
class MediaResolveLiveTest {
    @Test
    fun rutube_и_vk_отдают_hls() = runBlocking {
        if (System.getenv("TIMA_LIVE_MEDIA") != "1") return@runBlocking
        val r = MediaResolveOverHttp()
        val http = HttpClient(httpEngine())
        for ((name, url) in listOf(
            "rutube" to r.rutube("9d606361c5c4d6c7491ac2b6104c6031"),
            "vk" to r.vk("-65886767", "456239913", ""),
        )) {
            assertNotNull(url, "$name: потока нет")
            val list = http.get(url) { header("User-Agent", io.tima.core.media.MEDIA_USER_AGENT) }.bodyAsText()
            println("$name: ${url.take(60)}… — ${list.lines().first()}, строк ${list.lines().size}")
            assertTrue(list.startsWith("#EXTM3U"), "$name: не список HLS")
        }
        http.close()
    }
}
