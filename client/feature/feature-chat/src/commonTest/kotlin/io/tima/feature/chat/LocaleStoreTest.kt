package io.tima.feature.chat

import io.tima.core.words.RussianWords
import io.tima.core.words.Words
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
 *
 * С Я12 сюда добавился язык, на котором человек **пишет**. До него этот тег не
 * устанавливал никто: `language()` не вызывался ниоткуда, `KEY_LANGUAGES` читался без
 * сеттера, и отбор лент работал от умолчания сервера.
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
    fun язык_письма_записывается_строчными() = runTest {
        val locales = FakeLocales()
        val store = LocaleStore(locales, FakeSettings(), backgroundScope)
        store.refresh()
        runCurrent()

        store.writingLanguage("  DE  ")
        runCurrent()
        // Зеркально стране, которая приводится к верхнему: «DE» и «de» один язык, и две
        // записи о нём дали бы отбор, промахивающийся через раз.
        assertEquals("de", locales.written?.lang)
        assertEquals("de", store.state.value.locale.lang, "показанное обязано совпасть с записанным")
    }

    @Test
    fun пустой_язык_письма_не_пишется() = runTest {
        val locales = FakeLocales()
        val store = LocaleStore(locales, FakeSettings(), backgroundScope)
        store.refresh()
        runCurrent()

        store.writingLanguage("   ")
        runCurrent()
        // «Не указан» настраивают не стиранием поля: пустой тег уехал бы на сервер и
        // выключил человеку языковые ленты молча.
        assertEquals(null, locales.written, "пустой тег записан на сервер")
    }

    @Test
    fun язык_письма_которого_нет_берётся_у_приложения() = runTest {
        val locales = FakeLocales(answer = PersonLocale(lang = "", country = "ES"))
        val store = LocaleStore(locales, FakeSettings(), backgroundScope, words = { Spanish })
        store.refresh()
        runCurrent()

        // Догадка сразу записывается: показанное на экране обязано совпадать с тем, по
        // чему сервер отбирает ленты.
        assertEquals("es", locales.written?.lang, "язык приложения не стал догадкой")
        assertEquals("es", store.state.value.locale.lang)
    }

    @Test
    fun какие_языки_читать_кладутся_как_набраны_а_отбор_их_разбирает() = runTest {
        val settings = FakeSettings()
        val store = LocaleStore(FakeLocales(), settings, backgroundScope)
        runCurrent()

        store.readingLanguages("ru, EN , ,es")
        runCurrent()

        // В настройках — как набрано: разбор при записи отнял бы у человека запятую
        // из-под курсора на середине слова.
        assertEquals("ru, EN , ,es", settings.values.value["feed.languages"])
        assertEquals("ru, EN , ,es", store.state.value.languagesText)
        // Отбору достаётся разобранное: пробелы убраны, регистр опущен, пустые выброшены.
        assertEquals(listOf("ru", "en", "es"), store.state.value.filter.languages)
    }

    /** Словарь с другим тегом: язык приложения, отличный от русского. */
    private object Spanish : Words by RussianWords {
        override val tag = "es"
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
