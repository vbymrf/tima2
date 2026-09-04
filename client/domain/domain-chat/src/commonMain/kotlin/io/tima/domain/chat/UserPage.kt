package io.tima.domain.chat

/**
 * Страница человека: своё и принесённое (ADR-0019 §7, ПЛАН-СОЦИУМА Г8).
 *
 * **Перенос — ссылка, а не копия.** Запись остаётся записью того, кто её написал: у себя
 * человек ставит указание на неё. Отсюда три свойства, ради которых так и сделано — автор
 * остаётся автором, удаление оригинала убирает принесённое, сужение круга действует
 * везде. Копия не умеет ничего из этого, и подменить одно другим означало бы отобрать у
 * автора власть над сказанным.
 *
 * **Круг назначает принёсший.** Он решает, кому показать у себя: «своим» у него значит
 * его своих, а не чужих. Круг оригинала при этом остаётся границей — унести можно только
 * то, что показали.
 */
class CarryToPage(private val pages: UserPages) {

    /**
     * Унести запись к себе.
     *
     * @param was круг оригинала. Проверяется здесь, а не только на сервере: предлагать
     *   действие, которое заведомо отвергнут, значит обещать несбыточное.
     * @param level круг, под которым запись ляжет на страницу.
     */
    suspend fun carry(groupId: String, messageId: Long, was: Int, level: Int = LEVEL_EVERYONE): CarryStep {
        if (groupId.isBlank() || messageId <= 0) return CarryStep.Refused("bad_request")
        if (!canCarry(was)) return CarryStep.CannotCarry
        if (level < 0 || level > 3) return CarryStep.Refused("bad_level")
        return pages.carry(groupId, messageId, level)
    }

    companion object {
        /** Круг «всем»: умолчание страницы. */
        const val LEVEL_EVERYONE: Int = 1

        /**
         * Выносится ли запись этого круга.
         *
         * Уровни 0, 1 и 2 — да; «по разрешению» отдано поимённо, шифр читают по ключу, и
         * ссылкой не передаётся ни то, ни другое.
         */
        fun canCarry(level: Int): Boolean = level in 0..2
    }
}

/** Что вышло из попытки унести к себе. */
sealed interface CarryStep {
    data class Carried(val postId: Long) : CarryStep

    /** Круг не выносится: «по разрешению» или шифр. */
    data object CannotCarry : CarryStep

    /** Записи нет — удалена либо нам её не показывали. */
    data object NotFound : CarryStep

    data class Offline(val retryAfterMs: Long) : CarryStep
    data class Refused(val reason: String) : CarryStep
}

/**
 * Строка страницы: своя запись или принесённая.
 *
 * Автор и принёсший — **разные люди**, и оба нужны на экране: принесённая запись
 * показывается от лица источника, а не от лица хозяина страницы. Иначе получилось бы
 * присвоение чужого текста самим показом.
 */
data class PageEntry(
    val postId: Long,
    /** Круг, под которым запись лежит на этой странице. */
    val level: Int,
    val atMs: Long,
    /** Кто написал. У принесённой — автор оригинала. */
    val authorId: String,
    val text: String?,
    /** Кто принёс. Пусто у своей записи. */
    val carriedBy: String = "",
    /** Откуда принесено: название группы. Пусто у своей записи. */
    val sourceTitle: String = "",
    val refGroupId: String = "",
    val refMessageId: Long = 0,
)

/** Чтение страницы. Своя — [PAGE_MINE], чужая — идентификатор человека. */
class ReadPage(private val pages: UserPages) {

    suspend fun page(userId: String = PAGE_MINE): PageStep = pages.page(userId)

    companion object {
        const val PAGE_MINE: String = "me"
    }
}

/** Что вышло из запроса страницы. */
sealed interface PageStep {
    data class Page(val entries: List<PageEntry>) : PageStep

    /** Ленты нет: человек ещё ничего себе не клал. Не поломка и не тайна. */
    data object NoPage : PageStep

    data class Offline(val retryAfterMs: Long) : PageStep
    data class Refused(val reason: String) : PageStep
}

/** Порт к серверу: страница человека и перенос к себе. */
interface UserPages {
    suspend fun carry(groupId: String, messageId: Long, level: Int): CarryStep
    suspend fun page(userId: String): PageStep

    /** Убрать запись со своей страницы. Оригинала это не касается. */
    suspend fun remove(postId: Long): CarryStep
}
