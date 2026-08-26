package io.tima.feature.auth

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Сборка номера из кода страны и остатка.
 *
 * Проверяется то, на чём человек спотыкался живьём: «+7» стоял подсказкой, выглядел как
 * уже введённое значение, и номер уходил без кода страны. Теперь код — отдельная
 * величина, а не часть строки, и склейка живёт в одном месте.
 */
class CountryCodeTest {

    @Test
    fun код_и_номер_склеиваются_в_E164() {
        val state = AuthState.Phone(countryCode = "7", number = "9990000001")
        assertEquals("+79990000001", state.fullNumber)
    }

    @Test
    fun плюс_в_значении_не_хранится() {
        // Иначе «+7» и «7» стали бы разными кодами одной страны, а сравнить их было бы
        // нечем: строка есть строка.
        val state = AuthState.Phone(countryCode = "+7", number = "9990000001")
        assertEquals("+79990000001", state.fullNumber)
    }

    @Test
    fun вставленный_целиком_номер_берётся_как_есть() {
        // Так выглядит вставка из буфера. Приписать к нему код страны значит получить
        // «+779990000001» — номер, которого нет.
        val state = AuthState.Phone(countryCode = "7", number = "+79990000001")
        assertEquals("+79990000001", state.fullNumber)
    }

    @Test
    fun разделители_в_номере_отбрасываются() {
        // Люди набирают с пробелами и скобками, сервер ждёт E.164.
        val state = AuthState.Phone(countryCode = "7", number = "999 000-00-01")
        assertEquals("+79990000001", state.fullNumber)
    }

    @Test
    fun другая_страна_работает_так_же() {
        val state = AuthState.Phone(countryCode = "380", number = "501234567")
        assertEquals("+380501234567", state.fullNumber)
    }
}
