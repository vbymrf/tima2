package io.tima.domain.chat

/**
 * Строка в списке переписок и поиске у сообщения без текста (Решения §12): «Фото», «Файл: имя»,
 * «Контакт: имя», «Место», «Ссылка: сайт».
 *
 * Кодек слов не знает (они у экрана, на языке читающего), поэтому отдаёт метку, а экран списка
 * переводит её в слова — тем же путём, каким приглашение в звонок становится «Групповым
 * звонком». Метка начинается знаком, которого в набранном тексте не бывает.
 */
object MediaSummary {
    private const val MARK = '\u001E'

    enum class Kind { PHOTO, FILE, CONTACT, PLACE, LINK }

    fun of(kind: Kind, count: Int, name: String = ""): String = "$MARK${kind.name}|$count|$name"

    /** Метка ли это; нет — `null`, текст обычный. */
    fun parse(text: String): Triple<Kind, Int, String>? {
        if (!text.startsWith(MARK)) return null
        val parts = text.drop(1).split('|', limit = 3)
        val kind = Kind.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return null
        return Triple(kind, parts.getOrNull(1)?.toIntOrNull() ?: 1, parts.getOrNull(2).orEmpty())
    }
}
