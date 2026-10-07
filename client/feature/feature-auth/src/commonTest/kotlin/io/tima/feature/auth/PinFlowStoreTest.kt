package io.tima.feature.auth

import io.tima.domain.account.PinCheck
import io.tima.domain.account.PinPhraseVerdict
import io.tima.domain.account.PinPort
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Экран пин-кода (ПЛАН-(ПН)): задать, сменить, убрать, забыли, разблокировать. */
class PinFlowStoreTest {

    /** Пин в памяти: проверка, ошибки и «только фраза» — как у настоящего, без хеша. */
    private class Pin(var value: String? = null) : PinPort {
        var fails = 0
        var blocked: PinCheck = PinCheck.Ok
        override fun isOn() = value != null
        override fun set(pin: String) { value = pin; fails = 0 }
        override fun remove() { value = null }
        override fun check(pin: String): PinCheck {
            if (blocked != PinCheck.Ok) return blocked
            if (pin == value) return PinCheck.Ok
            fails++
            return PinCheck.Wrong(5 - fails)
        }
        override fun state(): PinCheck = blocked
        override fun phraseAccepted() { fails = 0; blocked = PinCheck.Ok }
    }

    private fun PinFlowStore.type(pin: String) = pin.forEach { digit(it) }

    @Test
    fun включить_придумать_и_повторить() = runTest {
        val pin = Pin()
        val store = PinFlowStore(PinMode.Enable, pin, { PinPhraseVerdict.Ok }, backgroundScope, { 0L })
        assertEquals(PinStep.Create, store.state.value.step)
        store.type("1234")
        assertEquals(PinStep.Repeat, store.state.value.step)
        store.type("4321")
        assertEquals(PinMessage.Mismatch, store.state.value.message)
        assertEquals(PinStep.Create, store.state.value.step, "не совпало — заново с первого шага")
        store.type("1234")
        store.type("1234")
        assertEquals(PinStep.Done, store.state.value.step)
        assertEquals(PinResult.TurnedOn, store.state.value.result)
        assertEquals("1234", pin.value)
    }

    @Test
    fun сменить_нужен_нынешний_неверный_считается() = runTest {
        val pin = Pin("1111")
        val store = PinFlowStore(PinMode.Change, pin, { PinPhraseVerdict.Ok }, backgroundScope, { 0L })
        store.type("0000")
        assertEquals(PinMessage.Wrong(4), store.state.value.message)
        assertEquals(0, store.state.value.typed, "набранное стёрто")
        store.type("1111")
        assertEquals(PinStep.Create, store.state.value.step)
        store.type("2222")
        store.type("2222")
        assertEquals(PinResult.Changed, store.state.value.result)
        assertEquals("2222", pin.value)
    }

    @Test
    fun пауза_не_даёт_вводить() = runTest {
        val pin = Pin("1111").apply { blocked = PinCheck.Paused(10_000) }
        val store = PinFlowStore(PinMode.Unlock, pin, { PinPhraseVerdict.Ok }, backgroundScope, { 5_000L })
        assertEquals(10_000L, store.state.value.pausedUntil)
        store.type("1111")
        assertEquals(0, store.state.value.typed, "во время паузы цифры не принимаются")
    }

    @Test
    fun забыли_без_сети_пин_прежний_с_сетью_можно_убрать() = runTest {
        val pin = Pin("1111")
        var verdict: PinPhraseVerdict = PinPhraseVerdict.Offline
        val store = PinFlowStore(PinMode.Forgot, pin, { verdict }, backgroundScope, { 0L })
        store.submitPhrase("двенадцать слов")
        runCurrent()
        assertEquals(PinMessage.Offline, store.state.value.message)
        assertTrue(pin.isOn(), "без сети пин остался прежним")
        verdict = PinPhraseVerdict.Ok
        store.submitPhrase("двенадцать слов")
        runCurrent()
        assertEquals(PinStep.Choice, store.state.value.step)
        store.choose(newPin = false)
        assertEquals(PinResult.TurnedOff, store.state.value.result)
        assertFalse(pin.isOn())
    }

    @Test
    fun замок_после_десяти_ошибок_только_фраза() = runTest {
        val pin = Pin("1111").apply { blocked = PinCheck.PhraseOnly }
        val store = PinFlowStore(PinMode.Unlock, pin, { PinPhraseVerdict.Ok }, backgroundScope, { 0L })
        assertEquals(PinStep.Phrase, store.state.value.step)
        assertTrue(store.state.value.phraseOnly)
        store.submitPhrase("двенадцать слов")
        runCurrent()
        assertEquals(PinStep.Choice, store.state.value.step)
        store.choose(newPin = true)
        store.type("5555")
        store.type("5555")
        assertEquals(PinStep.Done, store.state.value.step)
        assertEquals("5555", pin.value)
    }

    @Test
    fun разблокировать_верным_пином() = runTest {
        val store = PinFlowStore(PinMode.Unlock, Pin("2468"), { PinPhraseVerdict.Ok }, backgroundScope, { 0L })
        store.type("2468")
        assertIs<PinResult>(store.state.value.result)
        assertEquals(PinResult.Unlocked, store.state.value.result)
    }
}
