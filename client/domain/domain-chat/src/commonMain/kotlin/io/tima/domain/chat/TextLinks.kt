package io.tima.domain.chat

/**
 * Ссылка в тексте (ADR-0050 §3): адрес с `https://`, `http://`, `www.` или домен популярной зоны
 * (`tima.ru`, `example.com/путь`). Знаки препинания в конце — конец предложения, не адрес; перед
 * доменом не `@` (почта) и не буква. Одна грамматика у показа, простого сообщения и редактора.
 */
object TextLinks {

    /** Зоны, домен которых без `https://` считается адресом. */
    private val ZONES = listOf(
        "com", "ru", "org", "net", "io", "рф", "su", "info", "biz", "me", "app", "dev", "ai", "co", "uk", "de",
        "fr", "by", "kz", "ua", "tv", "pro", "online", "site", "store", "edu", "gov", "eu", "us", "xyz", "tech",
    )

    private val SCHEME = Regex("""(?i)(?:https?://|www\.)[^\s<>"«»]+""")
    private val BARE = Regex(
        """(?i)(?<![\p{L}\p{N}@._/-])(?:[\p{L}\p{N}-]+\.)+(?:${ZONES.joinToString("|")})(?![\p{L}\p{N}-])(?:/[^\s<>"«»]*)?""",
    )

    /** Адреса в тексте — места по возрастанию, без пересечений. */
    fun find(text: String): List<IntRange> {
        val found = mutableListOf<IntRange>()
        for (m in SCHEME.findAll(text)) found += trimmed(text, m.range)
        for (m in BARE.findAll(text)) {
            val r = trimmed(text, m.range)
            if (found.none { it.first <= r.last && r.first <= it.last }) found += r
        }
        return found.filter { !it.isEmpty() }.sortedBy { it.first }
    }

    private fun trimmed(text: String, r: IntRange): IntRange {
        var end = r.last
        while (end > r.first && text[end] in ".,;:!?)]}'\"»") end--
        return r.first..end
    }

    /** Адрес для открытия: без схемы — с `https://`. */
    fun openable(url: String): String = if (url.startsWith("http://", true) || url.startsWith("https://", true)) url else "https://$url"
}
