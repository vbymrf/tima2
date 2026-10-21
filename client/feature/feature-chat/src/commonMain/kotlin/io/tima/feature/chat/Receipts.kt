package io.tima.feature.chat

import androidx.compose.runtime.compositionLocalOf
import io.tima.core.ui.MarkKind
import io.tima.domain.chat.MessageDisplay

/**
 * «Доставлено» и «прочитано» своих сообщений в личной переписке — до времени написания
 * (ПЛАН-(ОП)-ОТМЕТОК-И-ПРИСУТСТВИЯ). Время одно у отправителя и получателя: своё сообщение
 * помнит его как время написания, собеседник получил его подписанным.
 */
data class ChatReceipt(val deliveredMs: Long = 0, val readMs: Long = 0)

/** Отметки открытой переписки — пузырям, без протаскивания через каждый слой ленты. */
internal val LocalChatReceipt = compositionLocalOf<ChatReceipt?> { null }

/**
 * Знак своего сообщения: ждёт, сервер принял, доставлено, прочитано, не ушло. У чужого и
 * служебного знака нет. [own] — сообщение своё: написанное с другого своего устройства приходит
 * сюда входящим, но знаки у него те же (заказчик 2026-10-21) — оно на сервере, раз пришло, а
 * отметки собеседника приходят всем устройствам отправителя.
 */
fun markOf(display: MessageDisplay?, atMs: Long?, receipt: ChatReceipt?, own: Boolean = false): MarkKind? = when (display) {
    MessageDisplay.PENDING -> MarkKind.Waits
    MessageDisplay.SENT -> sentMark(atMs, receipt)
    MessageDisplay.FAILED -> MarkKind.NotLeft
    MessageDisplay.RECEIVED -> if (own) sentMark(atMs, receipt) else null
    // Служебной строке отмечать нечего: её никто не отправлял.
    MessageDisplay.UNREADABLE, MessageDisplay.SYSTEM, null -> null
}

private fun sentMark(atMs: Long?, receipt: ChatReceipt?): MarkKind = when {
    receipt == null || atMs == null -> MarkKind.Left
    receipt.readMs > 0 && atMs <= receipt.readMs -> MarkKind.Read
    receipt.deliveredMs > 0 && atMs <= receipt.deliveredMs -> MarkKind.Delivered
    else -> MarkKind.Left
}

/**
 * Вторая строка шапки личной переписки (ПЛАН-(ОП)): «печатает…», «в сети» или «был(а) сегодня в
 * 14:05»; `null` — сказать нечего.
 */
fun peerStatus(
    words: io.tima.core.words.ChatWords,
    callWords: io.tima.core.words.CallLogWords,
    typing: Boolean,
    online: Boolean,
    lastSeenMs: Long,
): String? = when {
    typing -> words.typing
    online -> words.online
    lastSeenMs > 0 -> words.lastSeen(
        day(lastSeenMs, callWords.today, callWords.yesterday).replaceFirstChar { it.lowercase() },
        time(lastSeenMs),
    )
    else -> null
}
