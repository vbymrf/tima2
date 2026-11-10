package io.tima.shared

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Поле ввода и клавиатура в журнале (`KEYBOARD`, заказчик 2026-11-10). */
class KeyboardLogTest {

    private val notes = mutableListOf<String>()
    private val troubles = mutableListOf<String>()

    private fun TestScope.log() = KeyboardLog(
        backgroundScope,
        clock = { testScheduler.currentTime },
        note = { text, details -> notes += text + " " + details.joinToString { "${it.first}=${it.second}" } },
        trouble = { text, _ -> troubles += text },
    )

    @Test
    fun клавиатура_пришла_вовремя_тревоги_нет() = runTest {
        val log = log()
        log.asked()
        advanceTimeBy(300)
        log.shown(280)
        advanceTimeBy(5_000)
        assertEquals(emptyList(), troubles)
        assertTrue(notes.any { it.startsWith("клавиатура показана") && "мс после просьбы=300" in it }, notes.toString())
    }

    @Test
    fun поле_просит_а_клавиатуры_нет() = runTest {
        // Случай Honor: поле в фокусе, клавиатура не выехала — строка с «!» через 1,5 с.
        val log = log()
        log.asked()
        advanceTimeBy(KeyboardLog.LATE_MS + 100)
        assertEquals(listOf("поле ввода в фокусе, клавиатуры нет"), troubles)
    }

    @Test
    fun отпустили_раньше_срока_тревоги_нет() = runTest {
        val log = log()
        log.asked()
        advanceTimeBy(500)
        log.released()
        advanceTimeBy(5_000)
        assertEquals(emptyList(), troubles)
    }

    @Test
    fun скрыта_пишется_один_раз_и_только_после_показа() = runTest {
        val log = log()
        log.hidden()
        log.shown(280)
        log.hidden()
        log.hidden()
        assertEquals(1, notes.count { it.startsWith("клавиатура скрыта") })
    }
}
