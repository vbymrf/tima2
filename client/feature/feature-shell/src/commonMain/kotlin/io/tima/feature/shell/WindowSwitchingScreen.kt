package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Secondary
import io.tima.core.ui.Name
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Counter
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima

/**
 * Подокно «Переключение окон» — единственный видимый способ сменить окно на телефоне.
 *
 * Три решения макета, которые здесь важнее вида:
 *
 * 1. **Панель выезжает снизу, а не разворачивается от логотипа.** До низа экрана палец
 *    дотягивается, до верхнего левого угла — нет. Открывает её при этом верхний левый
 *    угол, и это не противоречие: нажимают редко, а выбирают из списка часто.
 * 2. **У каждого окна вторая строка о том, что внутри.** «Свободное общение» ничего не
 *    говорит человеку, который туда не ходил, — а решение зайти принимается здесь.
 * 3. **Это единственное место, где счётчики всех окон видны разом.** Панели вкладок в
 *    приложении нет, собрать их больше негде (`интерфейс.md §1`).
 *
 * Под панелью остаётся то окно, где человек был: он не ушёл никуда, а приподнял
 * список поверх. Поэтому фон затемняется, а не подменяется.
 */
@Composable
fun WindowSwitchingScreen(
    current: Window,
    name: String,
    alias: String,
    onSelect: (Window) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Непрочитанное по окнам. Нет записи — нет и числа. */
    counters: Map<Window, Int> = emptyMap(),
    onSettings: (() -> Unit)? = null,
    /**
     * «Изменить» в шапке — вход в профиль (ПЛАН-КОНТАКТОВ.md, Д8).
     *
     * Здесь, потому что своего экрана учётной записи у нас нет, а окно 5 — про
     * содержимое, а не про запись о человеке. Это подокно открывается логотипом с
     * любого экрана и потому ближе всего к «шапке приложения».
     */
    onProfile: (() -> Unit)? = null,
    /**
     * Аккаунты этого устройства: пара «идентификатор — как называть» (Д11).
     *
     * Один аккаунт — списка нет вовсе: строка «переключиться» там, где переключаться
     * не на что, обещает несуществующее.
     */
    accounts: List<Pair<String, String>> = emptyList(),
    currentAccount: String = "",
    onAccount: (String) -> Unit = {},
) {
    val colors = Tima.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            // Затемнение поверх окна, из которого пришли: человек не ушёл никуда, а
            // приподнял список над тем, где был.
            .background(colors.text.copy(alpha = DIM))
            // Касание вне панели закрывает — то же, что «✕». Оба входа обязаны быть:
            // касание вне угадывают не все, а «✕» ищут глазами.
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = TimaShapes.radius, topEnd = TimaShapes.radius))
                .background(colors.surface)
                // Нажатие по самой панели не должно закрывать её вместе с фоном.
                .clickable(enabled = false, onClick = {}),
        ) {
            Header(name, alias, onClose, onProfile)

            if (accounts.size > 1) {
                SectionTitle("Аккаунты")
                accounts.forEach { (userId, label) ->
                    ListLine(
                        onClick = { if (userId != currentAccount) onAccount(userId) },
                        left = { Glyph(if (userId == currentAccount) "●" else "○") },
                        middle = { Name(label) },
                    )
                }
            }

            for (window in Window.entries) {
                Item(
                    window = window,
                    current = window == current,
                    howMany = counters[window] ?: 0,
                    onClick = { onSelect(window) },
                )
            }

            if (onSettings != null) {
                ListLine(
                    onClick = onSettings,
                    left = { Glyph("⚙") },
                    middle = { Name("Настройки, помощь, баги") },
                )
            }

            // Блогерские окна включаются в настройках; пока их нет, заголовок раздела
            // тоже не рисуем: пустой раздел обещает то, чего не существует.
        }
    }
}

/**
 * Шапка: кто я и вход в профиль.
 *
 * Заголовка «Окна» здесь больше нет — он повторял то, что видно из списка под ним
 * (решение 2026-09-05). Вместо него имя и ник: это единственное место, где человек
 * видит свою учётную запись.
 */
@Composable
private fun Header(
    name: String,
    alias: String,
    onClose: () -> Unit,
    onProfile: (() -> Unit)?,
) {
    val colors = Tima.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph("Т")
        Column(modifier = Modifier.weight(1f)) {
            // Кто я — здесь, а не в шапке окна: имя нужно тому, кто выбирает, от чьего
            // лица он сейчас в приложении, а не тому, кто читает переписку.
            Name(name)
            Tertiary(alias, lineOne = true)
        }
        // «Изменить» — первый из двух входов в профиль; второй в настройках.
        if (onProfile != null) IconButton(glyph = "✎", onClick = onProfile)
        IconButton(glyph = "✕", onClick = onClose)
    }
}

@Composable
private fun Item(window: Window, current: Boolean, howMany: Int, onClick: () -> Unit) {
    ListLine(
        onClick = onClick,
        left = { Glyph(window.glyph) },
        right = { if (howMany > 0) Counter(howMany) },
        middle = {
            Column {
                Name(window.full)
                Secondary(
                    // Текущее окно называет себя текущим словом, а не только цветом:
                    // цвет здесь один на всё приложение и уже занят навигацией.
                    if (current) "${window.about} · вы здесь" else window.about,
                    lineOne = true,
                )
            }
        },
    )
}

@Composable
private fun Glyph(glyph: String) {
    val colors = Tima.colors
    Box(
        modifier = Modifier
            .background(colors.softAccent, RoundedCornerShape(TimaShapes.smallSquare))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Name(glyph) }
}

/** Насколько затемняется окно под панелью. Меньше — панель «висит», больше — окно исчезает. */
private const val DIM = 0.32f
