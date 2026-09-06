package io.tima.shared

import io.tima.core.diag.Diary
import io.tima.core.diag.Journal
import io.tima.core.network.DeviceTokenApi
import io.tima.core.network.RouteConfig
import io.tima.core.network.ServerRoute
import io.tima.core.network.timaHttpClient
import io.tima.domain.account.Session
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Правило состава журнала, четвёртый вопрос: **в каком состоянии был вход**
 * (ПЛАН-ОТЛАДКИ.md, «Что попадает в журнал»).
 *
 * Именно этого не хватило 2026-09-06: в отчёте с телефона `401` был виден, а причина —
 * нет, и разбирать пришлось по базе сервера. Теперь состояние входа пишется словами, и
 * проверяется это здесь.
 *
 * **В сеть тест не ходит.** Подписант возвращает `null` — «подписывать нечем», — и
 * обновление обрывается до первого запроса. Проверяется ровно то, что нужно: какая строка
 * попала в журнал ДО попытки.
 */
class JournalStateTest {

    private val now = 1_757_000_000_000L

    private fun journalOf(block: suspend () -> Unit): String {
        val diary = Diary(now = { now })
        Journal.replace(diary)
        kotlinx.coroutines.runBlocking { block() }
        return diary.dump()
    }

    /** Токен с известным сроком. Подпись здесь любая: клиент её не проверяет. */
    private fun token(expiresAtMillis: Long): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val payload = """{"sub":"u-1","exp":${expiresAtMillis / 1000}}""".encodeToByteArray()
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in payload) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                out.append(alphabet[(buffer shr bits) and 0x3F])
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (6 - bits)) and 0x3F])
        return "header.$out.signature"
    }

    private fun tokens(access: String) = DeviceTokens(
        api = DeviceTokenApi(ServerRoute.from(RouteConfig(host = "example.invalid")), timaHttpClient()),
        session = Session("u-1", "d-1", access),
        sign = { null },
        now = { now },
        remember = {},
    )

    @Test
    fun истёкший_токен_объяснён_словами() = runTest {
        // «exp=1757169600» читающему отчёт не говорит ничего. «Токен истёк 2 ч 0 мин
        // назад» — говорит всё.
        val expired = token(now - 2 * 60 * 60 * 1000)

        val log = journalOf { tokens(expired).renewIfStale() }

        assertContains(log, "токен истёк")
        assertContains(log, "2 ч")
        // Самого токена в журнале быть не должно: это ключ доступа.
        assertFalse(log.contains(expired.substringAfter('.').substringBefore('.')))
    }

    @Test
    fun живой_токен_тоже_назван() = runTest {
        // Строка про живой токен нужна не меньше: она отвечает «вход был в порядке», и
        // без неё пришлось бы гадать, дошли ли мы вообще до проверки.
        val log = journalOf { tokens(token(now + 20 * 60 * 60 * 1000)).renewIfStale() }

        assertContains(log, "токен жив")
    }

    @Test
    fun отсутствие_токена_не_путается_с_истечением() {
        // «Токена нет» и «токен истёк» требуют разного: первое — войти, второе —
        // обновить. Одинаковая строка на оба случая обесценила бы обе.
        val log = journalOf { tokens("").renewIfStale() }

        assertContains(log, "токена нет")
        assertTrue(log.contains("не вошло"))
    }
}
