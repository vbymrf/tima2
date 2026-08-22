package io.tima.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/**
 * Строка списка: **линия снизу, без карточки и без фона**.
 *
 * Правило разделения из макета, и оно короткое: **рамка** — у сообщения переписки,
 * **линия** — между записями списка и ленты, **заливка** — только там, где цвет что-то
 * означает. Фон содержимого белый, поэтому карточек на подложке больше нет, и
 * разделять приходится линией.
 *
 * Прошлая редакция делала обратное — карточка на серой подложке, — и тогда список из
 * двадцати чатов был двадцатью карточками, каждая из которых просила внимания.
 */
@Composable
fun СтрокаСписка(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /**
     * Слева: обычно [Аватар]. Именно квадрат — «кто-то или что-то».
     */
    слева: (@Composable () -> Unit)? = null,
    /**
     * Справа: время, [Счётчик], замок. В макете это `.мета` — столбик, прижатый к
     * правому краю, и он не сжимается: время не должно уезжать от чужого длинного имени.
     */
    справа: (@Composable () -> Unit)? = null,
    /** Середина: имя и превью. Занимает остаток и обрезается первой. */
    середина: @Composable () -> Unit,
) {
    val цвета = Тима.цвета
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // Линия рисуется по нижней границе самой строки, а не отдельным элементом
            // между строками: разделитель-элемент в списке приходится вставлять
            // вызывающему, и он однажды забудет — либо поставит лишний в конце.
            .drawBehind {
                val толщина = 1.dp.toPx()
                drawLine(
                    color = цвета.линия,
                    start = Offset(0f, size.height - толщина / 2),
                    end = Offset(size.width, size.height - толщина / 2),
                    strokeWidth = толщина,
                )
            }
            .padding(horizontal = TimaSpacing.о4, vertical = TimaSpacing.о3),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        слева?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { середина() }
        справа?.let {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) { it() }
        }
    }
}

/**
 * Заголовок раздела списка: `.раздел`.
 *
 * Прописными, разряжённо и тихим цветом: он делит список, а не соревнуется с ним за
 * внимание.
 */
@Composable
fun ЗаголовокРаздела(текст: String, modifier: Modifier = Modifier) = Подпись(
    текст = текст.uppercase(),
    modifier = modifier.padding(
        start = TimaSpacing.о4,
        end = TimaSpacing.о4,
        top = TimaSpacing.о4,
        bottom = TimaSpacing.о2,
    ),
    кегль = TimaType.щ6,
    вес = androidx.compose.ui.text.font.FontWeight.ExtraBold,
    цвет = Тима.цвета.текст3,
)
