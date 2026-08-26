package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tima

/**
 * Настройки — подокно со списком разделов.
 *
 * **Список, а не вкладки.** Так решено макетом: `doc/Layout-UI-light/пк/настройки.html`,
 * «Список разделов — в колонке, содержимое раздела — в главной области. Это тот же
 * список, что на телефоне, включая текущие значения справа». Ряд вкладок здесь не влез бы
 * ни при каком раскладе: пунктов одиннадцать, а вкладок на телефоне помещается три.
 *
 * **Текущее значение стоит в строке.** «светлая», «русский», «2 устройства» видно, не
 * заходя внутрь, — и это не украшение: половина заходов в настройки заканчивается тем,
 * что человек посмотрел и вышел.
 *
 * **Одна дверь вместо трёх.** До 2026-08-26 «⚙» в шапке вело прямо в список устройств.
 * Пока раздел был один, это выглядело разумно, и было верно ровно до второго.
 *
 * Содержимое выбранного пункта передаётся слотом: оболочка не знает, что устройства живут
 * в `feature-auth`. Зависимостей на другие feature у неё нет и не будет — это проверяется
 * архитектурным тестом.
 *
 * @param opened какой пункт открыт; `null` — виден сам список
 * @param value текущее значение пункта для правого края строки
 */
@Composable
fun SettingsScreen(
    opened: SettingsItem?,
    onOpen: (SettingsItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    value: (SettingsItem) -> String = { "" },
    content: @Composable (SettingsItem) -> Unit,
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        // Шапка одна на подокно, и заголовок в ней — имя открытого пункта. Человеку
        // нужно знать, где он, а «Настройки» этого уже не отвечают, когда он внутри.
        SubwindowHeader(
            title = opened?.title ?: "Настройки",
            // «Назад» из пункта возвращает к списку, а не из настроек целиком: выйти
            // наружу одним нажатием из глубины — это потерять место, куда шёл.
            onBack = onBack,
        )

        if (opened != null) {
            Box(Modifier.fillMaxSize()) { content(opened) }
            return@Column
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            for (group in SettingsGroup.entries) {
                SectionTitle(group.title)
                for (item in SettingsItem.entries.filter { it.group == group }) {
                    ListLine(
                        onClick = { onOpen(item) },
                        left = { Name(item.glyph) },
                        right = value(item).takeIf { it.isNotBlank() }?.let { { Secondary(it) } },
                        middle = { Name(item.title) },
                    )
                }
            }
        }
    }
}

/** Группы разделов — те же четыре, что в макете. */
enum class SettingsGroup(val title: String) {
    ACCOUNT("Аккаунт"),
    APPLICATION("Приложение"),
    BLOGGER("Блогер"),
    HELP("Помощь"),
}

/**
 * Пункт настроек.
 *
 * Перечень, а не строки у вызывающего: имя пункта — одновременно ключ навигации и надпись
 * на экране, и, разъехавшись в двух местах, они дают пункт, который не открывается.
 *
 * Порядок объявления — порядок на экране. Он взят из макета и держится на нём, а не на
 * вкусе: «Профиль» первым потому, что чаще всего заходят посмотреть на себя.
 */
enum class SettingsItem(val group: SettingsGroup, val title: String, val glyph: String) {
    PROFILE(SettingsGroup.ACCOUNT, "Профиль", "👤"),

    /**
     * Фраза и устройства — один пункт, а не два.
     *
     * Так в макете, и это не экономия строки: фраза и есть то, чем заводят новое
     * устройство. Разведённые по разным пунктам, они выглядят как несвязанные вещи, и
     * человек, потерявший телефон, ищет не там.
     */
    DEVICES(SettingsGroup.ACCOUNT, "Секретная фраза и устройства", "🔑"),
    NOTIFICATIONS(SettingsGroup.ACCOUNT, "Уведомления", "🔔"),

    APPEARANCE(SettingsGroup.APPLICATION, "Оформление", "🎨"),
    LANGUAGE(SettingsGroup.APPLICATION, "Язык", "🌐"),
    PRIVACY(SettingsGroup.APPLICATION, "Приватность и блокировки", "🔒"),
    STORAGE(SettingsGroup.APPLICATION, "Память и трафик", "💾"),

    BLOGGER(SettingsGroup.BLOGGER, "Окна блогера", "📈"),

    QUESTIONS(SettingsGroup.HELP, "Частые вопросы", "❓"),
    PROBLEM(SettingsGroup.HELP, "Сообщить о проблеме", "🐞"),

    /**
     * Обновление — пункт заказчика от 2026-08-26.
     *
     * **В макете на его месте «О приложении» с версией справа.** Разделение сделано
     * потому, что это разные вопросы: «что у меня стоит» — справка, «есть ли новее» —
     * действие, и действию нужна кнопка. Расхождение с макетом названо здесь, чтобы его
     * не обнаружили как ошибку: правится оно решением заказчика, а не молча.
     */
    UPDATE(SettingsGroup.HELP, "Обновление", "⬇"),
    ABOUT(SettingsGroup.HELP, "О приложении", "ℹ"),
}
