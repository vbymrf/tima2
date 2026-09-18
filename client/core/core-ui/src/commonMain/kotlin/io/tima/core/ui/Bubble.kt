package io.tima.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
    /** Картинка аватара автора. Есть — вместо букв, в том же квадрате. */
    avatarImage: ImageBitmap? = null,
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
    // Строка автора НЕ масштабируется вместе с сообщениями (заказчик 2026-09-18: «первая
    // строка либо не масштабируется, либо вместе с аватаром» — выбрано первое). Caption
    // умножает кегль на множитель группы, поэтому здесь он заранее поделен.
    //
    // Геометрия — по пробе `Layout-UI-light/пробы-социум/аватар-за-округлением.html`:
    // аватар выступает вверх в зазор между репликами, не доставая до рамки предыдущей на
    // один пункт; низ его — на нижней линии строки имени, в следующую строку он заходит не
    // больше чем на два пункта; стоит за скруглением пузыря (радиус + 3), имя — вплотную.
    val scale = LocalTextScale.current
    val authorSize = AUTHOR_SIZE / scale
    val nameLine = with(LocalDensity.current) { AUTHOR_SIZE.toDp() } * 1.35f
    val avatarRise = REPLY_GAP - 1.dp
    val avatarSide = avatarRise + BUBBLE_TOP + nameLine + 1.dp
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
                        // всю ширину пузыря. Отступ едет за кеглем вместе с аватаром.
                        // Имя — сразу за аватаром: он стоит за скруглением, имя от его
                        // правого края через 6, за вычетом поля пузыря, в котором мы уже.
                        modifier = Modifier.padding(start = AVATAR_LEFT + avatarSide + 6.dp - 12.dp - STRIP).height(nameLine),
                        // Кегль имени — 22, верхняя ступень «Сообщений» в настройках
                        // (решение заказчика 2026-09-18): строка автора выше, и аватар
                        // помещается рядом с ней, а не сползает на текст. Сам текст
                        // сообщения при этом не меняется.
                        fontSize = authorSize,
                        weight = FontWeight.ExtraBold,
                        color = colors.text2,
                        lineOne = true,
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
                // Стоит по центру строки автора и на неё же рассчитан размером: оба
                // растут вместе с кеглем сообщений (заказчик 2026-09-18: «первая строка
                // либо не масштабируется, либо масштабируется вместе с аватаром»).
                val shape = RoundedCornerShape(TimaShapes.square)
                Box(
                    modifier = Modifier
                        .offset(x = AVATAR_LEFT, y = -avatarRise)
                        .size(avatarSide)
                        .background(color = if (my) colors.my else colors.author, shape = shape)
                        .border(1.dp, colors.border, shape)
                        .clip(shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatarImage != null) {
                        Image(
                            bitmap = avatarImage,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Caption(
                            text = avatar,
                            fontSize = TimaType.sz4 / scale,
                            weight = FontWeight.ExtraBold,
                            color = colors.text,
                        )
                    }
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

/** Кегль имени автора: 22 (верхняя ступень настроек), уменьшенный в полтора раза — заказчик 2026-09-19. */
private val AUTHOR_SIZE = 15.sp

/** Зазор между репликами в ленте — `spacedBy(about3)` в `ChatScreen.Feed`. */
private val REPLY_GAP = 12.dp

/** Верхнее поле пузыря: `padding: 11px` из макета. */
private val BUBBLE_TOP = 11.dp

/** Аватар — за скруглением пузыря: радиус + 3 (проба «аватар-за-округлением»). */
private val AVATAR_LEFT = TimaShapes.radius + 3.dp

/** `max-width: 290px` из макета. */
private val ПРЕДЕЛ_ШИРИНЫ = 290.dp
