package io.tima.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Окна 2–5 по макету: вкладки настоящие, содержимое честно пустое.
 *
 * ── ПОЧЕМУ СОДЕРЖИМОГО НЕТ, И ЭТО НЕ НЕДОДЕЛКА ──────────────────────────────
 *
 * Социального слоя на сервере нет вовсе: ни лент, ни постов, ни коллекций, ни
 * реакций, ни подписок. Показать здесь что-нибудь можно было бы только выдумав, а
 * выдуманные записи однажды уезжают в сборку и принимаются за работающие — этим
 * проект уже болел.
 *
 * Поэтому построено ровно то, что не выдумано: **окно существует, называется своим
 * именем, переключается, помнит выбранную вкладку** — и говорит словами, чего в нём
 * ещё нет и чем это держится. Такое окно можно взять в руки и проверить: рейка,
 * свайп, счётчики, возврат в прежнюю вкладку.
 *
 * Каждая вкладка ниже — строка из `интерфейс.md §4–§7`, а не выдумка: набор вкладок
 * и их порядок взяты оттуда, и разойтись с макетом они не могут.
 */

/** Окно 2 «Социум» — `§4`. */
@Composable
fun SocialWindow(
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
) = WindowWithTabs(
    window = Window.Social,
    tabs = listOf("Общая", "Друзья", "Каталог"),
    stubs = mapOf(
        "Общая" to ("Здесь будет общая лента" to
            "Рекомендации и подписки. Сервер социального слоя не готов: постов, " +
            "оценок и подписок на нём нет вовсе."),
        "Друзья" to ("Здесь будет лента друзей" to
            "То, что положили себе на страницу люди из вашей книги. " +
            "Раздача карточек по книге не реализована (ADR-0018)."),
        "Каталог" to ("Здесь будет каталог" to
            "Группы, каналы, сообщества и голосовые чаты по разделам. " +
            "Личные группы уже работают, но живут пока во временном входе окна 1."),
    ),
    onSwitchWindows = onSwitchWindows,
    onSearch = onSearch,
    onSettings = onSettings,
    onNeighbourWindow = onNeighbourWindow,
    modifier = modifier,
)

/** Окно 3 «Медиа» — `§5`. */
@Composable
fun MediaWindow(
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
) = WindowWithTabs(
    window = Window.Media,
    tabs = listOf("Общая", "Друзья", "Каталог"),
    stubs = mapOf(
        "Общая" to ("Здесь будет медиа-лента" to
            "Фото и видео карточками, с переключателем «лента / слайды» в шапке. " +
            "Ни медиа-постов, ни ленты сервер не отдаёт."),
        "Друзья" to ("Здесь будут медиа друзей" to
            "Тот же социальный слой, которого на сервере нет."),
        "Каталог" to ("Здесь будут коллекции" to
            "Коллекции по разделам, с метками «E2E · личная» и «публичная». " +
            "Коллекций нет ни в базе, ни на сервере."),
    ),
    onSwitchWindows = onSwitchWindows,
    onSearch = onSearch,
    onSettings = onSettings,
    onNeighbourWindow = onNeighbourWindow,
    modifier = modifier,
)

/** Окно 4 «Общение» — `§6`. */
@Composable
fun ActivityWindow(
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
) = WindowWithTabs(
    window = Window.Activity,
    tabs = listOf("Ответы", "Реакции", "Коллекции"),
    stubs = mapOf(
        "Ответы" to ("Здесь будет то, где вам ответили" to
            "Ветки обсуждений и отслеживаемое. Комментариев и веток на сервере нет."),
        "Реакции" to ("Здесь будет, как оценили ваше" to
            "Эмоции и «плюсы» на ваших записях. Шкала эмоций на сервере не заведена."),
        "Коллекции" to ("Здесь будет то, что вы собрали" to
            "Плитки коллекций. Коллекций нет ни в базе, ни на сервере."),
    ),
    onSwitchWindows = onSwitchWindows,
    onSearch = onSearch,
    onSettings = onSettings,
    onNeighbourWindow = onNeighbourWindow,
    modifier = modifier,
)

/** Окно 5 «Страница» — `§7`, перерисовано 2026-08-25 под ADR-0018. */
@Composable
fun PageWindow(
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
) = WindowWithTabs(
    window = Window.Page,
    // Порядок из макета: страница открывается на «Подписан», и это её первая вкладка.
    tabs = listOf("Подписан", "Лента", "Коллекции", "Группы"),
    stubs = mapOf(
        "Подписан" to ("Здесь будут каналы и сообщества" to
            "На них подписываются. Подписок на сервере нет."),
        "Лента" to ("Здесь будет то, что вы показываете" to
            "Отложенные записи и положенные группы. Раздача по книге не реализована."),
        "Коллекции" to ("Здесь будет собранное вами" to
            "Медиа, сообщения и каталог своего. Коллекций на сервере нет."),
        "Группы" to ("Здесь будут группы, где вы состоите" to
            "Личные группы уже работают, но пока открываются из окна 1: " +
            "каталог окна 2 и эта вкладка ещё не связаны с данными."),
    ),
    onSwitchWindows = onSwitchWindows,
    onSearch = onSearch,
    onSettings = onSettings,
    onNeighbourWindow = onNeighbourWindow,
    modifier = modifier,
)

/**
 * Общий вид окна, у которого пока нет содержимого.
 *
 * Вкладка запоминается на время жизни окна: «единая сессия» из `§1` требует, чтобы
 * окно возвращалось туда, где его оставили.
 */
@Composable
private fun WindowWithTabs(
    window: Window,
    tabs: List<String>,
    stubs: Map<String, Pair<String, String>>,
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onNeighbourWindow: (InSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember(window) { mutableStateOf(tabs.first()) }
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
    ) {
        val (willWhat, thanHolds) = stubs.getValue(selected)
        TabStub(willWhat, thanHolds)
    }
}
