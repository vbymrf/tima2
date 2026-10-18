package io.tima.domain.chat

/**
 * Открытое тело записи (ADR-0011 §4): публичный контур — узлы и разметка открытым текстом, плоский
 * [text] — для старого читателя и поиска. Так сервер принимает запись канала и страницы.
 */
data class OpenBody(val text: String, val nodes: List<String>, val markup: String)

/** Порт: опубликовать запись на своей странице (её канал). */
fun interface PagePosts {
    suspend fun publish(channelId: String, body: OpenBody, level: Int): PublishStep
}

sealed interface PublishStep {
    data class Published(val postId: Long) : PublishStep
    data class Offline(val retryAfterMs: Long) : PublishStep
    data class Refused(val reason: String) : PublishStep
}

/** Чем кончилась запись на страницу из редактора. */
sealed interface PageWriteResult {
    data class Published(val postId: Long) : PageWriteResult
    data object Empty : PageWriteResult
    data class Invalid(val problems: List<String>) : PageWriteResult
    data class TableTooWide(val maxColumns: Int) : PageWriteResult
    /** Картинки и карточки с картинкой на страницу не идут: у записи канала нет файлов. */
    data object HasFiles : PageWriteResult
    data object Offline : PageWriteResult
    data class Refused(val reason: String) : PageWriteResult
}

/**
 * Редактор на личной странице (Решения §10.2: «редактор есть и на личной странице»). Тот же
 * документ, те же правила; уходит не конвертом, а открытой записью канала страницы.
 */
class WriteToPage(private val posts: PagePosts, private val bodies: ComposedBodies) {

    suspend fun write(channelId: String, doc: ComposedDoc, level: Int): PageWriteResult {
        if (doc.isEmpty) return PageWriteResult.Empty
        if (TableFit.tooWide(doc).isNotEmpty()) return PageWriteResult.TableTooWide(TableFit.maxColumns())
        val problems = bodies.problems(doc)
        if (problems.isNotEmpty()) return PageWriteResult.Invalid(problems)
        val body = bodies.open(doc) ?: return PageWriteResult.HasFiles
        return when (val step = posts.publish(channelId, body, doc.level ?: level)) {
            is PublishStep.Published -> PageWriteResult.Published(step.postId)
            is PublishStep.Offline -> PageWriteResult.Offline
            is PublishStep.Refused -> PageWriteResult.Refused(step.reason)
        }
    }
}
