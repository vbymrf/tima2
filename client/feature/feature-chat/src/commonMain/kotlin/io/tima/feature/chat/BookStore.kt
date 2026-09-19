package io.tima.feature.chat

import io.tima.core.words.BookWords
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.letter
import io.tima.domain.chat.line
import io.tima.domain.chat.Book
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.Section
import io.tima.domain.chat.common
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
    /**
     * Правки разделов идут в порт книги напрямую. `null` — управлять разделами нечем
     * (так собирают store тесты, которым нужен только список): кнопки тогда не работают,
     * и это лучше, чем заглушка, которая делает вид.
     */
    private val edit: Book? = null,
) {
    private val _state = MutableStateFlow(BookState())
    val state: StateFlow<BookState> = _state.asStateFlow()

    init {
        scope.launch {
            book.list().collect { people -> _state.value = _state.value.copy(all = people) }
        }
        scope.launch {
            book.sections().collect { list ->
                // «Общий» лежит в той же таблице, но обычным разделом не показывается: он
                // есть всегда, его не заводят и не убирают. Отсюда и разделение — иначе он
                // встал бы в списке дважды (строкой и собственной вкладкой).
                _state.value = _state.value.copy(
                    sections = list.filterNot { it.common },
                    common = list.firstOrNull { it.common },
                )
            }
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

    /** Выбранный раздел на полосе и в плитке — идентификатором. Пусто — «Всё». */
    fun choseSection(id: String) {
        _state.value = _state.value.copy(chosen = id)
    }

    // ── Управление набором (Р2) ─────────────────────────────────────────────
    //
    // Через порт книги, без своего кэша: список разделов уже течёт потоком из базы, и
    // вторая правда здесь разошлась бы с первой на первом же переименовании.

    fun addSection(name: String, icon: Int) {
        if (name.isBlank()) return
        scope.launch { edit?.addSection(name.trim(), icon) }
    }

    /** Имя и значок «Общего»: строка заводится при первой правке (заказчик 2026-09-19). */
    fun renameCommon(name: String, icon: Int) {
        if (name.isBlank()) return
        scope.launch { edit?.setCommon(name.trim(), icon) }
    }

    fun renameSection(id: String, name: String, icon: Int) {
        if (name.isBlank()) return
        scope.launch { edit?.renameSection(id, name.trim(), icon) }
    }

    /**
     * Сдвинуть на одну позицию — **включая «Общий»** (заказчик 2026-09-19).
     *
     * Стрелками, а не перетаскиванием: перетаскивание в списке, который сам
     * прокручивается, на телефоне промахивается, а стрелка — нет.
     *
     * Места проставляются всему списку заново (0, 1, 2…), а не меняются у двух соседей:
     * у «Общего» без своей строки место заведомо большое, и обмен отправил бы соседа в
     * самый конец. Разделов единицы, а порядок после такой простановки всегда плотный.
     *
     * @param commonName имя «Общего» на экране. Строка у него заводится здесь и только
     *   если она понадобилась — когда после перестановки он перестал быть последним.
     *   Переставить можно лишь то, что записано; заводить её при каждом движении не
     *   нужно — записанное имя больше не меняется вместе с языком.
     */
    fun moveSection(id: String, up: Boolean, commonName: String = "", commonIcon: Int = 0) {
        val state = _state.value
        val list = orderedSections(state.sections, state.common, commonName)
        val at = list.indexOfFirst { it.id == id }
        val to = if (up) at - 1 else at + 1
        if (at < 0 || to !in list.indices) return
        val moved = list.toMutableList().apply { add(to, removeAt(at)) }
        scope.launch {
            if (state.common == null && moved.last().id != COMMON_SECTION && commonName.isNotBlank()) {
                edit?.setCommon(commonName, commonIcon)
            }
            moved.forEachIndexed { place, section -> edit?.placeSection(section.id, place) }
        }
    }

    fun removeSection(id: String) {
        scope.launch {
            edit?.removeSection(id)
            if (_state.value.chosen == id) _state.value = _state.value.copy(chosen = "")
        }
    }

    fun changedView(view: BookView) {
        _state.value = _state.value.copy(view = view)
        scope.launch { view.save(settings) }
    }
}

/** Как показывать список — то, что настраивается в подокне «Вид». */
data class BookView(
    /**
     * `true` — разделы в самом списке («папки»), `false` — полосой над списком.
     *
     * Первый из двух НЕЗАВИСИМЫХ тумблеров `разделы.md` («Полоса / Папки»); второй —
     * [icons]. Вместе дают четыре исполнения одного экрана:
     *
     * | | имена | ярлычки |
     * |---|---|---|
     * | папки  | **Б** гармошка | **А** плитка ярлычков |
     * | полоса | **Г** словами | **В** ярлычками |
     */
    val folders: Boolean = false,
    /** `true` — разделы значками («ярлычки»), `false` — словами («имена»). */
    val icons: Boolean = false,
    /** Размер ярлычков в плитке — пункт «Вида» (решение заказчика 2026-09-18). */
    val tileSize: TileSize = TileSize.Normal,
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
    /**
     * Порядок полей сверху вниз (решение заказчика 2026-09-18: «теперь это список с
     * сортировкой — что в самом верху, то показываем, если есть»). Галки — выше.
     */
    val order: List<PersonField> = PersonField.entries,
) {
    /** Как называть человека: порядок и галки одним значением для строк списков. */
    fun look(): PersonLook = PersonLook(
        order = order,
        checked = buildSet {
            if (showName) add(PersonField.Name)
            if (showUserName) add(PersonField.UserName)
            if (showNickname) add(PersonField.Nick)
            if (showPhone) add(PersonField.Phone)
        },
    )

    fun checked(field: PersonField): Boolean = when (field) {
        PersonField.Name -> showName
        PersonField.UserName -> showUserName
        PersonField.Nick -> showNickname
        PersonField.Phone -> showPhone
    }

    fun withChecked(field: PersonField, on: Boolean): BookView = when (field) {
        PersonField.Name -> copy(showName = on)
        PersonField.UserName -> copy(showUserName = on)
        PersonField.Nick -> copy(showNickname = on)
        PersonField.Phone -> copy(showPhone = on)
    }

    /** Сдвинуть поле на одну позицию вверх или вниз; за край — без изменений. */
    fun moved(field: PersonField, up: Boolean): BookView {
        val at = order.indexOf(field)
        val to = if (up) at - 1 else at + 1
        if (at < 0 || to !in order.indices) return this
        val next = order.toMutableList()
        next[at] = order[to]
        next[to] = field
        return copy(order = next)
    }

    /**
     * @param prefix чей это вид: `book` — контакты, `community` — набор сообществ
     *   (каталог Социума). У каждого набора свой «Вид» — как и свой список разделов.
     */
    suspend fun save(settings: Settings, prefix: String = BOOK) {
        settings.put("$prefix.$VIEW", if (folders) FOLDERS else MENU)
        settings.put("$prefix.$ICONS", icons.toString())
        settings.put("$prefix.$TILE_SIZE", tileSize.wire)
        settings.put("$prefix.$SEARCH", showSearch.toString())
        settings.put("$prefix.$OUTSIDERS", showOutsiders.toString())
        settings.put("$prefix.$NAMES", listOfNotNull(
            "name".takeIf { showName },
            "user".takeIf { showUserName },
            "nick".takeIf { showNickname },
            "phone".takeIf { showPhone },
        ).joinToString(","))
        settings.put("$prefix.$ORDER", order.joinToString(",") { it.wire })
    }

    companion object {
        /** Префикс вида контактов — прежние ключи `book.*`, чтобы настройки телефонов не пропали. */
        const val BOOK = "book"
        /** Префикс вида набора сообществ — каталог Социума и «Группы» Страницы. */
        const val COMMUNITY = "community"
        private const val VIEW = "view"
        private const val ICONS = "icons"
        private const val TILE_SIZE = "tile_size"
        private const val SEARCH = "search"
        private const val OUTSIDERS = "outsiders"
        private const val NAMES = "names"
        private const val ORDER = "names_order"
        private const val FOLDERS = "folders"
        private const val MENU = "menu"

        /**
         * Умолчание — **меню** (решение заказчика 2026-09-05), поэтому «папки» здесь
         * включаются явным значением, а не отсутствием строки.
         */
        /**
         * Порядок из строки. Пропущенные поля дописываются в конец в порядке по умолчанию:
         * набор полей мог вырасти после того, как строка легла на телефон.
         */
        private fun orderFrom(saved: String?): List<PersonField> {
            val listed = saved?.split(",")?.mapNotNull { PersonField.byWire(it.trim()) }?.distinct() ?: emptyList()
            return listed + PersonField.entries.filter { it !in listed }
        }

        fun from(saved: Map<String, String>, prefix: String = BOOK): BookView {
            val names = saved["$prefix.$NAMES"]?.split(",")?.filter { it.isNotBlank() }
            // Умолчания галок — по набору: у контактов телефон и так второй строкой, у
            // авторов в группе номер не нужен, а имя пользователя — нужно: имени из книги у
            // чужого участника чаще всего нет.
            val community = prefix == COMMUNITY
            return BookView(
                icons = saved["$prefix.$ICONS"] == "true",
                tileSize = TileSize.fromWire(saved["$prefix.$TILE_SIZE"]),
                folders = saved["$prefix.$VIEW"] == FOLDERS,
                showSearch = saved["$prefix.$SEARCH"]?.toBooleanStrictOrNull() ?: true,
                showOutsiders = saved["$prefix.$OUTSIDERS"]?.toBooleanStrictOrNull() ?: true,
                showName = names?.contains("name") ?: true,
                showUserName = names?.contains("user") ?: community,
                showNickname = names?.contains("nick") ?: false,
                showPhone = names?.contains("phone") ?: !community,
                order = orderFrom(saved["$prefix.$ORDER"]),
            )
        }
    }
}

/** Раздел книги с его людьми. */
data class BookGroup(
    val name: String,
    val people: List<BookEntry>,
    val outsiders: Boolean = false,
    /** Идентификатор раздела; пусто у «Общего» и у «Телефона». */
    val id: String = "",
    /** Индекс значка; 0 — без значка. */
    val icon: Int = 0,
)

data class BookState(
    val all: List<BookEntry> = emptyList(),
    val sections: List<Section> = emptyList(),
    /** Имя и значок «Общего», если человек их менял; `null` — как в словаре. */
    val common: Section? = null,
    val search: String = "",
    val view: BookView = BookView(),
    val collapsed: Set<String> = emptySet(),
    /**
     * Выбранный раздел в исполнениях с полосой (В, Г) и внутри плитки (А) — идентификатор.
     * Пусто — «Всё». До 2026-09-18 здесь лежало ИМЯ, и выбор ни на что не влиял: полоса
     * подсвечивала чип, а список показывал всех. Фильтр, от которого ничего не меняется,
     * неотличим от сломанного.
     */
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
        // Порядок — общий на все разделы, «Общий» стоит в нём наравне (заказчик
        // 2026-09-19). Принадлежность к нему по-прежнему выражается ПУСТЫМ идентификатором
        // у записи — это отсутствие раздела, — поэтому здесь его ключ переводится в пустой.
        val order = orderedSections(sections, common, words.commonSection)
            .map { Triple(if (it.common) "" else it.id, it.name, it.icon) }
        // Полоса — ФИЛЬТР, а не переход (`разделы.md`): выбранный раздел сужает список.
        // В гармошке выбора нет — там все разделы видны сразу и сворачиваются на месте.
        // «Всё» и «Общий» — не одно и то же (`разделы.md`): у «Всё» пустой выбор, у
        // «Общего» — свой ключ на полосе, который здесь переводится в пустой идентификатор.
        val wanted = if (chosen == COMMON_SECTION) "" else chosen
        // «Всё» в плитке — свой ключ, а не пустой выбор: пустой выбор в плитке означает
        // «показать плитку», и ярлычок «Всё» с пустым ключом никуда не вёл (заказчик
        // 2026-09-18: «раздел Все нельзя зайти»). Внутри — все, без сужения.
        val narrowed = if ((!view.folders || tiles) && chosen.isNotEmpty() && chosen != ALL_SECTION) {
            order.filter { it.first == wanted }
        } else {
            order
        }
        // В гармошке (Б) пустой раздел ВИДЕН — заголовком без строк: его завели осознанно,
        // и он ждёт наполнения (`разделы.md`). На полосе и в плитке внутри раздела пустой
        // не показывается — там он был бы заголовком над пустотой.
        val keepEmpty = view.folders && !tiles && search.isBlank()
        val usual = narrowed.mapNotNull { (id, title, icon) ->
            val people = ours.filter { it.sectionId == id }
            if (people.isEmpty() && !(keepEmpty && id.isNotEmpty())) null
            else BookGroup(title, people, id = id, icon = icon)
        }
        val outsiders = if (strangers.isEmpty()) {
            emptyList()
        } else {
            listOf(BookGroup(words.phoneSection, strangers, outsiders = true))
        }
        return usual + outsiders
    }

    /**
     * Чипы полосы (В, Г): «Всё» и все заведённые разделы, «Общий» — на своём месте.
     *
     * До 2026-09-19 полоса строилась по разделам **с людьми**, и заведённый пустой раздел
     * на ней не появлялся вовсе: человек заводил «Работу» и видел «Всё · Общий» — заказчик
     * назвал это ошибкой, и он прав. Теперь все четыре исполнения собираются одним
     * [sectionTabs]: что заведено, то и показано.
     */
    fun tabs(words: BookWords): List<SectionTab> = sectionTabs(sections, common, words, allKey = "")

    /**
     * Плитка (А) и гармошка (Б): «Всё», ВСЕ заведённые разделы — включая пустые, — и
     * «Общий». Пустой раздел здесь виден: его завели осознанно, и он ждёт наполнения
     * (`разделы.md`, «показываются те разделы, которые человек добавил, включая пустые»).
     * На полосе пустых нет — там чип, за которым пустота, не нужен.
     */
    fun tiles(words: BookWords): List<SectionTab> = sectionTabs(sections, common, words, allKey = ALL_SECTION)

    /** Сколько наших людей в разделе по ключу полосы: «Общий» переводится в пустой идентификатор. */
    fun countIn(key: String): Int {
        if (key == ALL_SECTION) return all.count { it.inTima }
        val id = if (key == COMMON_SECTION) "" else key
        return all.count { it.inTima && it.sectionId == id }
    }

    /** Все разделы с людьми, без учёта выбранного: полоса строится по ним. */
    private fun allGroups(words: BookWords): List<BookGroup> = copy(chosen = "").groups(words)

    /** Список пуст потому, что ничего не нашлось, а не потому, что книга пуста. */
    /** Исполнение А из `разделы.md`: папки ярлычками — плитка, а не гармошка. */
    val tiles: Boolean get() = view.folders && view.icons

    val notFoundNothing: Boolean get() = all.isNotEmpty() && visible.isEmpty()

    /** Разрешения нет — вкладка не пуста, ей есть что предложить нажать. */
    val needPermission: Boolean get() = sync == SyncStep.NeedPermission

    /** Платформа без outsidersной книги: предлагать «разрешить» нечего. */
    val noBook: Boolean get() = sync == SyncStep.NoBook
}

/** Чип полосы разделов: что показать и что выбрать. */
data class SectionTab(val id: String, val name: String, val icon: Int)

/**
 * Ключ «Общего» на полосе и в плитке. У самого «Общего» идентификатор пустой — это
 * отсутствие раздела, — но пустой выбор занят «Всё», а они не одно и то же. Ни один
 * настоящий идентификатор так не выглядит: они случайные.
 */
const val COMMON_SECTION = "common"

/**
 * Ключ ярлычка «Всё» в плитке (А). Не пустая строка: пустой выбор в плитке — это сама
 * плитка, и «Всё» с пустым ключом было некуда открыть. Внутри — все люди без сужения.
 */
const val ALL_SECTION = "*"

/**
 * Вкладки набора разделов — **один сбор на все наборы и все четыре исполнения**.
 *
 * До 2026-09-19 их собирали в четырёх местах по трём разным правилам: полоса книги — по
 * разделам с людьми, плитка — по всем, «Группы» Страницы — по разделам, где есть чат,
 * каталог — по всем. Из-за этого заведённый раздел то появлялся, то нет, и объяснить это
 * человеку было нечем. Правило теперь одно: **«Всё», все заведённые разделы, «Общий»**.
 *
 * @param allKey ключ «Всё»: пустой на полосе (пусто = не сужаем) и [ALL_SECTION] в плитке,
 *   где пустой выбор означает саму плитку.
 */
fun sectionTabs(
    sections: List<Section>,
    common: Section?,
    words: BookWords,
    allKey: String = "",
): List<SectionTab> =
    listOf(SectionTab(allKey, words.everyone, 0)) +
        orderedSections(sections, common, words.commonSection).map { SectionTab(it.id, it.name, it.icon) }

/**
 * Разделы набора в порядке, который задал человек, — **вместе с «Общим»**.
 *
 * «Общий» участвует в порядке наравне с прочими (решение заказчика 2026-09-19: «добавь
 * стрелки Общий»). Раньше он приписывался последним всегда, и список собирался в двух
 * шагах: «сначала обычные, потом он». Теперь шаг один — сортировка по [Section.place], — и
 * место «Общего» ничем не особеннее любого другого.
 *
 * Строки у него может не быть: её заводят только при первой правке или перестановке. Пока
 * её нет, он зовётся словом [commonName] и стоит последним — [COMMON_PLACE_DEFAULT] больше
 * любого настоящего места.
 */
fun orderedSections(sections: List<Section>, common: Section?, commonName: String): List<Section> {
    val whole = common ?: Section(COMMON_SECTION, commonName, 0, place = COMMON_PLACE_DEFAULT)
    return (sections + whole).sortedBy { it.place }
}

/** Место «Общего», пока его строки нет: заведомо больше любого настоящего — он последний. */
const val COMMON_PLACE_DEFAULT = 1_000
