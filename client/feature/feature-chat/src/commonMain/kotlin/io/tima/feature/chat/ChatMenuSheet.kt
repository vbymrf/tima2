package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tima.core.ui.CheckMark
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.RadioMark
import io.tima.core.ui.SectionGlyph
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.words
import io.tima.domain.chat.Section

/**
 * Меню «•••» групповой переписки — подокно снизу, тем же приёмом, что «Вид».
 *
 * Состав — решение заказчика 2026-09-18: «Перенести в раздел» первым; «Доступность» и
 * «Участники» переехали сюда из шапки — там им не место, это про группу, а не про
 * переписку (ПЛАН-ЧАТА Ч2).
 *
 * «Перенести в раздел» разворачивается на месте списком разделов набора сообществ:
 * выбор — один из, отмечен кружком; «Общий» первым. Второе подокно поверх первого было
 * бы лестницей, с которой на телефоне не возвращаются.
 */
@Composable
fun ChatMenuSheet(
    sections: List<Section>,
    currentSection: String,
    onMoveTo: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Показ доступности реплик: `null` — переписка личная, пункта нет. */
    circlesShown: Boolean? = null,
    onCircles: ((Boolean) -> Unit)? = null,
    onMembers: (() -> Unit)? = null,
    /** «Мой цвет в группе» — подокно выбора оттенка полосы (сервер 0053). `null` — пункта нет. */
    onMyColor: (() -> Unit)? = null,
    /**
     * Видеозвонок — ЗВ6, решение заказчика 2026-09-20.
     *
     * **Здесь, а не в шапке.** В макете `03-personal-chat.md` в шапке две кнопки, 📞 и
     * 📹; заказчик оставил в шапке одну, а видео увёл сюда. Расхождение намеренное:
     * шапка переписки — место для того, что делают часто, а видеозвонок начинают реже,
     * чем голосовой.
     *
     * `null` — пункта нет: группа (групповых звонков нет вовсе) или платформа без
     * движка.
     */
    onVideoCall: (() -> Unit)? = null,
    /** Групповая переписка — заголовок «Настройка группы», иначе «Настройка переписки». */
    group: Boolean = circlesShown != null,
) {
    val colors = Tima.colors
    val words = Tima.words.chat
    val book = Tima.words.book
    var moving by remember { mutableStateOf(false) }

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
                .background(colors.surface)
                .clickable(enabled = false) {},
        ) {
            // Шапка — на подложке, как первая строка подокна переходов: заголовок отделён
            // от пунктов цветом, а не только кеглем (заказчик 2026-09-18).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.functional)
                    .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    ProvidePlace(TextPlace.HEADERS) { Name(if (group) words.groupSettings else words.chatSettings) }
                }
                IconButton(glyph = "✕", onClick = onClose)
            }

            val currentName = sections.firstOrNull { it.id == currentSection }?.name ?: book.commonSection
            ListLine(
                onClick = { moving = !moving },
                middle = {
                    Column {
                        Name(words.moveToSection)
                        Tertiary(words.inSection(currentName), lineOne = true)
                    }
                },
                right = { Tertiary(if (moving) "▾" else "›", lineOne = true) },
            )
            if (moving) {
                ListLine(
                    onClick = { onMoveTo(""); onClose() },
                    middle = { Name(book.commonSection) },
                    right = { RadioMark(currentSection.isEmpty()) },
                )
                for (section in sections) {
                    ListLine(
                        onClick = { onMoveTo(section.id); onClose() },
                        left = { SectionGlyph(index = section.icon, size = 20.dp) },
                        middle = { Name(section.name) },
                        right = { RadioMark(currentSection == section.id) },
                    )
                }
            }

            // Первым пунктом: это действие, а не настройка, и меню открывают ради
            // действия чаще, чем ради галочки.
            if (onVideoCall != null) {
                ListLine(
                    onClick = { onVideoCall(); onClose() },
                    middle = { Name(words.videoCall) },
                    right = { Tertiary("›", lineOne = true) },
                )
            }

            if (circlesShown != null && onCircles != null) {
                ListLine(
                    onClick = { onCircles(!circlesShown) },
                    middle = { Name(words.access) },
                    right = { CheckMark(circlesShown) },
                )
            }
            if (onMembers != null) {
                ListLine(
                    onClick = { onMembers(); onClose() },
                    middle = { Name(words.members) },
                    right = { Tertiary("›", lineOne = true) },
                )
            }
            if (onMyColor != null) {
                ListLine(
                    onClick = { onMyColor(); onClose() },
                    middle = {
                        Column {
                            Name(words.myColor)
                            Tertiary(words.myColorAbout, lineOne = true)
                        }
                    },
                    right = { Tertiary("›", lineOne = true) },
                )
            }
        }
    }
}
