package io.tima.app

import io.tima.app.net.LinkState
import io.tima.app.net.classifyFailure
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Признаки взяты из настоящих журналов испытаний в мобильной сети, а не придуманы.
 * Строки в тестах — дословно то, что писало приложение на телефоне.
 */
class LinkStateTest {

    @Test
    fun `имя не разобралось — сети нет`() {
        assertEquals(
            LinkState.NO_NETWORK,
            classifyFailure(UnknownHostException("Unable to resolve host \"api.xn--80aa4ar0b.xn--p1ai\": No address associated with hostname")),
        )
    }

    @Test
    fun `соединение встало, а ответа нет — не пускают`() {
        // Ровно это видели на 4G: TCP за 57 мс, дальше тишина до самого срока.
        assertEquals(LinkState.BLOCKED, classifyFailure(SocketTimeoutException("Read timed out")))
        assertEquals(LinkState.BLOCKED, classifyFailure(IOException("Socket timeout has expired")))
    }

    @Test
    fun `сеть моргнула — это не стена`() {
        // Смена Wi-Fi на мобильную даёт именно такую ошибку. Ждать здесь две минуты
        // нельзя: связь вернётся через секунду.
        assertEquals(LinkState.NO_NETWORK, classifyFailure(IOException("Software caused connection abort")))
        assertEquals(LinkState.NO_NETWORK, classifyFailure(java.io.EOFException()))
    }

    @Test
    fun `причина находится и во вложенном исключении`() {
        val wrapped = IOException("не удалось подключиться", UnknownHostException("No address associated with hostname"))
        assertEquals(LinkState.NO_NETWORK, classifyFailure(wrapped))
    }

    @Test
    fun `в стене ждём долго, при мигании сети — коротко`() {
        // Смысл разделения именно в этом: раз в 15 секунд часами — севшая батарея.
        assertTrue(
            LinkState.BLOCKED.retryDelayMs > LinkState.NO_NETWORK.retryDelayMs * 10,
            "пауза в состоянии «не пускают» должна быть заметно длиннее",
        )
    }

    @Test
    fun `у каждого нерабочего состояния есть что сказать человеку`() {
        assertEquals("", LinkState.ONLINE.message, "когда всё работает, показывать нечего")
        for (s in listOf(LinkState.NO_NETWORK, LinkState.BLOCKED)) {
            assertTrue(s.message.isNotBlank(), "$s молчит, а человек не понимает, что происходит")
            // Причину не называем: сегодня одна, завтра другая (ADR-0016 §4).
            assertTrue(
                listOf("ограничен", "оператор", "блокир").none { it in s.message.lowercase() },
                "в тексте не должно быть догадок о причине: «${s.message}»",
            )
        }
    }
}
