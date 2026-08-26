package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Name
import io.tima.core.ui.Layout
import io.tima.core.ui.Counter
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Tima

/**
 * Рейка окон — то, чем на широких форматах заменяется подокно «Переключение окон».
 *
 * **Переключателя там нет вовсе** (`интерфейс.md §1 «Три формата»`): полоса слева
 * всегда на виду, и открывать поверх неё панель значило бы прятать список за списком.
 *
 * Разница планшета и ПК ровно одна: на планшете только знаки, на ПК знаки с подписями.
 * Решает это [Раскладка], а не отдельный флаг — экран про устройство не спрашивает.
 *
 * Счётчики непрочитанного переезжают сюда: это то самое «единственное место, где они
 * видны разом», просто на широком формате оно всегда открыто.
 */
@Composable
fun Rail(
    layout: Layout,
    current: Window,
    onSelect: (Window) -> Unit,
    modifier: Modifier = Modifier,
    counters: Map<Window, Int> = emptyMap(),
    onSettings: (() -> Unit)? = null,
) {
    val colors = Tima.colors
    val withCaptions = layout.railCaption
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.functional)
            .padding(vertical = TimaSpacing.about3, horizontal = TimaSpacing.about2),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        for (window in Window.entries) {
            Item(
                window = window,
                selected = window == current,
                howMany = counters[window] ?: 0,
                withCaption = withCaptions,
                onClick = { onSelect(window) },
            )
        }

        // Настройки внизу — так в макете, и это не только вид: то, чем пользуются
        // редко, не должно стоять на пути к тому, чем пользуются постоянно.
        Spacer(Modifier.weight(1f))
        if (onSettings != null) {
            Item(
                glyph = "⚙",
                caption = "Настройки",
                selected = false,
                howMany = 0,
                withCaption = withCaptions,
                onClick = onSettings,
            )
        }
    }
}

@Composable
private fun Item(
    window: Window,
    selected: Boolean,
    howMany: Int,
    withCaption: Boolean,
    onClick: () -> Unit,
) = Item(window.glyph, window.full, selected, howMany, withCaption, onClick)

@Composable
private fun Item(
    glyph: String,
    caption: String,
    selected: Boolean,
    howMany: Int,
    withCaption: Boolean,
    onClick: () -> Unit,
) {
    val colors = Tima.colors
    Row(
        modifier = Modifier
            .then(if (withCaption) Modifier.fillMaxWidth() else Modifier)
            .background(
                if (selected) colors.navigation else colors.functional,
                RoundedCornerShape(TimaShapes.smallSquare),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) { Name(glyph) }
        if (withCaption) {
            Name(caption, modifier = Modifier.weight(1f))
        }
        if (howMany > 0) Counter(howMany)
    }
}
