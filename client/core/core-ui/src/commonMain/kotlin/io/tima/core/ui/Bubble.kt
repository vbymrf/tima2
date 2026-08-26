package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Пузырь сообщения переписки.
 *
 * **Три решения макета, которые здесь важнее вида.**
 *
 * 1. **У сообщения есть рамка** — единственное место, где рамка, а не линия. Фон
 *    содержимого белый, и без рамки чужое сообщение на белом сливалось бы с фоном.
 *
 * 2. **Полоса автора — левая граница пузыря, а не подложка под ним.** Поэтому она сама
 *    огибает оба скругления; подложка потребовала бы второго прямоугольника и на
 *    скруглениях выглядела бы браком. Цвет полосы назначает клиент при входе в чат, и
 *    человек может его сменить.
 *
 * 3. **Аватар — первый элемент внутри пузыря, а не рядом с ним.** У сообщения есть
 *    рамка, и аватар живёт под ней, а не на общем фоне. Он стоит справа от полосы,
 *    выступает вверх в зазор между репликами и **непрозрачен**: он перекрывает полосу и
 *    угол рамки, а полупрозрачный тон здесь читается как брак.
 *
 * Аватар вставлен в сообщения **автора**, в свои — нет: свой аватар в каждом пузыре
 * шум, а кто говорит, и так понятно.
 */
@Composable
fun Пузырь(
    /** `true` — моё сообщение: другой фон, ни полосы, ни аватара, ни имени. */
    моё: Boolean,
    modifier: Modifier = Modifier,
    /** Имя автора. У своих не показывается; у продолжения серии — тоже. */
    автор: String? = null,
    /** Буквы аватара автора. */
    аватар: String? = null,
    /**
     * Продолжение серии: перед этим сообщением есть предыдущее от того же автора.
     * Тогда аватара и имени нет — вопроса «кто это» сообщение не задаёт.
     */
    продолжение: Boolean = false,
    /** Цвет полосы автора. По умолчанию — салатовый. */
    полоса: Color? = null,
    /** Нижняя строка: эмоции, время, галочки. Одним рядом справа. */
    низ: (@Composable () -> Unit)? = null,
    содержимое: @Composable () -> Unit,
) {
    val цвета = Тима.цвета
    val показыватьАвтора = !моё && !продолжение
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (моё) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Column(
                modifier = Modifier
                    // Предел ширины считается вместе с рамкой и полем: справа от пузыря
                    // должно остаться место кнопке.
                    .widthIn(max = ПРЕДЕЛ_ШИРИНЫ)
                    .background(
                        color = if (моё) цвета.мои else цвета.автор,
                        shape = RoundedCornerShape(TimaShapes.радиус),
                    )
                    .border(1.dp, цвета.рамка, RoundedCornerShape(TimaShapes.радиус))
                    .then(
                        if (!моё) {
                            // Полоса автора: левая граница самого пузыря.
                            Modifier.полосаАвтора(полоса ?: цвета.навигация)
                        } else {
                            Modifier
                        },
                    )
                    .padding(
                        start = if (моё) 14.dp else 12.dp + ПОЛОСА,
                        end = 14.dp,
                        top = 11.dp,
                        bottom = 11.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (показыватьИмя(показыватьАвтора, автор)) {
                    Подпись(
                        текст = автор!!,
                        // Имя отходит на ширину аватара: обтекания нет, текст идёт во
                        // всю ширину пузыря.
                        modifier = Modifier.padding(start = ОТСТУП_ПОД_АВАТАР),
                        кегль = TimaType.щ6,
                        вес = FontWeight.ExtraBold,
                        цвет = цвета.текст2,
                    )
                }
                содержимое()
                низ?.let {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { it() }
                }
            }

            if (показыватьАвтора && аватар != null) {
                // Выступает вверх, в зазор между репликами, и перекрывает полосу.
                Box(
                    modifier = Modifier
                        .offset(x = (-1).dp, y = (-12).dp)
                        .background(
                            color = if (моё) цвета.мои else цвета.автор,
                            shape = RoundedCornerShape(TimaShapes.квадрат),
                        )
                        .border(1.dp, цвета.рамка, RoundedCornerShape(TimaShapes.квадрат))
                        .padding(АВАТАР_ПОЛЕ),
                ) {
                    Подпись(
                        текст = аватар,
                        кегль = TimaType.щ6,
                        вес = FontWeight.ExtraBold,
                        цвет = цвета.текст,
                    )
                }
            }
        }
    }
}

private fun показыватьИмя(показывать: Boolean, автор: String?): Boolean =
    показывать && !автор.isNullOrBlank()

/**
 * Полоса автора — **левая граница пузыря**, а не подложка под ним.
 *
 * Рисуется отсечением по форме самого пузыря: полоса сама огибает оба скругления, и
 * второго прямоугольника не требуется. Подложка под пузырём давала бы на скруглениях
 * зазор, который читается как брак.
 */
private fun Modifier.полосаАвтора(цвет: Color): Modifier = drawBehind {
    val радиус = TimaShapes.радиус.toPx()
    val форма = Path().apply {
        addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                cornerRadius = CornerRadius(радиус, радиус),
            ),
        )
    }
    clipPath(форма) {
        drawRect(color = цвет, size = Size(ПОЛОСА.toPx(), size.height))
    }
}
/** Ширина полосы автора: `border-left: 4px`. */
private val ПОЛОСА = 4.dp

/** Насколько имя отходит вправо, освобождая место аватару: `padding-left: 33px`. */
private val ОТСТУП_ПОД_АВАТАР = 33.dp

/** Поле внутри аватара пузыря: он 40×40 при кегле 10. */
private val АВАТАР_ПОЛЕ = 13.dp

/** `max-width: 290px` из макета. */
private val ПРЕДЕЛ_ШИРИНЫ = 290.dp
