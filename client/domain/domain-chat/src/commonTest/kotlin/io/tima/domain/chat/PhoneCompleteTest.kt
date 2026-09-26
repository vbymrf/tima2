package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Номер набран целиком — только тогда сверка идёт сама (заказчик 2026-09-26): каждая
 * цифра недонабранного номера была запросом к серверу про несуществующего человека.
 */
class PhoneCompleteTest {

    @Test
    fun российский_полон_на_десяти_цифрах_после_кода() {
        assertEquals(true, phoneComplete("+79160001122"))
        assertEquals(false, phoneComplete("+7916000112"))
        assertEquals(false, phoneComplete("+791600011"))
    }

    @Test
    fun недонабранный_уже_номер_для_нормализации_но_ещё_не_полон() {
        // Ровно тот случай, из-за которого запросы шли с восьмой цифры.
        val partial = normalizePhone("+7 916 000 1")
        assertEquals("+79160001", partial)
        assertEquals(false, phoneComplete(partial!!))
    }

    @Test
    fun самый_длинный_код_побеждает() {
        // «380…» — Украина, девять цифр после кода; не «3» и не что-то ещё.
        assertEquals(true, phoneComplete("+380501234567"))
        assertEquals(false, phoneComplete("+38050123456"))
    }

    @Test
    fun страна_с_плавающей_длиной_не_решается_по_длине() {
        // Германия: длина номера разная — ждём ухода из поля.
        assertNull(phoneComplete("+4930123456"))
    }
}
