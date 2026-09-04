package io.tima.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

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
) = WindowWithTabs(
    window = Window.Social,
    tabs = COMMON_TABS,
    onSwitchWindows = onSwitchWindows,
    onSearch = onSearch,
    onSettings = onSettings,
    onNeighbourWindow = onNeighbourWindow,
    modifier = modifier,
) { tab ->
    when (tab) {
        "Общая" -> TabStub(
            "Здесь будет общая лента",
            "Рекомендации и подписки. Сервер социального слоя не готов: постов, " +
                "оценок и подписок на нём нет вовсе.",
        )

        "Друзья" -> friends?.invoke() ?: TabStub(
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
        tabsTrailing = { ModeSwitch(MEDIA_MODES, mode, { mode = it }) },
    ) { tab ->
        val looking = if (mode == "Слайды") {
            "Кадр во весь экран, отклики под ним, листание вверх и вниз."
        } else {
            "Карточками, как в остальных окнах."
        }
        when (tab) {
            "Общая" -> TabStub(
                "Здесь будет медиа-лента: $mode",
                "$looking Ни медиа-постов, ни ленты сервер не отдаёт.",
            )

            "Друзья" -> TabStub(
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

/** Окно 4 «Общение» — `§6`. Ряд фильтров есть только у «Реакций». */
@Composable
fun ActivityWindow(
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
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
        secondRow = { tab ->
            if (tab == "Реакции") {
                FilterRow(REACTION_FILTERS, reactions, { reactions = it })
            }
        },
    ) { tab ->
        when (tab) {
            "Ответы" -> TabStub(
                "Здесь будет то, где вам ответили",
                "Ветки обсуждений и отслеживаемое. Комментариев и веток на сервере нет.",
            )

            "Реакции" -> TabStub(
                "Здесь будет, как оценили ваше: ${reactions.lowercase()}",
                when (reactions) {
                    "Комментарии" -> "Только комментарии к вашим записям. Комментариев на сервере нет."
                    "Оценки" -> "Только эмоции и «плюсы». Шкала эмоций на сервере не заведена."
                    else -> "Комментарии и оценки вперемешку. Ни того, ни другого на сервере нет."
                },
            )

            else -> TabStub(
                "Здесь будет то, что вы собрали",
                "Плитки коллекций. Коллекций нет ни в базе, ни на сервере.",
            )
        }
    }
}

/**
 * Окно 5 «Страница» — `§7`, перерисовано 2026-08-25 под ADR-0018.
 *
 * **Переключатель «Открытое / Личное» стоит не на всех подвкладках.** «Каталог» —
 * это собственность человека: каналы, сообщества, группы, голосовые комнаты. Она не
 * бывает «открытой» или «личной», поэтому переключателя там нет вовсе (`§7`), а не
 * стоит серым. Серый переключатель сообщал бы, что за ним что-то есть.
 */
@Composable
fun PageWindow(
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    var collections by remember { mutableStateOf(COLLECTION_TABS.first()) }
    var outline by remember { mutableStateOf(COLLECTION_MODES.last()) }
    WindowWithTabs(
        window = Window.Page,
        // Порядок из макета: страница открывается на «Подписан», и это её первая вкладка.
        tabs = PAGE_TABS,
        onSwitchWindows = onSwitchWindows,
        onSearch = onSearch,
        onSettings = onSettings,
        onNeighbourWindow = onNeighbourWindow,
        modifier = modifier,
        secondRow = { tab ->
            if (tab == "Коллекции") {
                FilterRow(
                    items = COLLECTION_TABS,
                    selected = collections,
                    onPick = { collections = it },
                    trailing = if (collections == "Каталог") {
                        null
                    } else {
                        { ModeSwitch(COLLECTION_MODES, outline, { outline = it }) }
                    },
                )
            }
        },
    ) { tab ->
        when (tab) {
            "Подписан" -> TabStub(
                "Здесь будут каналы и сообщества",
                "На них подписываются. Подписок на сервере нет.",
            )

            "Лента" -> TabStub(
                "Здесь будет то, что вы показываете",
                "Отложенные записи и положенные группы. Подвкладок у «Ленты» нет — " +
                    "так в макете. Раздача по книге не реализована.",
            )

            "Коллекции" -> when (collections) {
                "Каталог" -> TabStub(
                    "Здесь будет ваша собственность",
                    "Каналы, сообщества, группы и голосовые комнаты — то, чем вы " +
                        "управляете. Переключателем контура каталог не управляется. " +
                        "Ничего этого на сервере нет.",
                )

                else -> TabStub(
                    "Здесь будет собранное вами: ${collections.lowercase()}, ${outline.lowercase()}",
                    if (outline == "Личное") {
                        "Контур под сквозным шифрованием, метка «E2E · личная». " +
                            "Коллекций на сервере нет."
                    } else {
                        "Публичный контур: то, что видно всем. Коллекций на сервере нет."
                    },
                )
            }

            else -> TabStub(
                "Здесь будут группы, где вы состоите",
                "Личные группы уже работают, но пока открываются из окна 1: " +
                    "каталог окна 2 и эта вкладка ещё не связаны с данными.",
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
/** Вкладки окон 2 и 3 (`§4`, `§5`). */
internal val COMMON_TABS = listOf("Общая", "Друзья", "Каталог")

/** Вкладки окна 4 (`§6`). */
internal val SOCIAL_TABS = listOf("Ответы", "Реакции", "Коллекции")

/** Вкладки окна 5 (`§7`). */
internal val PAGE_TABS = listOf("Подписан", "Лента", "Коллекции", "Группы")

/** «Лента» и «Слайды» — два способа смотреть одно и то же (`§5`). */
internal val MEDIA_MODES = listOf("Лента", "Слайды")

/** Фильтры «Реакций» (`§6`). */
internal val REACTION_FILTERS = listOf("Все", "Комментарии", "Оценки")

/** Подвкладки «Коллекций» (`§7`). */
internal val COLLECTION_TABS = listOf("Медиа", "Сообщения", "Каталог")

/**
 * Контур коллекций (`§7`). Открывается окно на «Личном»: это своё хозяйство, и
 * показывать его сначала публичной частью значило бы прятать от человека его же
 * содержимое — так и в макете, где залито «Личное».
 */
internal val COLLECTION_MODES = listOf("Открытое", "Личное")

/**
 * Фильтры журнала звонков — `интерфейс.md §2`, ряд под вкладками окна 1.
 *
 * Единственный список здесь **не** `internal`: окно «Телефон» живёт в `shared`, рядом с
 * данными переписок, а не здесь с остальными четырьмя. Держать его надписи там значило
 * бы, что мерить ряд на перенос нечем — [RowFitTest] лежит в этом модуле и до чужого
 * `private` не дотянется.
 */
val CALL_FILTERS = listOf("Все", "Контактов", "Неизвестные", "Пропущенные")

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
    tabs: List<String>,
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
    /** Хвост ряда вкладок: режим всего окна, он вкладку переживает. */
    tabsTrailing: (@Composable () -> Unit)? = null,
    /** Ряд под вкладками. Получает выбранную вкладку: у разных вкладок он разный. */
    secondRow: (@Composable (String) -> Unit)? = null,
    content: @Composable (String) -> Unit,
) {
    var selected by remember(window) { mutableStateOf(tabs.first()) }
    val row = secondRow
    WindowFrame(
        window = window,
        tabs = tabs,
        selected = selected,
        onTab = { selected = it },
        onSwitchWindows = onSwitchWindows,
        onSearch = onSearch,
        onSettings = onSettings,
        onNeighbourWindow = onNeighbourWindow,
        modifier = modifier,
        tabsTrailing = tabsTrailing,
        secondRow = if (row == null) null else ({ row(selected) }),
    ) {
        content(selected)
    }
}
