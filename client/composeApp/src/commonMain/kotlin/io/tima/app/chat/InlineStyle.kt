package io.tima.app.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.tima.crypto.EntityType
import io.tima.crypto.Markup
import io.tima.crypto.MarkupEntity

/**
 * Точечный рендер инлайн-сущностей (ADR-0011 §8; ПЛАН-РЕФАКТОРИНГА.md Р5б).
 *
 * Это не блочный рендерер: таблицы, контейнеры, каскад стилей чата/сообщества
 * ([io.tima.crypto.StyleCascade]) и профили возможностей
 * ([io.tima.crypto.ContentProfiles]) сюда не входят — та часть требует визуальной
 * итерации, которую нельзя провести в среде без Compose-тулчейна, и остаётся
 * открытой работой. Здесь — механическое и проверяемое отдельно: сущность внутри
 * ОДНОГО узла (жирный, курсив, код, ссылка…) → `SpanStyle`.
 *
 * Многоузловые сообщения (реальные блоки — списки, заголовки, таблицы) намеренно
 * показываются объединённым текстом БЕЗ инлайн-оформления: смещения сущностей
 * заданы в символах СВОЕГО узла (Markup.kt), и применить их к склеенному тексту
 * значило бы или разъехаться, или тихо выбрать не тот узел.
 */
fun renderMessageContent(text: String, markupJson: String): AnnotatedString {
    val markup = Markup.decode(markupJson) ?: return AnnotatedString(text)
    // [text] здесь — уже склеенные узлы ([io.tima.crypto.MessageContent.plainText]),
    // отдельного списка узлов на этом уровне нет. Смещения сущностей заданы в
    // символах СВОЕГО узла — применить их можно только если узел был ровно один
    // (n.size == 1), иначе text и есть тот единственный узел. Больше узлов —
    // текст остаётся, оформление — нет: это честная деградация, не потеря текста.
    if (markup.n.size != 1) return AnnotatedString(text)
    val nodeId = markup.n[0]
    val entities = markup.entities.filter { it.nodeId == nodeId }
    return renderInline(text, entities)
}

/** Инлайн-рендер сущностей одного узла. Публична отдельно — тестируется без Markup.decode. */
fun renderInline(text: String, entities: List<MarkupEntity>): AnnotatedString {
    if (entities.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (e in entities) {
            // Повреждённая или рассогласованная сущность (из будущей версии клиента,
            // из битой правки) — пропускаем эту сущность, а не всё сообщение: тот же
            // принцип, что и у Markup.decode для разметки целиком.
            if (e.start < 0 || e.length <= 0 || e.start + e.length > text.length) continue
            addStyle(spanStyleFor(e.type), e.start, e.start + e.length)
        }
    }
}

private fun spanStyleFor(type: EntityType): SpanStyle = when (type) {
    EntityType.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    EntityType.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    EntityType.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
    EntityType.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    EntityType.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
    EntityType.LINK, EntityType.MENTION, EntityType.HASHTAG -> SpanStyle(textDecoration = TextDecoration.Underline)
    // Тип из более новой версии клиента — текст остаётся, просто без оформления
    // (тот же принцип деградации, что у EntityType.fromWire).
    EntityType.UNKNOWN -> SpanStyle()
}
