package io.tima.feature.chat

import io.tima.domain.account.Me
import io.tima.domain.account.NickStep
import io.tima.domain.account.Profile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Свой профиль — ПЛАН-КОНТАКТОВ.md, Д8; правило «ник один раз на личность» — 0050.
 *
 * Проверяется то, что человек увидит на экране, а не вызовы сети: что поля заполнены
 * с сервера, что ник после сохранения закреплён, и что набранное не затирается.
 */
class ProfileStoreTest {

    private class FakeProfile(
        var answer: Me? = Me(phone = "+79990000105", name = "Пётр", nickname = "petr_smirnov"),
        var onNick: NickStep = NickStep.Taken,
    ) : Profile {
        override suspend fun me(): Me? = answer
        override suspend fun setName(name: String) = true
        override suspend fun freeNickname(nick: String): Boolean? = true
        override suspend fun setNickname(nick: String): NickStep = onNick
    }

    @Test
    fun экран_заполняется_с_сервера_а_не_открывается_пустым() = runTest {
        val store = ProfileStore(FakeProfile(), phone = "", scope = this)
        store.refresh()
        testScheduler.advanceUntilIdle()

        val s = store.state.value
        assertEquals("+79990000105", s.phone, "телефон не пришёл — сессия его не хранит, брать больше негде")
        assertEquals("Пётр", s.name)
        assertEquals("petr_smirnov", s.nickname)
        assertEquals("petr_smirnov", s.savedNickname, "иначе свой же ник спросят о занятости")
    }

    @Test
    fun перечитывание_не_затирает_набранное() = runTest {
        val store = ProfileStore(FakeProfile(), phone = "", scope = this)
        store.changedName("Пё")
        store.refresh()
        testScheduler.advanceUntilIdle()

        assertEquals("Пё", store.state.value.name, "человек печатал, а сервер стёр буквы старым именем")
    }

    @Test
    fun заданный_ник_закрепляется_сразу_а_не_после_перезахода() = runTest {
        val store = ProfileStore(FakeProfile(answer = Me(phone = "+7")), phone = "", scope = this)
        store.changedNickname("novyy_nik_0001")
        store.save()
        testScheduler.advanceUntilIdle()

        val s = store.state.value
        assertTrue(s.saved)
        assertTrue(s.nickLocked, "ник задан — второго раза у этой личности нет, экран обязан показать это сразу")
        assertFalse(s.nickEditable)
    }

    @Test
    fun замок_с_сервера_запирает_экран_и_возвращает_прежний_ник() = runTest {
        // Ник задали с другого устройства минуту назад: экран не знал, сервер ответил замком.
        val store = ProfileStore(
            FakeProfile(answer = Me(nickname = "staryy_nik_000", nickLocked = true), onNick = NickStep.Locked),
            phone = "", scope = this, nickname = "staryy_nik_000",
        )
        store.changedNickname("drugoy_nik_000")
        store.save()
        testScheduler.advanceUntilIdle()

        val s = store.state.value
        assertTrue(s.nickLocked)
        assertEquals("staryy_nik_000", s.nickname, "отказ обязан вернуть на экран тот ник, что стоит на сервере")
    }

    @Test
    fun имя_меняется_без_ограничений_и_при_запертом_нике() = runTest {
        val store = ProfileStore(FakeProfile(answer = Me(nickname = "nik_zapert_0", nickLocked = true)), phone = "", scope = this)
        store.refresh()
        testScheduler.advanceUntilIdle()
        store.changedName("Пётр Второй")
        store.save()
        testScheduler.advanceUntilIdle()

        val s = store.state.value
        assertTrue(s.saved, "запертый ник не должен мешать сменить имя")
        assertEquals("Пётр Второй", s.name)
    }
}
