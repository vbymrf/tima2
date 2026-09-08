package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.words
import io.tima.core.ui.ListLine
import io.tima.core.ui.SettingsGroup
import io.tima.core.ui.SettingsItem
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
    val words = Tima.words.settings2
    Column(modifier.fillMaxSize().background(colors.surface)) {
        // Шапка одна на подокно, и заголовок в ней — имя открытого пункта. Человеку
        // нужно знать, где он, а «Настройки» этого уже не отвечают, когда он внутри.
        SubwindowHeader(
            title = opened?.let { words.item(it) } ?: words.settings,
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
                SectionTitle(words.group(group))
                for (item in SettingsItem.entries.filter { it.group == group }) {
                    ListLine(
                        onClick = { onOpen(item) },
                        left = { Name(item.glyph) },
                        right = value(item).takeIf { it.isNotBlank() }?.let { { Secondary(it) } },
                        middle = { Name(words.item(item)) },
                    )
                }
            }
        }
    }
}
