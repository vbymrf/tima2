package io.tima.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import io.tima.core.ui.Tima
import io.tima.domain.chat.TypingMarks
import io.tima.domain.chat.TypingStyle

/**
 * Строка ввода: знаки быстрого оформления видны серым, текст уже оформлен (ПЛАН-(РС) РС5,
 * doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §7).
 *
 * Только вид: набранный текст не меняется, смещения курсора те же ([OffsetMapping.Identity]).
 * Поэтому «стереть знак» — просто стереть символ: знак остаётся знаком до отправки, а
 * превращается в оформление уже в сообщении.
 */
@Composable
internal fun rememberTypingView(marks: TypingMarks?): VisualTransformation {
    val colors = Tima.colors
    if (marks == null) return VisualTransformation.None
    val marker = colors.text3
    val quote = colors.text2
    val highlight = colors.activity.copy(alpha = 0.35f)
    val code = colors.functional
    val tag = colors.confirmed
    return remember(marks, marker, quote, highlight, code, tag) {
        VisualTransformation { text ->
            val styled = buildAnnotatedString {
                append(text.text)
                for (span in marks.of(text.text)) {
                    val style = when (span.style) {
                        TypingStyle.MARKER -> SpanStyle(color = marker)
                        TypingStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                        TypingStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                        TypingStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                        TypingStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        TypingStyle.HIGHLIGHT -> SpanStyle(background = highlight)
                        TypingStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = code)
                        TypingStyle.MENTION, TypingStyle.HASHTAG -> SpanStyle(color = tag)
                        // Заголовок в строке ввода — жирным, без смены размера: строка не должна прыгать.
                        TypingStyle.HEADING -> SpanStyle(fontWeight = FontWeight.ExtraBold)
                        TypingStyle.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic, color = quote)
                    }
                    val start = span.start.coerceIn(0, text.length)
                    val end = span.end.coerceIn(start, text.length)
                    if (end > start) addStyle(style, start, end)
                }
            }
            TransformedText(styled, OffsetMapping.Identity)
        }
    }
}
