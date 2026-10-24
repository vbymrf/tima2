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
 * Здесь проверяется адрес, записанный в ссылке; имя, которое DNS превращает в частный адрес,
 * ловит `resolvesInside` в `core-network` перед каждым запросом (и после каждого перехода).
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

    /**
     * Домен для показа (Решения §4): из самой ссылки, без `www.`, **буквами, как его поймёт человек**:
     * `xn--80aa4ar0b.xn--p1ai` — «кавказия.рф» (заказчик 2026-10-24: «фактически скрываем адрес»). Чужой
     * сайт под видом своего («раураl.com» с русскими «а») выдают не `xn--…`, а цвет: не латинские буквы
     * окно рисует другим тоном ([nonLatin]). Испорченная часть `xn--…` остаётся как есть.
     */
    fun displayHost(url: String): String {
        val host = (hostOf(url) ?: url.substringAfter("://").substringBefore('/')).removePrefix("www.")
        return host.split('.').joinToString(".") { label ->
            if (label.startsWith("xn--")) Punycode.decode(label.removePrefix("xn--"))?.takeIf { it.isNotEmpty() } ?: label else label
        }
    }

    /** Буква не латинская (кириллица, греческий и прочее) — её окно показывает другим тоном. */
    fun nonLatin(c: Char): Boolean = c.code >= 128

    /** Есть ли в имени буквы разных алфавитов — латиница вперемешку с кириллицей или греческим. */
    fun mixed(host: String): Boolean = host.split('.').any(::mixedScripts)

    private enum class Script { LATIN, CYRILLIC, GREEK, OTHER }

    private fun scriptOf(c: Char): Script? = when {
        c in 'a'..'z' || c in 'A'..'Z' -> Script.LATIN
        c in '\u0400'..'\u04FF' || c in '\u0500'..'\u052F' -> Script.CYRILLIC
        c in '\u0370'..'\u03FF' -> Script.GREEK
        c.isLetter() -> Script.OTHER
        else -> null // цифры, дефис — ничьи
    }

    private fun mixedScripts(label: String): Boolean = label.mapNotNull(::scriptOf).toSet().size > 1

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

/** Punycode (RFC 3492) — кодирование одной части имени для показа `xn--…`. */
internal object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128

    fun encode(input: String): String {
        val cps = input.lowercase().let { s -> buildList { var i = 0; while (i < s.length) { val c = s.codePointAtCompat(i); add(c); i += if (c > 0xFFFF) 2 else 1 } } }
        val out = StringBuilder()
        cps.filter { it < 128 }.forEach { out.append(it.toChar()) }
        val basic = out.length
        var handled = basic
        if (basic > 0) out.append('-')
        var n = INITIAL_N
        var delta = 0
        var bias = INITIAL_BIAS
        while (handled < cps.size) {
            val m = cps.filter { it >= n }.minOrNull() ?: break
            delta += (m - n) * (handled + 1)
            n = m
            for (c in cps) {
                if (c < n) delta++
                if (c == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = when { k <= bias -> TMIN; k >= bias + TMAX -> TMAX; else -> k - bias }
                        if (q < t) break
                        out.append(digit(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digit(q))
                    bias = adapt(delta, handled + 1, handled == basic)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return out.toString()
    }

    /** Часть имени из `xn--…` (без приставки) — обратно буквами; испорчена — `null`. */
    fun decode(input: String): String? {
        var n = INITIAL_N.toLong()
        var i = 0L
        var bias = INITIAL_BIAS
        val b = input.lastIndexOf('-')
        val out = mutableListOf<Int>()
        if (b > 0) for (c in input.substring(0, b)) if (c.code >= 128) return null else out += c.code
        var at = if (b > 0) b + 1 else 0
        while (at < input.length) {
            val old = i
            var w = 1L
            var k = BASE
            while (true) {
                if (at >= input.length) return null
                val d = value(input[at++]) ?: return null
                i += d * w
                val t = when { k <= bias -> TMIN; k >= bias + TMAX -> TMAX; else -> k - bias }
                if (d < t) break
                w *= (BASE - t)
                k += BASE
                if (w > Int.MAX_VALUE || i > Int.MAX_VALUE) return null
            }
            bias = adapt((i - old).toInt(), out.size + 1, old == 0L)
            n += i / (out.size + 1)
            i %= (out.size + 1)
            if (n > 0x10FFFF) return null
            out.add(i.toInt(), n.toInt())
            i++
        }
        return buildString {
            for (cp in out) {
                if (cp > 0xFFFF) {
                    val v = cp - 0x10000
                    append((0xD800 + (v shr 10)).toChar())
                    append((0xDC00 + (v and 0x3FF)).toChar())
                } else {
                    append(cp.toChar())
                }
            }
        }
    }

    private fun value(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0' + 26
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        else -> null
    }

    private fun digit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    private fun adapt(deltaIn: Int, points: Int, first: Boolean): Int {
        var delta = if (first) deltaIn / DAMP else deltaIn / 2
        delta += delta / points
        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN
            k += BASE
        }
        return k + (BASE - TMIN + 1) * delta / (delta + SKEW)
    }

    private fun String.codePointAtCompat(i: Int): Int {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            return ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00) + 0x10000
        }
        return c.code
    }
}
