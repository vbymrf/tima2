package io.tima.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.tima.core.ui.TabButton
import io.tima.core.ui.Tima
import io.tima.core.ui.words

/**
 * Окна 2–5 по макету: вкладки и вторые ряды настоящие, содержимое честно пустое.
 *
 * ── ПОЧЕМУ СОДЕРЖИМОГО НЕТ, И ЭТО НЕ НЕДОДЕЛКА ──────────────────────────────
 *
 * Социального слоя на сервере нет вовсе: ни лент, ни постов, ни коллекций, ни
 * реакций, ни подписок. Показать здесь что-нибудь можно было бы только выдумав, а
 * выдуманные записи однажды уезжают в сборку и принимаются за работающие — этим
 * проект уже болел.
 *
 * Поэтому построено ровно то, что не выдумано: **окно существует, называется своим
 * именем, переключается, помнит выбранную вкладку и выбранный фильтр** — и говорит
 * словами, чего в нём ещё нет и чем это держится. Такое окно можно взять в руки и
 * проверить: рейка, свайп, счётчики, возврат в прежнюю вкладку.
 *
 * ── ВТОРОЙ РЯД ──────────────────────────────────────────────────────────────
 *
 * Заведён 2026-09-02 по всем местам, где он есть в макете. **Заглушка меняется
 * вместе с фильтром**, и это обязательное условие, а не украшение: фильтр, который
 * ничего не меняет на экране, неотличим от сломанного, и человек будет тыкать в него
 * повторно.
 *
 * Каждая вкладка и каждый чип ниже — строка из `интерфейс.md §4–§7`, а не выдумка.
 */

/**
 * Окно 2 «Социум» — `§4`. Второго ряда у него в макете нет.
 *
 * Содержимое «Каталога» и «Друзей» приходит снаружи: списки групп и карточек знает
 * `feature-group`, а оболочка знает раму. Отдать раме ещё и работу с сервером значило бы
 * сделать её местом, где сходится всё.
 *
 * Незаполненные вкладки остаются честными заглушками — «здесь будет лента», а не
 * выдуманные записи.
 */
@Composable
fun SocialWindow(
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
    catalog: (@Composable () -> Unit)? = null,
    friends: (@Composable () -> Unit)? = null,
    /** «Вид» каталога — кнопка в хвосте вкладок, только на «Каталоге». `null` — кнопки нет. */
    onCatalogView: (() -> Unit)? = null,
    /** Второй ряд каталога — полоса разделов набора сообществ (В/Г). `null` — ряда нет. */
    catalogRow: (@Composable () -> Unit)? = null,
    /**
     * Вкладка снаружи: подокно «Вид» перестраивает окно целиком, и вкладка, живущая
     * внутри, сбрасывалась бы на «Общую» при каждом закрытии подокна. `null` — своя.
     */
    tab: WindowTab? = null,
    onTab: ((WindowTab) -> Unit)? = null,
) = WindowWithTabs(
    window = Window.Social,
    tabs = SOCIUM_TABS,
    selectedOutside = tab,
    onSelectOutside = onTab,
    onSwitchWindows = onSwitchWindows,
    onSearch = onSearch,
    onSettings = onSettings,
    onNeighbourWindow = onNeighbourWindow,
    modifier = modifier,
    // «Вид» — только на «Каталоге»: у лент настраивать нечего, и кнопка там означала бы
    // несуществующее (то же правило, что у «Контактов» окна 1).
    tabsTrailing = onCatalogView?.let { view -> { tab -> if (tab == WindowTab.Catalogue) CatalogViewButton(view) } },
    secondRow = catalogRow?.let { row -> { tab -> if (tab == WindowTab.Catalogue) row() } },
) { tab ->
    when (tab) {
        WindowTab.Common -> TabStub(
            "Здесь будет общая лента",
            "Рекомендации и подписки. Сервер социального слоя не готов: постов, " +
                "оценок и подписок на нём нет вовсе.",
        )

        WindowTab.Friends -> friends?.invoke() ?: TabStub(
            "Здесь будет лента друзей",
            "То, что положили себе на страницу люди из вашей книги.",
        )

        else -> catalog?.invoke() ?: TabStub(
            "Здесь будет каталог",
            "Группы, каналы, сообщества и голосовые чаты по разделам.",
        )
    }
}

/**
 * Кнопка «Вид» каталога — та же таблетка, что у «Контактов» окна 1: открывает подокно
 * вида набора сообществ (полоса/папки, имена/ярлычки, размер, разделы).
 */
@Composable
private fun CatalogViewButton(onClick: () -> Unit) {
    TabButton(label = Tima.words.tabs.label(WindowTab.View), glyph = "▤", onClick = onClick)
}

/**
 * Окно 3 «Медиа» — `§5`.
 *
 * **Режим окна, а не вкладки:** «Лента» и «Слайды» — два способа смотреть одно и то
 * же. Поэтому переключатель стоит **в ряду вкладок, следом за ними**, а не во втором
 * ряду среди фильтров: второй ряд принадлежит вкладке и меняется вместе с ней, а этот
 * режим вкладку переживает.
 *
 * В макете он живёт в шапке и набран знаками «▤ ▣»: подписи «Лента | Слайды» стоят 140
 * точек и вместе с логотипом, именем и двумя кнопками не помещались в телефонные 380.
 * Здесь он вынесен из шапки решением заказчика 2026-09-02 — «положи правее вкладок», —
 * и там места хватает, поэтому вернулись подписи: знаки экономили ширину, которой в
 * ряду вкладок ничто не угрожает. Не поместилось — ряд перенесётся на вторую строку,
 * а не спрячет хвост за краем.
 */
@Composable
fun MediaWindow(
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(MEDIA_MODES.first()) }
    WindowWithTabs(
        window = Window.Media,
        tabs = COMMON_TABS,
        onSwitchWindows = onSwitchWindows,
        onSearch = onSearch,
        onSettings = onSettings,
        onNeighbourWindow = onNeighbourWindow,
        modifier = modifier,
        tabsTrailing = { _ -> ModeSwitch(MEDIA_MODES, mode, { mode = it }) },
    ) { tab ->
        val looking = if (mode == WindowTab.Slides) {
            "Кадр во весь экран, отклики под ним, листание вверх и вниз."
        } else {
            "Карточками, как в остальных окнах."
        }
        when (tab) {
            WindowTab.Common -> TabStub(
                "Здесь будет медиа-лента: $mode",
                "$looking Ни медиа-постов, ни ленты сервер не отдаёт.",
            )

            WindowTab.Friends -> TabStub(
                "Здесь будут медиа друзей: $mode",
                "$looking Тот же социальный слой, которого на сервере нет.",
            )

            else -> TabStub(
                "Здесь будут коллекции",
                "Коллекции по разделам, с метками «E2E · личная» и «публичная». " +
                    "Коллекций нет ни в базе, ни на сервере.",
            )
        }
    }
}

/**
 * Окно 4 «Общение» — `§6`. Ряд фильтров есть только у «Реакций». [pointers] — вкладка «Указатели»
 * (ПЛАН-(УК)): содержимое приходит снаружи, у него свои разделы.
 */
@Composable
fun ActivityWindow(
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
    pointers: @Composable () -> Unit = {},
    /** «Вид» «Указателей» — кнопкой в хвосте вкладок, как у «Телефона» и «Социума». `null` — кнопки нет. */
    onPointersView: (() -> Unit)? = null,
    /** Второй ряд «Указателей» — полоса разделов. `null` — ряда нет. */
    pointersRow: (@Composable () -> Unit)? = null,
    /** «Ответы» (ПЛАН-(СЦ) §1.9, СЦ15): ленты с сущностями; `null` — прежняя заглушка. */
    answers: (@Composable () -> Unit)? = null,
) {
    var reactions by remember { mutableStateOf(REACTION_FILTERS.first()) }
    WindowWithTabs(
        window = Window.Activity,
        tabs = SOCIAL_TABS,
        onSwitchWindows = onSwitchWindows,
        onSearch = onSearch,
        onSettings = onSettings,
        onNeighbourWindow = onNeighbourWindow,
        modifier = modifier,
        tabsTrailing = onPointersView?.let { view -> { tab -> if (tab == WindowTab.Pointers) CatalogViewButton(view) } },
        secondRow = { tab ->
            if (tab == WindowTab.Reactions) {
                FilterRow(REACTION_FILTERS, reactions, { reactions = it })
            }
            if (tab == WindowTab.Pointers) pointersRow?.invoke()
        },
    ) { tab ->
        when (tab) {
            WindowTab.Answers -> answers?.invoke() ?: TabStub(
                "Здесь будет то, где вам ответили",
                "Ветки обсуждений и отслеживаемое.",
            )

            WindowTab.Reactions -> TabStub(
                "Здесь будет, как оценили ваше: " + Tima.words.tabs.label(reactions).lowercase(),
                when (reactions) {
                    WindowTab.Comments -> "Только комментарии к вашим записям. Комментариев на сервере нет."
                    WindowTab.Marks -> "Только эмоции и «плюсы». Шкала эмоций на сервере не заведена."
                    else -> "Комментарии и оценки вперемешку. Ни того, ни другого на сервере нет."
                },
            )

            else -> pointers()
        }
    }
}

/**
 * Окно 5 «Страница» — одна вкладка «Лента» (заказчик 2026-11-19: «Нужно убрать всё из окна Страница. Там оставить одну
 * вкладку Лента»). Группы и прочие сущности живут в «Социуме» (ПЛАН-(СЦ)): вкладка «Группы» здесь была остатком
 * прежнего замысла, и новое в группах считалось этому окну — человек видел число на «Странице», а в «Социуме» —
 * ничего. «Подписан» и «Коллекции» были заглушками. Самим окном займёмся потом.
 */
@Composable
fun PageWindow(
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Содержимое вкладки «Лента». `null` — заглушка.
     *
     * Слотом, а не экраном внутри: оболочка не знает ни о страницах, ни о переносе — она
     * знает про окна и вкладки.
     */
    feed: (@Composable () -> Unit)? = null,
) {
    WindowWithTabs(
        window = Window.Page,
        tabs = PAGE_TABS,
        onSwitchWindows = onSwitchWindows,
        onSearch = onSearch,
        onSettings = onSettings,
        onNeighbourWindow = onNeighbourWindow,
        modifier = modifier,
    ) { _ ->
        if (feed != null) {
            feed()
        } else {
            TabStub(
                "Здесь будет то, что вы показываете",
                "Записи вашей страницы.",
            )
        }
    }
}

/**
 * Надписи рядов — все, какие есть в окнах, в одном месте.
 *
 * `internal`, а не `private`, по одной причине: **их меряет тест на перенос строки**
 * ([RowFitTest]). Список, переписанный в тест руками, разошёлся бы с окном молча — а
 * тест мерил бы старые слова и уверял, что всё помещается.
 */
/** Вкладки окна 3 (`§5`). */
internal val COMMON_TABS = listOf(WindowTab.Common, WindowTab.Friends, WindowTab.Catalogue)

/** Вкладки «Социума» (окно 2, `§4`): «Каталог» первым — решение заказчика 2026-10-05. */
internal val SOCIUM_TABS = listOf(WindowTab.Catalogue, WindowTab.Common, WindowTab.Friends)

/** Вкладки окна 4 (`§6`). */
internal val SOCIAL_TABS = listOf(WindowTab.Answers, WindowTab.Reactions, WindowTab.Pointers)

/** Вкладки окна 5 (`§7`). */
internal val PAGE_TABS = listOf(WindowTab.Feed)

/** «Лента» и «Слайды» — два способа смотреть одно и то же (`§5`). */
internal val MEDIA_MODES = listOf(WindowTab.Feed, WindowTab.Slides)

/** Фильтры «Реакций» (`§6`). */
internal val REACTION_FILTERS = listOf(WindowTab.All, WindowTab.Comments, WindowTab.Marks)

/**
 * Фильтры журнала звонков — `интерфейс.md §2`, ряд под вкладками окна 1.
 *
 * Единственный список здесь **не** `internal`: окно «Телефон» живёт в `shared`, рядом с
 * данными переписок, а не здесь с остальными четырьмя. Держать его надписи там значило
 * бы, что мерить ряд на перенос нечем — [RowFitTest] лежит в этом модуле и до чужого
 * `private` не дотянется.
 */
val CALL_FILTERS =
    listOf(WindowTab.All, WindowTab.FromBook, WindowTab.Unknown, WindowTab.Missed)

/**
 * Общий вид окна, у которого пока нет содержимого.
 *
 * Вкладка запоминается на время жизни окна: «единая сессия» из `§1` требует, чтобы
 * окно возвращалось туда, где его оставили. Фильтры второго ряда живут у самого окна,
 * а не здесь: их набор у каждого свой, и общего состояния «фильтр» не существует.
 */
@Composable
private fun WindowWithTabs(
    window: Window,
    tabs: List<WindowTab>,
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
    /** Хвост ряда вкладок. Получает выбранную вкладку: у одних вкладок он есть, у других нет. */
    tabsTrailing: (@Composable (WindowTab) -> Unit)? = null,
    /** Ряд под вкладками. Получает выбранную вкладку: у разных вкладок он разный. */
    secondRow: (@Composable (WindowTab) -> Unit)? = null,
    /** Вкладка, которую держит вызывающий; `null` — окно держит её само. */
    selectedOutside: WindowTab? = null,
    onSelectOutside: ((WindowTab) -> Unit)? = null,
    /** Число вкладки (ЖУ2). */
    countOf: (WindowTab) -> Int = { 0 },
    content: @Composable (WindowTab) -> Unit,
) {
    // Вкладка — из общего места [WindowTabs] (беда 2026-10-08): окно закрывается при переходе,
    // и своя вкладка внутри него сбрасывалась на первую. Нет общего места (снимки) — своя.
    val kept = LocalWindowTabs.current
    var own by remember(window) { mutableStateOf(tabs.first()) }
    val selected = (selectedOutside ?: kept?.of(window, tabs) ?: own).takeIf { it in tabs } ?: tabs.first()
    val row = secondRow
    val trailing = tabsTrailing
    WindowFrame(
        window = window,
        tabs = tabs,
        selected = selected,
        onTab = {
            own = it
            kept?.choose(window, it)
            onSelectOutside?.invoke(it)
        },
        onSwitchWindows = onSwitchWindows,
        onSearch = onSearch,
        onSettings = onSettings,
        onNeighbourWindow = onNeighbourWindow,
        modifier = modifier,
        tabsTrailing = if (trailing == null) null else ({ trailing(selected) }),
        secondRow = if (row == null) null else ({ row(selected) }),
        countOf = countOf,
    ) {
        content(selected)
    }
}
