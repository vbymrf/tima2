package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.AuthorStrips
import io.tima.core.ui.Bubble
import io.tima.core.ui.Button
import io.tima.core.ui.Caption
import io.tima.core.ui.IconButton
import io.tima.core.ui.LocalStripLook
import io.tima.core.ui.Name
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaZones
import io.tima.core.ui.Trouble
import io.tima.core.ui.words

/**
 * «Мой цвет в группе» — решение заказчика 2026-09-19.
 *
 * Сверху образец — мой пузырь глазами остальных: с полосой выбранного цвета, именем и
 * буквой. Ниже сетка 10 × 10 набора **моей** темы: хранится номер оттенка, у собеседника со
 * другой темой это тот же оттенок нужной светлоты.
 *
 * **Занятые** другими участниками номера помечены буквой того, кто их взял, и **не
 * нажимаются**, пока участников не больше 80 % оттенков (правило заказчика; сервер
 * проверяет то же самое и ответит 409, если двое выбрали одновременно). «Сбросить» —
 * автоматический цвет по порядку появления.
 */
@Composable
fun MyColorSheet(
    /** Мой выбранный номер; `null` — автоматический. */
    mine: Int?,
    /** Занятые номера → буква того, кто взял. */
    taken: Map<Int, String>,
    /** Участников в группе — от этого зависит, запрещены ли совпадения. */
    members: Int,
    /** Имя и буква автора для образца. */
    author: String,
    letter: String,
    onPick: (Int) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    trouble: String? = null,
) {
    val colors = Tima.colors
    val words = Tima.words.chat
    val dark = LocalStripLook.current.dark
    val sharedMay = members > SHARED_FROM
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.text.copy(alpha = 0.45f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TimaZones.zone1)
                .background(colors.surface)
                .clickable(enabled = false) {},
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.functional)
                    .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { ProvidePlace(TextPlace.HEADERS) { Name(words.myColor) } }
                IconButton(glyph = "✕", onClick = onClose)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                trouble?.let { Trouble(it, Modifier.padding(TimaSpacing.about4)) }
                SectionTitle(Tima.words.book.lookSample)
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4)
                        .clip(RoundedCornerShape(TimaShapes.square)).background(colors.functional)
                        .padding(TimaSpacing.about3).padding(top = TimaSpacing.about3),
                ) {
                    ProvidePlace(TextPlace.MESSAGES) {
                        Bubble(
                            my = false,
                            author = author,
                            avatar = letter,
                            strip = mine?.let { AuthorStrips.hue(it, dark) } ?: colors.navigation,
                            bottom = { Tertiary("12:40", lineOne = true) },
                        ) { Caption(words.myColorAbout, fontSize = TimaType.sz4) }
                    }
                }
                Tertiary(
                    if (mine == null) words.myColorAuto else "№$mine",
                    modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
                    lineOne = true,
                )

                // Сетка: десять в ряд, сто клеток. №0 показан, но это оттенок салатового —
                // владельца; его тоже можно взять, запрета нет.
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (row in 0 until 10) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (col in 0 until 10) {
                                val index = row * 10 + col
                                val holder = taken[index]
                                val blocked = holder != null && !sharedMay
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(30.dp)
                                        .alpha(if (blocked) 0.45f else 1f)
                                        .background(AuthorStrips.hue(index, dark), RoundedCornerShape(6.dp))
                                        .then(
                                            if (index == mine) Modifier.border(3.dp, colors.text, RoundedCornerShape(6.dp)) else Modifier,
                                        )
                                        .clickable(enabled = !busy && !blocked) { onPick(index) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (holder != null) {
                                        // Буква того, кто занял: видно, чей цвет, не открывая состав.
                                        Caption(holder, fontSize = TimaType.sz6, weight = FontWeight.ExtraBold, color = colors.onAccent)
                                    }
                                }
                            }
                        }
                    }
                }
                if (!sharedMay && taken.isNotEmpty()) {
                    Tertiary(
                        "${words.myColorTaken}: ${taken.size}",
                        modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
                        lineOne = true,
                    )
                }
                Box(Modifier.fillMaxWidth().padding(TimaSpacing.about4), contentAlignment = Alignment.CenterEnd) {
                    Button(label = words.myColorReset, onClick = onReset)
                }
            }
        }
    }
}

/** С какого числа участников совпадения разрешены: 80 % от 99 оттенков — то же число, что у сервера. */
private const val SHARED_FROM = 79
