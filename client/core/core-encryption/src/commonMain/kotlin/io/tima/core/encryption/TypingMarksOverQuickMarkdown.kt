package io.tima.core.encryption

import io.tima.crypto.QuickMarkdown
import io.tima.domain.chat.TypingMarks
import io.tima.domain.chat.TypingSpan
import io.tima.domain.chat.TypingStyle

/** Строка ввода (ПЛАН-(РС) РС5): тот же разбор, что уходит в сообщение, в координатах набранного. */
object TypingMarksOverQuickMarkdown : TypingMarks {
    override fun of(text: String): List<TypingSpan> =
        QuickMarkdown.typing(text).map { TypingSpan(TypingStyle.valueOf(it.kind.name), it.start, it.end) }
}
