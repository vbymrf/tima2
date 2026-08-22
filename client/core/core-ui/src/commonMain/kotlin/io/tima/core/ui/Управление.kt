package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Круглые управляющие элементы: кнопки, чипы, счётчики.
 *
 * Вторая половина правила формы: **круг означает «нажми»**. Счётчик при этом круглый,
 * хотя не нажимается, — и это осознанное исключение из макета: он пилюля, потому что
 * живёт в одном ряду с чипами и кнопками, а квадратный счётчик читался бы аватаром.
 */

/** Толщина обводки у «контурных» кнопок: `.кн.контур` — 2 px. */
private val ОБВОДКА = 2.dp

/**
 * Кнопка. Салатовая заливка — **навигация и действие**: «отправить», «написать»,
 * «назад», «подписаться».
 */
@Composable
fun Кнопка(
    надпись: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    вид: ВидКнопки = ВидКнопки.Действие,
) {
    val цвета = Тима.цвета
    val фон = when (вид) {
        ВидКнопки.Действие -> цвета.навигация
        ВидКнопки.Тихая -> цвета.акцентМягкий
        // Опасное — БЕЗ ЦВЕТА: красного в палитре нет вовсе. Остаются слово,
        // незаполненная кнопка и последнее место в списке.
        ВидКнопки.Опасная -> Color.Transparent
    }
    val надписьЦвет = when (вид) {
        ВидКнопки.Действие -> цвета.наАкценте
        else -> цвета.текст
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 42.dp)
            .background(фон, CircleShape)
            .then(
                if (вид == ВидКнопки.Опасная) {
                    Modifier.border(ОБВОДКА, цвета.текст, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = TimaSpacing.о2),
        contentAlignment = Alignment.Center,
    ) {
        Подпись(надпись, кегль = TimaType.щ5, вес = FontWeight.Bold, цвет = надписьЦвет)
    }
}

/**
 * Виды кнопок из макета.
 *
 * Опасного действия **красным цветом не бывает**: красного в палитре нет. Отличается
 * оно словом, незаполненной кнопкой и местом — последним в списке.
 */
enum class ВидКнопки { Действие, Тихая, Опасная }

/**
 * Кнопка-иконка: круг 36 px. `.икона`.
 *
 * @param живая главное действие — салатовая. Внутри салатовой плашки шапки такая
 *   кнопка становится белой: салатовое на салатовом не видно, и это решено в
 *   [ШапкаОкна], а не здесь.
 */
@Composable
fun КнопкаИконка(
    знак: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    живая: Boolean = false,
    фон: Color? = null,
    знакЦвет: Color? = null,
) {
    val цвета = Тима.цвета
    Box(
        modifier = modifier
            .size(36.dp)
            .background(фон ?: if (живая) цвета.навигация else цвета.акцентМягкий, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Подпись(
            текст = знак,
            кегль = TimaType.щ4,
            вес = FontWeight.Bold,
            цвет = знакЦвет ?: if (живая) цвета.наАкценте else цвета.текст,
        )
    }
}

/**
 * Чип: короткая пилюля. `.чип`.
 *
 * Значение несёт заливка, а не форма: салатовая — выбранное, зелёная — подтверждённое
 * (E2E), тихая — обычная пометка.
 */
@Composable
fun Чип(
    надпись: String,
    modifier: Modifier = Modifier,
    вид: ВидЧипа = ВидЧипа.Тихий,
    onClick: (() -> Unit)? = null,
) {
    val цвета = Тима.цвета
    val фон = when (вид) {
        ВидЧипа.Тихий -> цвета.акцентМягкий
        ВидЧипа.Выбран -> цвета.навигация
        ВидЧипа.Подтверждено -> цвета.подтверждено
    }
    val цветНадписи = when (вид) {
        ВидЧипа.Тихий -> цвета.текст2
        else -> цвета.наАкценте
    }

    Box(
        modifier = modifier
            .background(фон, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Подпись(надпись, кегль = TimaType.щ6, вес = FontWeight.Bold, цвет = цветНадписи)
    }
}

enum class ВидЧипа { Тихий, Выбран, Подтверждено }

/**
 * Счётчик непрочитанного — **янтарь**. `.счёт`.
 *
 * Янтарь означает активность: новые сообщения, комментарии, непрочитанное. Текст на
 * нём чёрный в обеих темах: 12,32 : 1, и терять этот контраст не за что — янтарь не
 * зелёный, и правило про текст на заливке к нему не относится.
 */
@Composable
fun Счётчик(сколько: Int, modifier: Modifier = Modifier) {
    if (сколько <= 0) return
    val цвета = Тима.цвета
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
            .background(цвета.активность, CircleShape)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Подпись(
            // Больше 99 показывать незачем: точное число не меняет решения человека,
            // а ширину пилюли меняет.
            текст = if (сколько > 99) "99+" else сколько.toString(),
            кегль = TimaType.щ6,
            вес = FontWeight.ExtraBold,
            цвет = цвета.наЯнтаре,
        )
    }
}

/** Ряд управляющих элементов с одинаковым зазором: чипы, кнопки шапки. */
@Composable
fun РядУправления(
    modifier: Modifier = Modifier,
    зазор: androidx.compose.ui.unit.Dp = TimaSpacing.о2,
    content: @Composable () -> Unit,
) = Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(зазор),
    verticalAlignment = Alignment.CenterVertically,
) { content() }
