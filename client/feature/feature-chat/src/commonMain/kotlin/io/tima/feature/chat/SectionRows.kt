package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.ui.Caption
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.SectionGlyph
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/*
 * Разделы на экране — три композиции по `doc/Layout-UI-light/телефон/разделы.html` и
 * `стиль.css` (правила `.чип-разд`, `.чип-ярлык`, `.ярлык`, `.счёт`, `.счёт-об`, `.разд`).
 *
 * ── ДВЕ ЦИФРЫ У КАЖДОГО РАЗДЕЛА (`разделы.md`) ──────────────────────────────
 *
 *   зелёная  — сколько внутри лежит         `.счёт-об`, цвет «подтверждено», белая цифра
 *   янтарная — сколько объектов с новым      `.счёт`,    цвет «активность», тёмная цифра
 *
 * В плитке они разведены по углам значка: янтарная вверх-вправо, зелёная вниз-вправо. На
 * полосе зелёной нет вовсе — там переключают, и важно «где новое», а не «сколько всего».
 *
 * ── ВЫБОР ПОДЧЁРКНУТ, НЕ ЗАЛИТ ──────────────────────────────────────────────
 *
 * Заливка занята текущей вкладкой, и вкладка главнее: она про то, откуда содержимое, а
 * раздел — про то, о чём оно. Первая редакция полосы (утро 2026-09-18) заливала выбранный
 * чип салатовым — это было против макета и сливалось с рядом вкладок над ним.
 *
 * ── БЕЗ ЗНАЧКА — ПЕРВАЯ БУКВА ───────────────────────────────────────────────
 *
 * Решение заказчика 2026-09-18: разделу без значка подставляется первая буква имени, как
 * аватару без картинки. Пустой квадрат читался бы как сломанный.
 */

/** Ключ полосы, у которого нет своего раздела: «Всё». */
private const val ALL_KEY = ""

/**
 * Полоса разделов — исполнения **В** (ярлычками) и **Г** (словами).
 *
 * **Тянется пальцем или мышью, не переносится** (`overflow-x: auto`, `cursor: grab`):
 * полоса — одна строка, разделы за краем вытягивают движением. Полосы прокрутки нет.
 *
 * @param newIn сколько объектов с новым в разделе — янтарная цифра. Ноль — цифры нет.
 */
@Composable
fun SectionsRow(
    tabs: List<SectionTab>,
    chosen: String,
    icons: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    newIn: (String) -> Int = { 0 },
) {
    val colors = Tima.colors
    ProvidePlace(TextPlace.TABS) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.functional)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = TimaSpacing.about3),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (tab in tabs) {
                val selected = tab.id == chosen
                val fresh = newIn(tab.id)
                if (icons) IconChip(tab, selected, fresh) { onPick(tab.id) }
                else WordChip(tab, selected, fresh) { onPick(tab.id) }
            }
        }
    }
}

/** `.чип-разд`: слово, рядом янтарный счёт; выбранный подчёркнут. */
@Composable
private fun WordChip(tab: SectionTab, selected: Boolean, fresh: Int, onClick: () -> Unit) {
    val colors = Tima.colors
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .underline(selected)
            .padding(start = 11.dp, end = 11.dp, top = 9.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(
            tab.name,
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
            color = if (selected) colors.text else colors.text3,
        )
        if (fresh > 0) Badge(fresh, amber = true, small = true)
    }
}

/**
 * `.чип-ярлык`: значок над коротким словом, янтарный счёт в правом верхнем углу.
 *
 * Слово остаётся и под значком — в макете так: `.подпись-чипа` 9.5px. Значок без слова
 * читается только у тех, кто его сам выбирал.
 */
@Composable
private fun IconChip(tab: SectionTab, selected: Boolean, fresh: Int, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .underline(selected)
            .widthIn(min = 54.dp)
            .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 4.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                Mark(tab, size = 18.dp, color = if (selected) colors.text else colors.text3)
            }
            Caption(
                tab.name,
                fontSize = 9.5.sp,
                weight = FontWeight.Bold,
                color = if (selected) colors.text else colors.text3,
                lineOne = true,
            )
        }
        if (fresh > 0) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-2).dp)) {
                Badge(fresh, amber = true, small = true)
            }
        }
    }
}

/** Подчёркивание выбранного — 2 точки салатовым снизу; невыбранный — прозрачным, чтобы не дёргать высоту. */
@Composable
private fun Modifier.underline(selected: Boolean): Modifier {
    val colors = Tima.colors
    return this.then(
        Modifier.drawUnderline(if (selected) colors.navigation else Color.Transparent),
    )
}

private fun Modifier.drawUnderline(color: Color): Modifier = drawBehind {
    val thickness = 2.dp.toPx()
    drawRect(
        color = color,
        topLeft = Offset(0f, this.size.height - thickness),
        size = Size(this.size.width, thickness),
    )
}

/**
 * Плитка ярлычков — исполнение **А**. `.плитка`: четыре в ряд, зазор 12/10, поле 14.
 *
 * «Всё» — салатовый квадрат с белым знаком, первым. «Добавить» — пунктирная рамка и плюс,
 * последним. Пустой раздел бледнее (`opacity 0.45`): он есть, но в нём ничего.
 *
 * @param size размер ярлычков из «Вида»: меняет ширину клетки, а с ней число в ряду.
 */
@Composable
fun SectionsTiles(
    tabs: List<SectionTab>,
    countOf: (String) -> Int,
    onOpen: (String) -> Unit,
    onAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
    newIn: (String) -> Int = { 0 },
    size: TileSize = TileSize.Normal,
) {
    val words = Tima.words.book
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (tab in tabs) {
            val all = tab.id == ALL_SECTION
            val total = countOf(tab.id)
            Tile(
                tab = tab,
                total = if (all) null else total,
                fresh = newIn(tab.id),
                all = all,
                empty = !all && total == 0,
                side = size.side,
                onClick = { onOpen(tab.id) },
            )
        }
        if (onAdd != null) AddTile(words.addSection, size.side, onAdd)
    }
}

/** Размер ярлычков — пункт «Вида». Сторона клетки; при 360 точках 72 — четыре в ряд. */
enum class TileSize(val side: Dp, val wire: String) {
    Small(56.dp, "small"),
    Normal(72.dp, "normal"),
    Large(96.dp, "large"),
    ;

    companion object {
        fun fromWire(wire: String?): TileSize = entries.firstOrNull { it.wire == wire } ?: Normal
    }
}

@Composable
private fun Tile(
    tab: SectionTab,
    total: Int?,
    fresh: Int,
    all: Boolean,
    empty: Boolean,
    side: Dp,
    onClick: () -> Unit,
) {
    val colors = Tima.colors
    Column(
        modifier = Modifier.width(side).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(side)
                .alpha(if (empty) 0.45f else 1f)
                .background(
                    if (all) colors.navigation else colors.quiet,
                    RoundedCornerShape(TimaShapes.square),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Mark(tab, size = side * 0.45f, color = if (all) colors.onAccent else colors.text)
            // Два счёта по углам, ВНУТРИ квадрата: янтарный — новое, вверх; зелёный —
            // всего, вниз. В макете они наполовину вылезали за угол; заказчик 2026-09-18:
            // «переместить внутрь пузыря, увеличить в полтора раза» — на телефоне мелкий
            // счёт на углу читался хуже подписи.
            if (fresh > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) { Badge(fresh, amber = true, large = true) }
            }
            if (total != null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(3.dp)) { Badge(total, amber = false, large = true) }
            }
        }
        Caption(tab.name, fontSize = 10.5.sp, weight = FontWeight.Bold, lineOne = true)
    }
}

@Composable
private fun AddTile(label: String, side: Dp, onClick: () -> Unit) {
    val colors = Tima.colors
    Column(
        modifier = Modifier.width(side).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(side)
                .border(1.dp, colors.text3, RoundedCornerShape(TimaShapes.square)),
            contentAlignment = Alignment.Center,
        ) {
            Caption("+", fontSize = 26.sp, weight = FontWeight.Light, color = colors.text3)
        }
        Caption(label, fontSize = 10.5.sp, weight = FontWeight.Bold, color = colors.text3, lineOne = true)
    }
}

/**
 * Знак раздела: рисованный значок, а без него — первая буква имени. «Всё» — звёздочка
 * `✦` из макета, у него имени нет, есть смысл.
 */
@Composable
fun Mark(tab: SectionTab, size: Dp, color: Color) {
    when {
        tab.icon != 0 -> SectionGlyph(index = tab.icon, size = size, color = color)
        tab.id == ALL_KEY || tab.id == ALL_SECTION ->
            Caption("✦", fontSize = (size.value * 0.9f).sp, weight = FontWeight.Bold, color = color)
        else -> Caption(
            tab.name.take(1).uppercase(),
            fontSize = (size.value * 0.75f).sp,
            weight = FontWeight.Bold,
            color = color,
        )
    }
}

/**
 * Счёт в пузыре. Янтарный (`.счёт`) — новое, тёмная цифра; зелёный (`.счёт-об`) — всего,
 * белая цифра и белая обводка 2, чтобы не сливаться с углом значка.
 */
@Composable
fun Badge(count: Int, amber: Boolean, small: Boolean = false, large: Boolean = false) {
    val colors = Tima.colors
    // Крупный — в полтора раза от обычного: для плитки, где счёт стоит внутри квадрата.
    val height = when {
        small -> 16.dp
        large -> 27.dp
        else -> 18.dp
    }
    Box(
        modifier = Modifier
            .then(if (!amber) Modifier.border(2.dp, colors.surface, CircleShape) else Modifier)
            .background(if (amber) colors.activity else colors.confirmed, CircleShape)
            .height(height)
            .widthIn(min = height)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Caption(
            count.toString(),
            fontSize = when {
                small -> 9.5.sp
                large -> 15.sp
                else -> 10.sp
            },
            weight = FontWeight.ExtraBold,
            color = if (amber) colors.onAmber else colors.onAccent,
        )
    }
}
