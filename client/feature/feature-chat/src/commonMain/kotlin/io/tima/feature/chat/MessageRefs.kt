package io.tima.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tima.core.media.decodeImage
import io.tima.core.media.mediaUri
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.LocalBubbleInset
import io.tima.core.ui.MessagePicture
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.RefCut
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichMedia
import io.tima.domain.chat.RichText

/**
 * Вставленное внутри вставленного — одной строкой: исходное раскрывается на один шаг, иначе
 * сообщение со ссылкой на себя разворачивалось бы без конца.
 */
internal val LocalRefDepth = staticCompositionLocalOf { 0 }

/** Подкладка верхнего уровня — во всю ширину пузыря, на его поля (заказчик 2026-10-20). */
internal val LocalUnderlayBleed = staticCompositionLocalOf { false }

/** Имя владельца уже стоит заголовком пункта («Списком») — в самой подкладке его нет. */
internal val LocalRefTitled = staticCompositionLocalOf { false }

/** Строк у абзаца в подкладке с заданным числом строк; иначе — без предела. */
internal val LocalRefLines = staticCompositionLocalOf { Int.MAX_VALUE }

/**
 * Как перейти к вставленному (заказчик 2026-10-20, ADR-0050 §1): `""` — без перехода (сообщение
 * видно, перехода нет); [tap] — само сообщение: любая его часть открывает окно перехода и перебивает
 * нажатия внутри; `default`, `custom` — кнопка «Продолжить»; `blank` — стрелка: с именем владельца —
 * короткая перед ним, имя зелёное и нажимается; без имени — длинная.
 */
private class RefJump(val more: String, val tap: Boolean, val go: () -> Unit) {
    val arrow get() = !tap && more == "blank"
    val button get() = !tap && (more == "default" || more == "custom")
}

/**
 * Подборка (ADR-0050 §1) — в своём пузыре: область с полями, у нажимаемой — полоса справа. Только
 * текст и первое фото ([RefCut.quoted]); начинается с ряда, списка или таблицы — «Откройте сообщение».
 */
@Composable
internal fun QuotedRef(quoted: RichBlock.Quoted) {
    val colors = Tima.colors
    val look = LocalQuoteLook.current(quoted.kind, quoted.chatId, quoted.messageId)
    val open = rememberMessageOpen()
    val jump = RefJump(quoted.more, quoted.tap) { open(quoted.kind, quoted.chatId, quoted.messageId) }
    val depth = LocalRefDepth.current
    val titled = LocalRefTitled.current
    RefFrame(jump, bleed = false, tag = if (quoted.tap) "message.ref.tap" else "message.ref") {
        if (!titled) RefAuthor(quoted.author, look.name, jump)
        when {
            depth > 0 -> RefLine(look)
            look.rich == null -> RefFallback(look, if (quoted.lines == 0) Int.MAX_VALUE else quoted.lines)
            else -> {
                val shown = remember(look.rich) { RefCut.quoted(look.rich) }
                if (!shown.closed) shown.photo?.let { RefPhoto(it) }
                when {
                    shown.closed || (shown.texts.isEmpty() && shown.photo == null) -> Caption(Tima.words.chat.refOpen, fontSize = TimaType.sz5, color = colors.text2)
                    shown.texts.isNotEmpty() -> Caption(
                        annotated(withoutLinks(joined(shown.texts))),
                        fontSize = TimaType.sz5,
                        maxLines = if (quoted.lines == 0) Int.MAX_VALUE else quoted.lines,
                    )
                }
            }
        }
        RefMore(jump, quoted.moreText, author = quoted.author && look.name != null)
    }
}

/**
 * Подкладка (ADR-0050 §1) — без своего пузыря, внутри основного: область во всю его ширину, у
 * нажимаемой — полоса на правом краю основного пузыря. Исходное — как есть, со всем оформлением;
 * с заданным числом строк — по [RefCut.underlay].
 */
@Composable
internal fun UnderlayRef(underlay: RichBlock.Underlay) {
    val look = LocalQuoteLook.current(underlay.kind, underlay.chatId, underlay.messageId)
    val open = rememberMessageOpen()
    val jump = RefJump(underlay.more, underlay.tap) { open(underlay.kind, underlay.chatId, underlay.messageId) }
    val depth = LocalRefDepth.current
    // На поля выходит только в пузыре: вне его (виджет в редакторе) полей нет — обычная область.
    val bleed = LocalUnderlayBleed.current && depth == 0 && LocalBubbleInset.current.start > 0.dp
    val titled = LocalRefTitled.current
    RefFrame(jump, bleed = bleed, tag = if (underlay.tap) "message.ref.tap" else "message.ref") {
        if (!titled) RefAuthor(underlay.author, look.name, jump)
        val rich = look.rich
        when {
            depth > 0 -> RefLine(look)
            rich == null -> RefFallback(look, if (underlay.lines == 0) Int.MAX_VALUE else underlay.lines)
            else -> {
                val blocks = remember(rich, underlay.lines) { RefCut.underlay(rich, underlay.lines) }
                CompositionLocalProvider(
                    LocalRefDepth provides depth + 1,
                    LocalUnderlayBleed provides false,
                    LocalRefLines provides if (underlay.lines == 0) Int.MAX_VALUE else underlay.lines,
                    LocalRichMedia provides rich.media,
                ) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (b in blocks) RichBlockViewOf(b)
                    }
                }
            }
        }
        RefMore(jump, underlay.moreText, author = underlay.author && look.name != null)
    }
}

/**
 * Заголовок пункта «Списком» (заказчик 2026-10-20): имя владельца, если задано, иначе «Сообщение» — не
 * пустая строка. Имя не вместилось — обрезается. Со стрелкой — короткая стрелка, имя зелёное и ведёт
 * к сообщению.
 */
@Composable
internal fun RefTitle(block: RichBlock, size: androidx.compose.ui.unit.TextUnit, color: androidx.compose.ui.graphics.Color) {
    data class Ref(val kind: String, val chat: String, val id: Long, val author: Boolean, val more: String, val tap: Boolean)
    val ref = when (block) {
        is RichBlock.Underlay -> Ref(block.kind, block.chatId, block.messageId, block.author, block.more, block.tap)
        is RichBlock.Quoted -> Ref(block.kind, block.chatId, block.messageId, block.author, block.more, block.tap)
        else -> return
    }
    val look = LocalQuoteLook.current(ref.kind, ref.chat, ref.id)
    val open = rememberMessageOpen()
    val jump = RefJump(ref.more, ref.tap) { open(ref.kind, ref.chat, ref.id) }
    val name = look.name?.takeIf { ref.author }
    if (name != null && jump.arrow) {
        RefAuthor(true, name, jump)
    } else {
        Caption(name ?: Tima.words.chat.refItem, fontSize = size, color = color, lineOne = true)
    }
}

/**
 * Область вставленного: фон, поля; [bleed] — во всю ширину основного пузыря, на его поля. Полоса
 * справа — у нажимаемого целиком (заказчик 2026-10-20: слева уже полоса автора); нажатие поверх
 * содержимого — перебивает ссылки и фото внутри.
 */
@Composable
private fun RefFrame(jump: RefJump, bleed: Boolean, tag: String, content: @Composable () -> Unit) {
    val colors = Tima.colors
    val inset = LocalBubbleInset.current
    val start = if (bleed) (inset.start - 1.dp).coerceAtLeast(0.dp) else 0.dp
    val end = if (bleed) (inset.end - 1.dp).coerceAtLeast(0.dp) else 0.dp
    Box(
        Modifier.fillMaxWidth().bleed(start, end)
            .then(if (bleed) Modifier.background(colors.functional) else Modifier.background(colors.functional, RoundedCornerShape(6.dp)))
            .testTag(tag),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(start = if (bleed) start + 1.dp else 8.dp, end = (if (bleed) end + 1.dp else 8.dp) + if (jump.tap) STRIPE else 0.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
        if (jump.tap) {
            Box(Modifier.matchParentSize().clickable(onClick = jump.go))
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier.align(Alignment.CenterEnd).width(STRIPE).fillMaxHeight()
                        .background(colors.navigation, if (bleed) RoundedCornerShape(0.dp) else RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)),
                )
            }
        }
    }
}

/** Имя владельца: со стрелкой — короткая стрелка перед ним, имя зелёное и ведёт к сообщению. */
@Composable
private fun RefAuthor(author: Boolean, name: String?, jump: RefJump) {
    if (!author || name == null) return
    val colors = Tima.colors
    if (jump.arrow) {
        Row(
            Modifier.clickable(onClick = jump.go).padding(vertical = 2.dp).testTag("message.ref.more"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Arrow(14.dp)
            Caption(name, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colors.navigation, lineOne = true)
        }
    } else {
        Caption(name, fontSize = TimaType.sz5, weight = FontWeight.Bold, lineOne = true)
    }
}

/** «Продолжить» — кнопкой; стрелка без имени — длинная. */
@Composable
private fun RefMore(jump: RefJump, moreText: String, author: Boolean) {
    when {
        jump.button -> Chip(
            if (jump.more == "custom") moreText.ifBlank { Tima.words.chat.continueReading } else Tima.words.chat.continueReading,
            kind = ChipKind.Selected,
            onClick = jump.go,
            modifier = Modifier.testTag("message.ref.more"),
        )
        jump.arrow && !author -> Box(Modifier.clickable(onClick = jump.go).padding(vertical = 6.dp, horizontal = 2.dp).testTag("message.ref.more")) {
            Arrow(36.dp)
        }
    }
}

/** Стрелка «→» зелёным: линия и наконечник. */
@Composable
private fun Arrow(length: Dp) {
    val color = Tima.colors.navigation
    Canvas(Modifier.width(length).height(12.dp)) {
        val y = size.height / 2
        val w = 2.dp.toPx()
        val head = 5.dp.toPx()
        drawLine(color, Offset(0f, y), Offset(size.width, y), w, StrokeCap.Round)
        drawLine(color, Offset(size.width - head, y - head), Offset(size.width, y), w, StrokeCap.Round)
        drawLine(color, Offset(size.width - head, y + head), Offset(size.width, y), w, StrokeCap.Round)
    }
}

/** Вставленное внутри вставленного — строкой. */
@Composable
private fun RefLine(look: QuoteLook) {
    Caption(look.text ?: Tima.words.chat.refOpen, fontSize = TimaType.sz5, color = Tima.colors.text2, maxLines = 1)
}

/** Исходного на устройстве нет целиком — что известно: удалено, недоступно, текст, «из другой переписки». */
@Composable
private fun RefFallback(look: QuoteLook, lines: Int) {
    when {
        look.deleted -> Tertiary(Tima.words.chat.deleted, lineOne = true)
        look.text != null -> Caption(look.text, fontSize = TimaType.sz5, maxLines = lines)
        // Сервер не отдал — уровень не открыт читающему (Решения §11.3): «Недоступно» и что делать —
        // по причине, которую назвал сервер; не спрашивали ещё — общей строкой.
        look.why != null -> Tertiary(Tima.words.showcase.peekUnavailable(look.why, look.whyLevel))
        else -> Tertiary(Tima.words.chat.quotedElsewhere)
    }
}

/** Первое фото подборки — уменьшенным во всю ширину области. */
@Composable
private fun RefPhoto(m: RichMedia) {
    val bitmap = remember(m.mediaId) { if (m.preview.isNotEmpty()) decodeImage(m.preview) else null }
    val shape = RoundedCornerShape(6.dp)
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxWidth().height(REF_PHOTO).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.fillMaxWidth().heightIn(max = REF_PHOTO).clip(shape)) {
            MessagePicture(mediaUri(m.mediaId, m.key), if (m.width > 0 && m.height > 0) m.width.toFloat() / m.height else null, Tima.words.chat.pictureMissing)
        }
    }
}

/** Абзацы одним текстом, через перевод строки; оформление строки сохраняется. */
internal fun joined(texts: List<RichText>): RichText {
    val out = StringBuilder()
    val spans = mutableListOf<io.tima.domain.chat.RichSpan>()
    for ((i, t) in texts.withIndex()) {
        if (i > 0) out.append('\n')
        val at = out.length
        out.append(t.text)
        spans += t.spans.map { it.copy(start = it.start + at, end = it.end + at) }
    }
    return RichText(out.toString(), spans)
}

/** Шире родителя на [start] и [end]: подкладка ложится на поля пузыря. */
private fun Modifier.bleed(start: Dp, end: Dp): Modifier = if (start == 0.dp && end == 0.dp) this else layout { measurable, constraints ->
    val s = start.roundToPx()
    val e = end.roundToPx()
    if (!constraints.hasBoundedWidth) {
        val p = measurable.measure(constraints)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val wide = constraints.maxWidth + s + e
    val p = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
    layout(constraints.maxWidth, p.height) { p.place(-s, 0) }
}

private val STRIPE = 4.dp

/** Высота первого фото подборки. */
private val REF_PHOTO = 96.dp

/** Между соседними подкладками — тонкая линия цвета рамки во всю ширину пузыря (заказчик 2026-10-20). */
@Composable
internal fun UnderlaySeparator() {
    val inset = LocalBubbleInset.current
    Box(
        Modifier.fillMaxWidth().bleed((inset.start - 1.dp).coerceAtLeast(0.dp), (inset.end - 1.dp).coerceAtLeast(0.dp))
            .height(1.dp).background(Tima.colors.border),
    )
}
