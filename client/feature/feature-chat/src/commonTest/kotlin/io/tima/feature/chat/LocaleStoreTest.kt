package io.tima.feature.chat

import io.tima.domain.chat.FeedFilter
import io.tima.domain.chat.PersonLocale
import io.tima.domain.chat.PersonLocales
import io.tima.domain.chat.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Страна, язык и отбор выдачи (ПЛАН-ЯЗЫКА Я6, Я7).
 *
 * Магазин собирается на `backgroundScope`, а не на самой проверке: он слушает поток
 * настроек, который не кончается, — и проверка ждала бы его вечно.
 *
 * Проверяется то, ради чего они заведены: пустая настройка означает «показывать всё», а не
 * «не показывать ничего», и выключенный переключатель убирает отбор, а не подменяет его.
 */
class LocaleStoreTest {

    private class FakeLocales(var answer: PersonLocale? = PersonLocale("ru", "RU")) : PersonLocales {
        var written: PersonLocale? = null
        override suspend fun read(): PersonLocale? = answer
        override suspend fun write(locale: PersonLocale): Boolean {
            written = locale
            return true
        }
    }

    private class FakeSettings : Settings {
        val values = MutableStateFlow(mapOf<String, String>())
        override fun all(): Flow<Map<String, String>> = values
        override suspend fun put(name: String, value: String) {
            values.value = values.value + (name to value)
        }
    }

    @Test
    fun страна_читается_и_записывается() = runTest {
        val locales = FakeLocales()
        val store = LocaleStore(locales, FakeSettings(), backgroundScope)
        store.refresh()
        runCurrent()
        assertEquals("RU", store.state.value.locale.country)

        store.country("es")
        runCurrent()
        // Код страны приводится к верхнему регистру: «es» и «ES» — одна страна, и две
        // записи о ней означали бы, что отбор промахивается через раз.
        assertEquals("ES", locales.written?.country)
    }

    @Test
    fun пустая_настройка_показывает_всё_а_не_ничего() {
        val locale = PersonLocale(lang = "ru", country = "")
        val filter = FeedFilter(onlyMyCountry = true, onlyMyLanguages = true)

        // Страна не указана — отбора по стране нет: молчание не должно закрывать
        // человеку всё сразу.
        assertEquals("", filter.countryFor(locale))
        // Языки не выбраны — берётся язык человека: это и есть ответ по умолчанию.
        assertEquals(listOf("ru"), filter.languagesFor(locale))
    }

    @Test
    fun выключенный_переключатель_убирает_отбор() {
        val locale = PersonLocale(lang = "ru", country = "RU")
        val off = FeedFilter(onlyMyCountry = false, onlyMyLanguages = false)

        assertEquals("", off.countryFor(locale), "выключено — отбора по стране нет")
        assertTrue(off.languagesFor(locale).isEmpty(), "выключено — отбора по языку нет")

        // Выключен один — работает второй: переключатели независимы.
        val onlyCountry = FeedFilter(onlyMyCountry = true, onlyMyLanguages = false)
        assertEquals("RU", onlyCountry.countryFor(locale))
        assertTrue(onlyCountry.languagesFor(locale).isEmpty())
    }

    @Test
    fun переключатели_живут_в_настройках_и_приходят_потоком() = runTest {
        val settings = FakeSettings()
        val store = LocaleStore(FakeLocales(), settings, backgroundScope)
        runCurrent()
        assertTrue(store.state.value.filter.onlyMyCountry, "по умолчанию включено")

        store.onlyMyCountry(false)
        runCurrent()
        assertEquals(false, store.state.value.filter.onlyMyCountry)
        assertEquals("no", settings.values.value["feed.onlyCountry"])
    }
}
