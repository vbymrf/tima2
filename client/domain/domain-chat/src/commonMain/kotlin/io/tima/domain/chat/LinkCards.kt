package io.tima.domain.chat

/**
 * Карточка ссылки (Решения §4): название, описание и картинка с сайта. **Делает клиент
 * отправителя** — получатель ничего не скачивает, карточка едет внутри тела.
 */
data class LinkCard(val title: String, val description: String, val image: ByteArray?) {
    override fun equals(other: Any?): Boolean =
        other is LinkCard && title == other.title && description == other.description && image.contentEqualsOrNull(other.image)

    override fun hashCode(): Int = title.hashCode() * 31 + description.hashCode()

    private fun ByteArray?.contentEqualsOrNull(other: ByteArray?) =
        if (this == null) other == null else other != null && contentEquals(other)
}

/** Порт: прочитать служебные строки страницы. Нельзя или не вышло — `null`. */
fun interface LinkCards {
    suspend fun card(url: String): LinkCard?
}

/**
 * Куда клиент **не ходит** за карточкой (Решения §4, «ссылка во внутреннюю сеть — запрос
 * запрещён»): не `http`/`https`, своё устройство, локальная и частная сеть по адресу в ссылке.
 *
 * Проверяется адрес, записанный в ссылке; имя, которое DNS превратит в частный адрес, так не
 * поймать — это остаток, записанный в журнале работ.
 */
object LinkSafety {

    fun allowed(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = hostOf(url) ?: return false
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return false
        if (host.startsWith("[")) return !privateV6(host.trim('[', ']'))
        ipv4(host)?.let { return !privateV4(it) }
        // Одно слово без точки — имя внутри сети.
        return '.' in host
    }

    /** Имя узла из ссылки, в нижнем регистре, без порта и учётных данных. */
    fun hostOf(url: String): String? {
        val rest = url.substringAfter("://", "")
        if (rest.isEmpty()) return null
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return host.lowercase()
    }

    private fun ipv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return null }
    }

    private fun privateV4(ip: List<Int>): Boolean {
        val (a, b) = ip
        return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 100 && b in 64..127) || a >= 224
    }

    private fun privateV6(ip: String): Boolean {
        val s = ip.lowercase()
        return s == "::1" || s == "::" || s.startsWith("fc") || s.startsWith("fd") || s.startsWith("fe80") || s.startsWith("::ffff:")
    }
}
