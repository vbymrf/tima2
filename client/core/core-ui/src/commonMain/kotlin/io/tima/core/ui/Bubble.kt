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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
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
fun Bubble(
    /** `true` — моё сообщение: другой фон, ни полосы, ни аватара, ни имени. */
    my: Boolean,
    modifier: Modifier = Modifier,
    /** Имя автора. У своих не показывается; у продолжения серии — тоже. */
    author: String? = null,
    /** Буквы аватара автора. */
    avatar: String? = null,
    /**
     * Продолжение серии: перед этим сообщением есть предыдущее от того же автора.
     * Тогда аватара и имени нет — вопроса «кто это» сообщение не задаёт.
     */
    continuation: Boolean = false,
    /** Цвет полосы автора. По умолчанию — салатовый. */
    strip: Color? = null,
    /** Нижняя строка: эмоции, время, галочки. Одним рядом справа. */
    bottom: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = Tima.colors
    val showAuthor = !my && !continuation
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (my) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Column(
                modifier = Modifier
                    // Предел ширины считается вместе с рамкой и полем: справа от пузыря
                    // должно остаться место кнопке.
                    .widthIn(max = ПРЕДЕЛ_ШИРИНЫ)
                    .background(
                        color = if (my) colors.my else colors.author,
                        shape = RoundedCornerShape(TimaShapes.radius),
                    )
                    .border(1.dp, colors.border, RoundedCornerShape(TimaShapes.radius))
                    .then(
                        if (!my) {
                            // Полоса автора: левая граница самого пузыря.
                            Modifier.authorStrip(strip ?: colors.navigation)
                        } else {
                            Modifier
                        },
                    )
                    .padding(
                        start = if (my) 14.dp else 12.dp + STRIP,
                        end = 14.dp,
                        top = 11.dp,
                        bottom = 11.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (showName(showAuthor, author)) {
                    Caption(
                        text = author!!,
                        // Имя отходит на ширину аватара: обтекания нет, текст идёт во
                        // всю ширину пузыря.
                        modifier = Modifier.padding(start = ОТСТУП_ПОД_АВАТАР),
                        fontSize = TimaType.sz6,
                        weight = FontWeight.ExtraBold,
                        color = colors.text2,
                    )
                }
                content()
                bottom?.let {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { it() }
                }
            }

            if (showAuthor && avatar != null) {
                // Выступает вверх, в зазор между репликами, и перекрывает полосу.
                Box(
                    modifier = Modifier
                        .offset(x = (-1).dp, y = (-12).dp)
                        .background(
                            color = if (my) colors.my else colors.author,
                            shape = RoundedCornerShape(TimaShapes.square),
                        )
                        .border(1.dp, colors.border, RoundedCornerShape(TimaShapes.square))
                        .padding(FIELD_AVATAR),
                ) {
                    Caption(
                        text = avatar,
                        fontSize = TimaType.sz6,
                        weight = FontWeight.ExtraBold,
                        color = colors.text,
                    )
                }
            }
        }
    }
}

private fun showName(show: Boolean, author: String?): Boolean =
    show && !author.isNullOrBlank()

/**
 * Полоса автора — **левая граница пузыря**, а не подложка под ним.
 *
 * Рисуется отсечением по форме самого пузыря: полоса сама огибает оба скругления, и
 * второго прямоугольника не требуется. Подложка под пузырём давала бы на скруглениях
 * зазор, который читается как брак.
 */
private fun Modifier.authorStrip(color: Color): Modifier = drawWithCache {
    // ── ПОЧЕМУ ЗДЕСЬ ГЕОМЕТРИЯ, А НЕ ПРЯМОУГОЛЬНИК ──────────────────────────────
    //
    // В макете это `border-left: 4px` у пузыря со скруглением. Браузер рисует такую рамку
    // не полосой: на угловой дуге её толщина едет от левой (4) к верхней (1), и полоса
    // СХОДИТ НА НЕТ, превращаясь в тонкую серую рамку. Отрисовка макета браузером и
    // увеличение угла в десять раз показывают именно это.
    //
    // Две прежние попытки повторили вид приблизительно и обе были отвергнуты глазом
    // заказчика: залитый прямоугольник обрывался плоским срезом, обводка постоянной
    // ширины — вертикальным. Оба раза не хватало схода на нет, и оба раза это называлось
    // «огибания нет».
    //
    // Поэтому здесь строится то же, что строит браузер:
    //   кольцо = внешний контур МИНУС внутренний,
    //   где внутренний отступает слева на 4, с прочих сторон на 1, отчего его углы
    //   становятся ЭЛЛИПТИЧЕСКИМИ — и зазор между контурами сам едет от 4 к 1;
    //   клин = левая доля, отрезанная по линии стыка углов (у рамок она идёт от внешнего
    //   угла к внутреннему), чтобы покрасить левую сторону, а не обвести пузырь кругом.
    val r = TimaShapes.radius.toPx()
    val left = STRIP.toPx()
    val thin = HAIRLINE.toPx()
    val w = size.width
    val h = size.height

    val outer = Path().apply {
        addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r, r)))
    }
    val inner = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(left, thin, (w - thin).coerceAtLeast(left), (h - thin).coerceAtLeast(thin)),
                topLeft = CornerRadius((r - left).coerceAtLeast(0f), (r - thin).coerceAtLeast(0f)),
                topRight = CornerRadius((r - thin).coerceAtLeast(0f), (r - thin).coerceAtLeast(0f)),
                bottomRight = CornerRadius((r - thin).coerceAtLeast(0f), (r - thin).coerceAtLeast(0f)),
                bottomLeft = CornerRadius((r - left).coerceAtLeast(0f), (r - thin).coerceAtLeast(0f)),
            ),
        )
    }
    val ring = Path.combine(PathOperation.Difference, outer, inner)

    // Линия стыка: от внешнего угла (0,0) в направлении внутреннего (4,1). Клин обрезан по
    // x = радиус — дальше кольца слева всё равно нет, а узкий клин дешевле.
    val slope = if (left > 0f) thin / left else 0f
    val wedge = Path().apply {
        moveTo(0f, 0f)
        lineTo(r, r * slope)
        lineTo(r, h - r * slope)
        lineTo(0f, h)
        close()
    }
    val strip = Path.combine(PathOperation.Intersect, ring, wedge)

    // `drawWithCache`: обе фигуры и их пересечение считаются при смене РАЗМЕРА, а не на
    // каждом кадре. Прежняя редакция создавала `Path` внутри отрисовки — на длинной
    // переписке это выделение памяти в цикле прокрутки.
    onDrawBehind { drawPath(path = strip, color = color) }
}

/** Толщина рамки пузыря с прочих сторон: `border: 1px` из макета. */
private val HAIRLINE = 1.dp

/** Ширина полосы автора: `border-left: 4px`. */
private val STRIP = 4.dp

/** Насколько имя отходит вправо, освобождая место аватару: `padding-left: 33px`. */
private val ОТСТУП_ПОД_АВАТАР = 33.dp

/** Поле внутри аватара пузыря: он 40×40 при кегле 10. */
private val FIELD_AVATAR = 13.dp

/** `max-width: 290px` из макета. */
private val ПРЕДЕЛ_ШИРИНЫ = 290.dp
