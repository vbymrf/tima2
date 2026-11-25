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
    /** Ответ сервера целиком — так запись хранится на устройстве (СЦ22). */
    val raw: String = "",
    /** Тело документом (СЦ22): MessageBody, base64url; пусто — только текст, узлы, разметка. */
    val bodyB64: String = "",
    val nodes: List<String> = emptyList(),
    val markup: String = "",
    val level: Int = 0,
    /** Эмоции записи (ПЛАН-(СЦ) §1.18): сколько поставили каждую, по убыванию; видят все. */
    val reactions: List<EmotionCount> = emptyList(),
    /** Своя эмоция — ключ шкалы; `null` — не ставил. */
    val myEmotion: String? = null,
    /** Свой палец: 1 — вверх, −1 — вниз, 0 — нет. Частная оценка: сервер отдаёт только свою. */
    val myVote: Int = 0,
)
