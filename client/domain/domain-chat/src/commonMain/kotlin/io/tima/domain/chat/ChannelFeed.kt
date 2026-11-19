package io.tima.domain.chat

/**
 * Запись в ленте канала (ПЛАН-(СЦ) СЦ3, «минимальный экран канала»; К5): текст открытым — контур публичный
 * (ADR-0011 §4), число комментариев приходит вместе с записью.
 */
data class ChannelFeedPost(
    val postId: Long,
    val authorId: String,
    val text: String,
    val atMs: Long,
    val comments: Int,
    val commentsClosed: Boolean,
)
