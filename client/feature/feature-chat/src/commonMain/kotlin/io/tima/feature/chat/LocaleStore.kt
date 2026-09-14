package io.tima.feature.chat

import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.core.words.RussianWords
import io.tima.core.words.ChatWords
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
                val written = all[KEY_LANGUAGES].orEmpty()
                _state.value = _state.value.copy(
                    // Сырая строка — для поля, разобранный список — для отбора. Разбирать
                    // при записи нельзя: человек, набравший «ru, », тут же потерял бы
                    // запятую из-под курсора.
                    languagesText = written,
                    filter = FeedFilter(
                        onlyMyCountry = all[KEY_ONLY_COUNTRY] != "no",
                        onlyMyLanguages = all[KEY_ONLY_LANGUAGES] != "no",
                        languages = written
                            .split(",")
                            .map { it.trim().lowercase() }
                            .filter { it.isNotBlank() },
                    ),
                )
            }
            .launchIn(scope)
    }

    fun refresh() {
        scope.launch {
            val locale = locales.read()
            if (locale == null) {
                _state.value = _state.value.copy(loaded = true, trouble = words().settings.localeNotRead)
                return@launch
            }
            _state.value = _state.value.copy(locale = locale, loaded = true, trouble = null)
            // Языка нет вовсе — берём язык приложения (Я12). Это догадка, и она **сразу
            // записывается**: показанное на экране обязано совпадать с тем, по чему сервер
            // отбирает ленты, иначе человек настраивает одно, а получает другое.
            //
            // Сегодня сюда почти не заходят: колонка `lang` на сервере объявлена
            // NOT NULL DEFAULT 'ru', и пустой тег он не отдаёт. Это заслон на случай,
            // когда умолчание сервера станет пустым, — а до тех пор человек с испанским
            // интерфейсом видит «ru» и меняет его руками.
            if (locale.lang.isBlank()) write(locale.copy(lang = words().tag))
        }
    }

    /** Человек указал страну. Пустая законна: «не указана» значит «видит всё». */
    fun country(code: String) {
        write(_state.value.locale.copy(country = code.trim().uppercase()))
    }

    /**
     * Человек указал язык, на котором пишет (Я12).
     *
     * **Это не язык приложения**, и в один список их не сводят: сервер держит `lang`
     * свободным текстом, а словарей у нас три. Сведя, мы запретили бы писать по-немецки —
     * и запретили бы молча, потому что выбирать пришлось бы из трёх строк.
     *
     * Тег приводится к нижнему регистру — зеркально стране, которая приводится к верхнему:
     * «RU» и «ru» один язык, и две записи о нём означали бы отбор, промахивающийся через
     * раз. Пустой не пишется: «не указан» настраивают не стиранием поля, а тем, что в него
     * не заходили.
     */
    fun writingLanguage(tag: String) {
        val clean = tag.trim().lowercase()
        if (clean.isBlank()) return
        write(_state.value.locale.copy(lang = clean))
    }

    /**
     * Какие языки человек читает (Я12). Пусто — только его собственный.
     *
     * Строка кладётся как набрана, без разбора: разобранный список нужен отбору, а полю
     * нужна строка, в которой можно держать курсор. Разбор — в потоке настроек выше.
     */
    fun readingLanguages(value: String) {
        scope.launch { settings.put(KEY_LANGUAGES, value) }
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
    /** Какие языки читать — как набрано человеком. Разобранный список лежит в [filter]. */
    val languagesText: String = "",
    val loaded: Boolean = false,
    val trouble: String? = null,
)
