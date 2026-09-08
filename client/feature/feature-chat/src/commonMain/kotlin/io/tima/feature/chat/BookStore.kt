package io.tima.feature.chat

import io.tima.core.ui.BookWords
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.ObserveBook
import io.tima.domain.chat.Settings
import io.tima.domain.chat.SyncBook
import io.tima.domain.chat.SyncStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Вкладка «Контакты» окна «Телефон» — ПЛАН-КОНТАКТОВ.md, Д5.
 *
 * Список приходит **потоком из базы**, как у [ChatsStore]: прочитанное с outsidersа и
 * итог сверки появляются сами, без опроса.
 *
 * **Поиск фильтрует уже полученный список.** Людей в книге сотни, а не тысячи, и второй
 * запрос на каждую букву был бы работой ради работы. Тем более что имена в базе
 * зашифрованы: искать по ним в SQL нечем.
 */
class BookStore(
    private val book: ObserveBook,
    private val settings: Settings,
    private val sync: SyncBook,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(BookState())
    val state: StateFlow<BookState> = _state.asStateFlow()

    init {
        scope.launch {
            book.list().collect { people -> _state.value = _state.value.copy(all = people) }
        }
        scope.launch {
            book.sections().collect { list -> _state.value = _state.value.copy(sections = list) }
        }
        scope.launch {
            settings.all().collect { saved -> _state.value = _state.value.copy(view = BookView.from(saved)) }
        }
    }

    /**
     * Прочитать outsidersную книгу и сверить.
     *
     * Зовётся при открытии вкладки, а не при запуске приложения: разрешение, спрошенное
     * на первом экране, объяснить нечем — человек ещё не видел ни одного контакта.
     */
    fun refresh() {
        scope.launch {
            _state.value = _state.value.copy(working = true)
            val step = sync.run()
            _state.value = _state.value.copy(working = false, sync = step)
        }
    }

    fun changedSearch(line: String) {
        _state.value = _state.value.copy(search = line)
    }

    fun openedSection(name: String) {
        val was = _state.value.collapsed
        _state.value = _state.value.copy(
            collapsed = if (name in was) was - name else was + name,
        )
    }

    /** Выбранный раздел в виде «меню». Пусто — «Все». */
    fun choseSection(name: String) {
        _state.value = _state.value.copy(chosen = name)
    }

    fun changedView(view: BookView) {
        _state.value = _state.value.copy(view = view)
        scope.launch { view.save(settings) }
    }
}

/** Как показывать список — то, что настраивается в подокне «Вид». */
data class BookView(
    /** `true` — разделы полосами («папки»), `false` — вторым рядом вкладок («меню»). */
    val folders: Boolean = false,
    val showSearch: Boolean = true,
    /** Показывать раздел «Телефон» — тех, кого нет в TIMa. */
    val showOutsiders: Boolean = true,
    /**
     * Чем называть человека. Ни одной галки — order по умолчанию, тот же самый:
     * имя → имя пользователя → ник → outsiders → «Без имени».
     */
    val showName: Boolean = true,
    val showUserName: Boolean = false,
    val showNickname: Boolean = false,
    val showPhone: Boolean = true,
) {
    suspend fun save(settings: Settings) {
        settings.put(VIEW, if (folders) FOLDERS else MENU)
        settings.put(SEARCH, showSearch.toString())
        settings.put(OUTSIDERS, showOutsiders.toString())
        settings.put(NAMES, listOfNotNull(
            "name".takeIf { showName },
            "user".takeIf { showUserName },
            "nick".takeIf { showNickname },
            "phone".takeIf { showPhone },
        ).joinToString(","))
    }

    companion object {
        private const val VIEW = "book.view"
        private const val SEARCH = "book.search"
        private const val OUTSIDERS = "book.outsiders"
        private const val NAMES = "book.names"
        private const val FOLDERS = "folders"
        private const val MENU = "menu"

        /**
         * Умолчание — **меню** (решение заказчика 2026-09-05), поэтому «папки» здесь
         * включаются явным значением, а не отсутствием строки.
         */
        fun from(saved: Map<String, String>): BookView {
            val names = saved[NAMES]?.split(",")?.filter { it.isNotBlank() }
            return BookView(
                folders = saved[VIEW] == FOLDERS,
                showSearch = saved[SEARCH]?.toBooleanStrictOrNull() ?: true,
                showOutsiders = saved[OUTSIDERS]?.toBooleanStrictOrNull() ?: true,
                showName = names?.contains("name") ?: true,
                showUserName = names?.contains("user") ?: false,
                showNickname = names?.contains("nick") ?: false,
                showPhone = names?.contains("phone") ?: true,
            )
        }
    }
}

/** Раздел книги с его людьми. */
data class BookGroup(val name: String, val people: List<BookEntry>, val outsiders: Boolean = false)

data class BookState(
    val all: List<BookEntry> = emptyList(),
    val sections: List<String> = emptyList(),
    val search: String = "",
    val view: BookView = BookView(),
    val collapsed: Set<String> = emptySet(),
    val chosen: String = "",
    val working: Boolean = false,
    val sync: SyncStep? = null,
) {
    /**
     * Что показать.
     *
     * Ищется по имени, **нику и номеру** — по тому же, по чему человека находят на
     * сервере. По имени поиск здесь местный и другим быть не может: сервер по имени не
     * ищет вовсе (решение 2026-09-05), а имена oursх контактов и так лежат на устройстве.
     */
    val visible: List<BookEntry>
        get() {
            val request = search.trim()
            val listed = if (view.showOutsiders) all else all.filter { it.inTima }
            if (request.isEmpty()) return listed
            val digits = request.filter { it.isDigit() }
            return listed.filter { person ->
                person.name?.contains(request, ignoreCase = true) == true ||
                    (digits.isNotEmpty() && person.phone.contains(digits))
            }
        }

    /**
     * Разделы с людьми. **«Телефон» всегда последний** и всегда отдельный: в нём те,
     * кого нет в TIMa, и у них вместо звонка «Пригласить».
     */
    fun groups(words: BookWords): List<BookGroup> {
        val (ours, strangers) = visible.partition { it.inTima }
        val order = sections + listOf("")
        val usual = order.mapNotNull { name ->
            val people = ours.filter { it.section == name }
            if (people.isEmpty()) null else BookGroup(name.ifBlank { words.commonSection }, people)
        }
        val outsiders = if (strangers.isEmpty()) {
            emptyList()
        } else {
            listOf(BookGroup(words.phoneSection, strangers, outsiders = true))
        }
        return usual + outsiders
    }

    /** Вкладки вида «меню»: «Все», разделы, «Телефон» — последним. */
    fun tabs(words: BookWords): List<String> = listOf(words.everyone) + groups(words).map { it.name }

    /** Список пуст потому, что ничего не нашлось, а не потому, что книга пуста. */
    val notFoundNothing: Boolean get() = all.isNotEmpty() && visible.isEmpty()

    /** Разрешения нет — вкладка не пуста, ей есть что предложить нажать. */
    val needPermission: Boolean get() = sync == SyncStep.NeedPermission

    /** Платформа без outsidersной книги: предлагать «разрешить» нечего. */
    val noBook: Boolean get() = sync == SyncStep.NoBook
}
