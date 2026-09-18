package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.SectionGlyph
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Полоса разделов над списком — исполнения **В** (ярлычками) и **Г** (словами) из
 * `разделы.md`.
 *
 * **Полоса — фильтр, а не переход.** Вкладки остаются на месте, меняется только список
 * ниже; возврат — чип «Всё». Кнопки «добавить раздел» здесь нет: там переключают, а
 * заводят разделы в пункте «Разделы» кнопки «Вид».
 *
 * Ярлычками — значок в чипе без слова; у «Всё» и у раздела без значка слово остаётся:
 * пустой чип неотличим от сломанного.
 */
@Composable
fun SectionsRow(
    tabs: List<SectionTab>,
    chosen: String,
    icons: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    ProvidePlace(TextPlace.TABS) {
        FlowRow(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.functional)
                .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            for (tab in tabs) {
                val glyph = icons && tab.icon != 0
                Chip(
                    label = if (glyph) "" else tab.name,
                    kind = if (tab.id == chosen) ChipKind.Selected else ChipKind.Neutral,
                    onClick = { onPick(tab.id) },
                    leading = if (glyph) {
                        { color -> SectionGlyph(index = tab.icon, size = 18.dp, color = color) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/**
 * Плитка ярлычков — исполнение **А**: «Всё» первым, разделы, «Добавить» последним.
 *
 * Нажатие уводит ВНУТРЬ раздела (это не фильтр, а переход): вкладки заменяются на
 * «назад» и название — это делает экран книги, здесь только плитка.
 *
 * Зелёная цифра в углу — сколько внутри (`разделы.md`, «Две цифры»). Янтарной пока нет:
 * непрочитанное у контактов не считается.
 */
@Composable
fun SectionsTiles(
    tabs: List<SectionTab>,
    countOf: (String) -> Int,
    onOpen: (String) -> Unit,
    onAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val words = Tima.words.book
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(TimaSpacing.about4),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        for (tab in tabs) {
            Tile(
                title = tab.name,
                icon = tab.icon,
                count = if (tab.id.isEmpty() && tab.name == words.everyone) null else countOf(tab.id),
                onClick = { onOpen(tab.id) },
            )
        }
        if (onAdd != null) {
            // Короткое слово: в клетке 96 точек «Добавить раздел» режется на полуслове.
            Tile(title = Tima.words.wizard.add, icon = 0, count = null, onClick = onAdd, plus = true)
        }
    }
}

@Composable
private fun Tile(title: String, icon: Int, count: Int?, onClick: () -> Unit, plus: Boolean = false) {
    val colors = Tima.colors
    Column(
        modifier = Modifier
            .size(width = 96.dp, height = 88.dp)
            .background(colors.quiet, RoundedCornerShape(TimaShapes.square))
            .clickable(onClick = onClick)
            .padding(TimaSpacing.about2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (plus) {
                Caption("+", fontSize = TimaType.sz2, weight = FontWeight.Bold)
            } else if (icon != 0) {
                SectionGlyph(index = icon, size = 32.dp)
            } else {
                // Раздел без значка — первая буква имени, как у аватара без картинки.
                Caption(title.take(1).uppercase(), fontSize = TimaType.sz2, weight = FontWeight.Bold)
            }
            if (count != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(colors.navigation, RoundedCornerShape(999.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Caption(count.toString(), fontSize = TimaType.sz6, weight = FontWeight.Bold, color = colors.onAccent)
                }
            }
        }
        Caption(title, fontSize = TimaType.sz6, weight = FontWeight.Bold, lineOne = true)
    }
}
