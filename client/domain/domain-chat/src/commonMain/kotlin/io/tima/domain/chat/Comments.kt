package io.tima.domain.chat

/**
 * Комментарии под записью канала и страницы человека (ADR-0024, ПЛАН-КАНАЛОВ К5).
 *
 * **Комментарий — обычное сообщение, у которого контейнер не канал, а другая запись.**
 * Отсюда всё остальное выводится, и в первую очередь то, чего здесь НЕТ: своего круга,
 * своего права и своего способа удаления. Круг берётся у корня — тем же правилом на
 * сервере и здесь.
 *
 * **Комментирует тот, кто видит запись** (решение заказчика 2026-09-08). Отдельного права
 * не спрашивается ни у контейнера, ни у подписки: видна запись — открыт и разговор.
 * Поэтому в этом файле нет ни одного признака «можно ли писать»: сервер отвечает `404` на
 * то, чего человек не видит, и `403` на закрытое обсуждение, и оба ответа названы словами.
 *
 * **Глубина ровно два уровня.** Ответ на комментарий цепляется к тому же корню и несёт
 * «@имя» в тексте — так нарисовано в макете и так устроены ветки в группах. Третий
 * уровень вложения на телефоне не читается.
 */
data class CommentEntry(
    val postId: Long,
    val authorId: String,
    val text: String,
    val atMs: Long,
    /** Корень разговора. Один и тот же у всех комментариев записи. */
    val parentPostId: Long,
)

/** Чтение разговора под записью. */
class ReadComments(private val comments: PostComments) {

    /**
     * @param after номер последнего показанного комментария; 0 — с начала.
     *
     * Постранично «после», а не «до»: разговор читают с начала, а ленту с конца (решение
     * заказчика 2026-09-08). Порядок задаёт сервер, здесь он не переворачивается — иначе
     * два места решали бы одно и то же по-разному.
     */
    suspend fun under(channelId: String, postId: Long, after: Long = 0): CommentsStep {
        if (channelId.isBlank() || postId <= 0) return CommentsStep.Refused("bad_request")
        return comments.under(channelId, postId, after)
    }
}

/** Написать комментарий. */
class WriteComment(private val comments: PostComments) {

    suspend fun write(channelId: String, postId: Long, text: String): CommentStep {
        val body = text.trim()
        if (channelId.isBlank() || postId <= 0) return CommentStep.Refused("bad_request")
        // Пустой комментарий до сервера не доходит: предлагать действие, которое заведомо
        // отвергнут, значит обещать несбыточное.
        if (body.isEmpty()) return CommentStep.Empty
        if (body.length > MAX_CHARS) return CommentStep.TooLong
        return comments.write(channelId, postId, body)
    }

    companion object {
        /**
         * Предел содержимого — общий для всех сообщений (ADR-0019 §3): 4096 знаков.
         * Комментарий не исключение: он и есть сообщение.
         */
        const val MAX_CHARS: Int = 4096

        /**
         * «Ответить» подставляет «@имя» в поле ввода — вложения третьего уровня нет.
         *
         * Живёт в домене, а не в экране: то же правило понадобится ветке в группе, и два
         * места, склеивающие обращение по-своему, разъедутся на первом же пробеле.
         */
        fun mention(name: String, current: String): String {
            val tag = "@${name.trim()}"
            if (name.isBlank() || current.trimStart().startsWith(tag)) return current
            return if (current.isBlank()) "$tag " else "$tag $current"
        }
    }
}

/** Что вышло из запроса разговора. */
sealed interface CommentsStep {
    /**
     * @param level круг корня. Он же круг разговора: у комментария своего нет.
     */
    data class Conversation(val entries: List<CommentEntry>, val level: Int) : CommentsStep

    /**
     * Корня нет: удалён, не показан нам или сам комментарий. Три случая намеренно
     * неразличимы — сервер отвечает одинаково, чтобы отказ не сообщал о существовании
     * записи.
     */
    data object NoRoot : CommentsStep

    data class Offline(val retryAfterMs: Long) : CommentsStep
    data class Refused(val reason: String) : CommentsStep
}

/** Что вышло из попытки написать. */
sealed interface CommentStep {
    data class Written(val postId: Long) : CommentStep

    /** Обсуждение выключено: у канала целиком или у этой записи. Старые видны. */
    data object Closed : CommentStep

    /** Корня нет — см. [CommentsStep.NoRoot]. */
    data object NoRoot : CommentStep

    /** Пустой текст: до сервера не отправляется. */
    data object Empty : CommentStep

    data object TooLong : CommentStep
    data class Offline(val retryAfterMs: Long) : CommentStep
    data class Refused(val reason: String) : CommentStep
}

/**
 * Порт к серверу: разговор под записью.
 *
 * Адрес разговора — канал и запись в нём. Лента человека тоже канал (решение заказчика
 * 2026-09-04), поэтому второго порта для неё не нужно.
 */
interface PostComments {
    suspend fun under(channelId: String, postId: Long, after: Long): CommentsStep
    suspend fun write(channelId: String, postId: Long, text: String): CommentStep

    /** Убрать свой комментарий. Владелец и модератор канала убирают любой у себя. */
    suspend fun remove(channelId: String, postId: Long): CommentStep
}
