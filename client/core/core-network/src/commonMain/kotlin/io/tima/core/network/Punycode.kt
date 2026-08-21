package io.tima.core.network

/**
 * Punycode (RFC 3492) — перевод имени хоста в ASCII-совместимую форму.
 *
 * **Зачем это в клиенте.** Домен стенда интернационализированный: `пацак.рф`. В TLS
 * и в заголовке `Host` едет **punycode** — `xn--80aa4ar0b.xn--p1ai`, — и сервер
 * отвечает только на него. Человек при этом вводит адрес кириллицей.
 *
 * Проверено на живом стенде 2026-08-20: по кириллическому имени соединение не
 * встаёт, по punycode встаёт. То есть без этого перевода приложение с введённым
 * вручную адресом просто не подключится, а сообщение об ошибке будет про TLS или
 * DNS — то есть укажет куда угодно, кроме настоящей причины.
 *
 * **Почему своя реализация, а не библиотека.** В общем коде Kotlin нет ни IDN, ни
 * `java.net.IDN`; на JVM он есть, на Kotlin/Native — нет. Алгоритм закрытый,
 * описан стандартом целиком и проверяется известными векторами, поэтому это тот
 * редкий случай, когда своё дешевле зависимости с `expect/actual` на две
 * платформы.
 *
 * Реализовано только **кодирование**: обратное направление клиенту не нужно —
 * человеку показывается то, что он ввёл, а на провод уходит ASCII.
 */
internal object Punycode {

    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128
    private const val PREFIX = "xn--"
    private const val DELIMITER = '-'

    /**
     * Переводит имя хоста целиком: метки, разделённые точками, обрабатываются
     * независимо — так требует стандарт, и так же ведут себя браузеры.
     *
     * Метка из одних ASCII-символов остаётся как есть, **без префикса**: иначе
     * `example.com` превратился бы в `xn--example.xn--com`, и это не адрес.
     */
    fun encodeHost(host: String): String =
        host.split('.').joinToString(".") { encodeLabel(it) }

    private fun encodeLabel(label: String): String {
        if (label.all { it.code < INITIAL_N }) return label
        return PREFIX + encodeNonAscii(label.toCodePoints())
    }

    /**
     * Ядро RFC 3492 §6.3. Имена переменных оставлены как в стандарте (`n`, `delta`,
     * `bias`, `h`, `q`, `t`): сверять реализацию с текстом стандарта важнее, чем
     * читать её как обычный код.
     */
    private fun encodeNonAscii(input: List<Int>): String {
        val out = StringBuilder()
        val basic = input.filter { it < INITIAL_N }
        basic.forEach { out.append(it.toChar()) }

        val b = basic.size
        var h = b
        if (b > 0) out.append(DELIMITER)

        var n = INITIAL_N
        var delta = 0
        var bias = INITIAL_BIAS

        while (h < input.size) {
            // Наименьший код, ещё не обработанный, — следующий «порог».
            val m = input.filter { it >= n }.min()
            delta += (m - n) * (h + 1)
            n = m
            for (c in input) {
                if (c < n) delta++
                if (c != n) continue
                var q = delta
                var k = BASE
                while (true) {
                    val t = when {
                        k <= bias -> TMIN
                        k >= bias + TMAX -> TMAX
                        else -> k - bias
                    }
                    if (q < t) break
                    out.append(digit(t + (q - t) % (BASE - t)))
                    q = (q - t) / (BASE - t)
                    k += BASE
                }
                out.append(digit(q))
                bias = adapt(delta, h + 1, h == b)
                delta = 0
                h++
            }
            delta++
            n++
        }
        return out.toString()
    }

    private fun adapt(deltaIn: Int, numPoints: Int, firstTime: Boolean): Int {
        var delta = if (firstTime) deltaIn / DAMP else deltaIn / 2
        delta += delta / numPoints
        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN
            k += BASE
        }
        return k + (BASE - TMIN + 1) * delta / (delta + SKEW)
    }

    /** 0..25 → `a`..`z`, 26..35 → `0`..`9`. */
    private fun digit(d: Int): Char =
        if (d < 26) ('a' + d) else ('0' + (d - 26))

    /**
     * UTF-16 в кодовые точки. В общем коде Kotlin нет `codePointAt`, а пары
     * surrogate обязаны склеиваться: иначе имя с символом вне BMP закодируется в
     * мусор, который сервер не узнает.
     */
    private fun String.toCodePoints(): List<Int> {
        val out = ArrayList<Int>(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
                val low = this[i + 1]
                out += 0x10000 + ((c.code - 0xD800) shl 10) + (low.code - 0xDC00)
                i += 2
            } else {
                out += c.code
                i++
            }
        }
        return out
    }
}
