package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Инвентарь поведения, пункт 1: таблица «текст ошибки → состояние» перенесена из v1
 * и закреплена тестом. Все тринадцать признаков — из журналов настоящих испытаний в
 * мобильной сети, поэтому тест проверяет каждый, а не «пару для примера».
 *
 * Тест исполняется и на iOS: типы исключений там свои, а разбор идёт по тексту, и
 * ровно поэтому он должен вести себя одинаково на всех платформах.
 */
class LinkStateTest {

    private class Ошибка(override val message: String?) : Throwable(message)

    private fun поводы(): List<Pair<String, LinkState>> = listOf(
        // Имя не разбирается — сети нет вовсе
        "UnknownHostException: api.example.org" to LinkState.NO_NETWORK,
        "Unable to resolve host \"api\"" to LinkState.NO_NETWORK,
        "no address associated with hostname" to LinkState.NO_NETWORK,
        "nodename nor servname provided" to LinkState.NO_NETWORK,
        // Соединение не начали устанавливать
        "Network is unreachable" to LinkState.NO_NETWORK,
        "ECONNREFUSED" to LinkState.NO_NETWORK,
        "Connection refused" to LinkState.NO_NETWORK,
        "No route to host" to LinkState.NO_NETWORK,
        // Встало и молчит — то самое, ради чего состояний три
        "SocketTimeoutException: timeout" to LinkState.BLOCKED,
        "Read timed out" to LinkState.BLOCKED,
        // Оборвали снаружи
        "Software caused connection abort" to LinkState.NO_NETWORK,
        "Connection reset by peer" to LinkState.NO_NETWORK,
        "EOFException" to LinkState.NO_NETWORK,
        "Broken pipe" to LinkState.NO_NETWORK,
    )

    @Test
    fun каждый_признак_из_журналов_опознаётся() {
        for ((текст, ожидалось) in поводы()) {
            assertEquals(
                ожидалось,
                classifyFailure(Ошибка(текст)),
                "признак «$текст» опознан неверно",
            )
        }
    }

    @Test
    fun признак_ищется_и_в_причине_а_не_только_в_верхнем_исключении() {
        // Ktor и OkHttp оборачивают исходную ошибку, поэтому важен весь путь причин.
        val обёрнуто = Throwable("не удалось выполнить запрос", Ошибка("Read timed out"))
        assertEquals(LinkState.BLOCKED, classifyFailure(обёрнуто))
    }

    @Test
    fun незнакомая_ошибка_не_считается_блокировкой() {
        // Осторожность в правильную сторону: незнакомое — это «нет сети» с быстрым
        // повтором, а не «стена» с паузой на две минуты. Ошибиться в сторону
        // двухминутного молчания дороже.
        assertEquals(LinkState.NO_NETWORK, classifyFailure(Ошибка("что-то новое")))
        assertEquals(LinkState.NO_NETWORK, classifyFailure(null))
    }

    @Test
    fun сроки_повтора_различают_мигание_и_стену() {
        assertTrue(LinkState.NO_NETWORK.retryDelayMs < LinkState.BLOCKED.retryDelayMs)
        assertEquals(120_000, LinkState.BLOCKED.retryDelayMs)
    }
}
