package io.tima.feature.chat

import io.tima.core.ui.CurrentWords
import io.tima.core.ui.Words
import io.tima.core.ui.RussianWords
import io.tima.core.ui.ChatWords
import io.tima.domain.chat.FeedFilter
import io.tima.domain.chat.PersonLocale
import io.tima.domain.chat.PersonLocales
import io.tima.domain.chat.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Страна, язык и отбор выдачи (ПЛАН-ЯЗЫКА Я6, Я7).
 *
 * **Страна и язык живут на сервере, переключатели — на устройстве.** Разделение не
 * случайное: страной сервер штампует запись при публикации, а переключатели он видит
 * только как параметры запроса — они про то, что человек хочет видеть, и хранить их у
 * сервера незачем.
 *
 * **Лента друзей не фильтруется никогда** (решение заказчика 2026-09-08). Здесь это
 * выражено тем, что настройка не касается переписки вовсе: её читают только каталог,
 * поиск и общая лента.
 */
class LocaleStore(
    private val locales: PersonLocales,
    private val settings: Settings,
    private val scope: CoroutineScope,
    /**
     * Словарь надписей — **ссылкой, а не значением** (ПЛАН-ЯЗЫКА, Я2-беды).
     *
     * Store не `@Composable`, и `Tima.words` ему недоступен. Лямбда зовётся в момент
     * беды, поэтому язык всегда текущий: переданный значением, он запомнился бы на всю
     * жизнь store, и после смены языка беда пришла бы на прежнем.
     */
    private val words: () -> Words = { CurrentWords.value },
) {

    private val _state = MutableStateFlow(LocaleState())
    val state: StateFlow<LocaleState> = _state.asStateFlow()

    init {
        // Переключатели приходят потоком из настроек: они же читаются другими экранами, и
        // второй источник разошёлся бы с первым.
        settings.all()
            .onEach { all ->
                _state.value = _state.value.copy(
                    filter = FeedFilter(
                        onlyMyCountry = all[KEY_ONLY_COUNTRY] != "no",
                        onlyMyLanguages = all[KEY_ONLY_LANGUAGES] != "no",
                        languages = all[KEY_LANGUAGES].orEmpty()
                            .split(",")
                            .filter { it.isNotBlank() },
                    ),
                )
            }
            .launchIn(scope)
    }

    fun refresh() {
        scope.launch {
            val locale = locales.read()
            _state.value = if (locale == null) {
                _state.value.copy(loaded = true, trouble = words().settings.localeNotRead)
            } else {
                _state.value.copy(locale = locale, loaded = true, trouble = null)
            }
        }
    }

    /** Человек указал страну. Пустая законна: «не указана» значит «видит всё». */
    fun country(code: String) {
        write(_state.value.locale.copy(country = code.trim().uppercase()))
    }

    /** Человек указал язык, на котором пишет. */
    fun language(tag: String) {
        if (tag.isBlank()) return
        write(_state.value.locale.copy(lang = tag))
    }

    private fun write(locale: PersonLocale) {
        // Показываем сразу, пишем следом: настройка — не то, ради чего человек ждёт
        // сервер. Не записалось — говорим словами, а не молча возвращаем прежнее.
        _state.value = _state.value.copy(locale = locale)
        scope.launch {
            if (!locales.write(locale)) {
                _state.value = _state.value.copy(trouble = words().settings.localeNotSaved)
            }
        }
    }

    fun onlyMyCountry(on: Boolean) {
        scope.launch { settings.put(KEY_ONLY_COUNTRY, if (on) "yes" else "no") }
    }

    fun onlyMyLanguages(on: Boolean) {
        scope.launch { settings.put(KEY_ONLY_LANGUAGES, if (on) "yes" else "no") }
    }

    fun closeTrouble() {
        _state.value = _state.value.copy(trouble = null)
    }

    private companion object {
        const val KEY_ONLY_COUNTRY = "feed.onlyCountry"
        const val KEY_ONLY_LANGUAGES = "feed.onlyLanguages"
        const val KEY_LANGUAGES = "feed.languages"
    }
}

/** Что видно на экране языка. */
data class LocaleState(
    val locale: PersonLocale = PersonLocale(),
    val filter: FeedFilter = FeedFilter(),
    val loaded: Boolean = false,
    val trouble: String? = null,
)
