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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
        // Значку отведена своя ширина, а не «сколько занял». Во-первых, подписи от
        // этого встают в колонку; во-вторых, ширина строки становится считаемой —
        // на ней держится RAIL_AROUND_CAPTION и проверка, что подпись влезает.
        Box(Modifier.width(RAIL_GLYPH), contentAlignment = Alignment.Center) { Name(glyph) }
        if (withCaption) {
            Name(caption, modifier = Modifier.weight(1f))
        }
        if (howMany > 0) Counter(howMany)
    }
}

/** Ширина колонки значка в строке рейки. */
internal val RAIL_GLYPH: Dp = 22.dp

/**
 * Место под счётчик. `Counter` начинается с 22 точек и растёт до «99+»; резервируем
 * широкий случай, иначе подпись помещалась бы ровно до первого двузначного числа.
 */
internal val RAIL_COUNTER: Dp = 30.dp

/**
 * Всё, что в строке рейки занимает ширину **помимо самой подписи**.
 *
 * Сумма, а не выбранное число: поля рейки, поля строки, колонка значка, счётчик и два
 * зазора между ними. Написано сложением ровно затем, чтобы правка любого отступа
 * пересчитала его сама, а проверка «подпись влезает» осталась верной.
 *
 * На этом держится `FormatTima.CAPTION_RAIL`: ширина рейки обязана быть не меньше, чем
 * это плюс самая длинная подпись окна. Проверяет `RailWidthTest`.
 */
internal val RAIL_AROUND_CAPTION: Dp =
    TimaSpacing.about2 * 2 + // поля самой рейки
        TimaSpacing.about3 * 2 + // поля строки
        RAIL_GLYPH + TimaSpacing.about2 + // значок и зазор после него
        RAIL_COUNTER + TimaSpacing.about2 // счётчик и зазор перед ним
