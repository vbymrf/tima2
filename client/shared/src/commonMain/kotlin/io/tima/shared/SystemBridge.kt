package io.tima.shared

import io.tima.domain.chat.NoticeTab
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Нажатие на строку уведомления (ПЛАН-(МП) М8): платформа кладёт сюда «что открыть», корень забирает, когда может
 * (после замка). Код — вкладка журнала и, если сущность одна, она.
 */
object NoticeTaps {
    private const val FIELD = '\u001F'

    /** Ждущее открытие; забрал — `null`. */
    val pending = MutableStateFlow<String?>(null)

    fun open(code: String) {
        pending.value = code
    }

    internal fun code(tab: NoticeTab, entity: String?): String = tab.wire + (entity?.let { FIELD + it } ?: "")

    /** Вкладка и сущность из кода; не разобрали — `null`. */
    internal fun parse(code: String): Pair<NoticeTab, String?>? {
        val tab = NoticeTab.of(code.substringBefore(FIELD)) ?: return null
        return tab to code.substringAfter(FIELD, "").ifEmpty { null }
    }
}

/**
 * Системная «Назад» (ПЛАН-(МП) М7, МП5): платформа зовёт [handle]; `false` — приложение в начале «Телефона», выходить
 * решает платформа. Ставит корень, пока жив.
 */
object SystemBack {
    @Volatile
    var handle: (() -> Boolean)? = null
}
