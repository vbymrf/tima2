package io.tima.core.ui

/**
 * QR-код: строка → матрица тёмных и светлых клеток.
 *
 * **Зачем свой, а не библиотека.** Кодировщик нужен на всех четырёх платформах, а
 * готовые — платформенные (`zxing` на Android, `CoreImage` на Apple): это значит четыре
 * реализации вместо одной и четыре разных повода не сойтись. Сам алгоритм при этом
 * закрытый и проверяемый: одна строка на входе, одна матрица на выходе, сверяется
 * векторами (`QrCodeTest`), посчитанными сторонним кодировщиком.
 *
 * **Что поддерживается и почему именно это.** Байтовый режим, уровень коррекции M,
 * версии 1–15. Наш единственный настоящий вход — код привязки устройства (~260 знаков,
 * версия 12), и он не цифровой и не буквенно-цифровой: в нём двоеточия, косые и base64url.
 * Уровень M (восстанавливает ~15 %) — обычное умолчание для кода, который снимают с
 * экрана, а не с наклейки на морозе. Версии дальше 15 не нужны: 15-я держит 412 байт, а
 * то, что не влезло, — это не «взять версию побольше», а «не показывать человеку то, что
 * не отсканируется».
 *
 * Раскладка целиком из ISO/IEC 18004: выравнивающие узоры, чередование блоков,
 * BCH-код информации о версии и формате, выбор маски по штрафам. Всё это **не наши
 * решения**, и менять их нельзя — сканер чужой.
 */
object QrCode {

    /**
     * @param маскаНасильно номер маски вместо выбора по штрафам. Нужен проверке: сверять
     *   матрицу с эталоном по отдельным маскам — единственный способ отличить «неверно
     *   считаем штрафы» от «неверно собираем матрицу». Приложение это не задаёт.
     * @return матрица без «тихой зоны» (её добавляет рисующий: это отступ, а не данные),
     *   либо `null` — данные не влезли даже в 15-ю версию.
     */
    fun matrix(data: String, forcedMask: Int? = null): QrMatrix? {
        val bytes = data.encodeToByteArray()
        val version = pickVersion(bytes.size) ?: return null
        val words = wordCode(bytes, version)
        val size = 17 + 4 * version

        val field = Field(size)
        field.patterns(version)
        val map = field.taken.copyOf()
        field.data(words)

        // Восемь масок и штрафы за каждую — не оптимизация, а требование стандарта:
        // сканер калибруется по узорам, и полосы, случайно похожие на узор поиска,
        // сбивают его. Выбирается наименьший штраф, при равенстве — меньший номер.
        // Штраф считается ДО записи информации о формате и версии: в этот момент их
        // клетки светлые. Так делает эталонная реализация, и так честнее — маска
        // выбирается по тому, что маскируется, а служебные поля от неё не зависят.
        // Обе договорённости дают читаемый код (номер маски записан в самом формате),
        // но вектор проверяет только ту, по которой посчитан.
        var bestMask = forcedMask ?: 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in if (forcedMask != null) forcedMask..forcedMask else 0..7) {
            field.mask(mask, map)
            val penalty = field.penalty()
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
            }
            field.mask(mask, map) // XOR обратим: снимаем ту же маску
        }
        field.mask(bestMask, map)
        field.service(version, bestMask)

        return QrMatrix(size, field.cells)
    }

    // ── подбор версии ───────────────────────────────────────────────────────

    private fun pickVersion(bytes: Int): Int? {
        for (version in 1..CAPACITY.size) {
            val structure = CAPACITY[version - 1]
            val bits = 4 + bitCounter(version) + 8 * bytes
            if (bits <= structure.wordData * 8) return version
        }
        return null
    }

    /** Длина счётчика знаков: до 9-й версии 8 бит, дальше 16. Так в стандарте. */
    private fun bitCounter(version: Int): Int = if (version <= 9) 8 else 16

    // ── кодовые слова ───────────────────────────────────────────────────────

    /**
     * Данные и коррекция, **чередованные по блокам**.
     *
     * Чередование — не украшение: длинная царапина по коду попадёт по одному-двум словам
     * каждого блока, а не съест один блок целиком. Блок восстанавливается, если пострадало
     * не больше половины его слов коррекции.
     */
    private fun wordCode(bytes: ByteArray, version: Int): IntArray {
        val structure = CAPACITY[version - 1]

        val bits = LineBit()
        bits.add(0b0100, 4) // байтовый режим
        bits.add(bytes.size, bitCounter(version))
        for (b in bytes) bits.add(b.toInt() and 0xFF, 8)

        // Завершитель — до четырёх нулей, и не больше, чем осталось места.
        val totalBit = structure.wordData * 8
        bits.add(0, minOf(4, totalBit - bits.length))
        while (bits.length % 8 != 0) bits.add(0, 1)
        // Добивка чередующимися 0xEC/0x11 — значения из стандарта, а не произвольные.
        var padding = 0
        while (bits.length < totalBit) {
            bits.add(if (padding++ % 2 == 0) 0xEC else 0x11, 8)
        }

        val words = bits.bytes()
        val dataBlocks = mutableListOf<IntArray>()
        val correctionBlocks = mutableListOf<IntArray>()
        var offset = 0
        for (group in structure.groups) {
            repeat(group.blocks) {
                val block = IntArray(group.words) { words[offset + it] }
                offset += group.words
                dataBlocks += block
                correctionBlocks += correction(block, structure.correctionOnBlock)
            }
        }

        val result = ArrayList<Int>(structure.totalWords)
        val longMost = dataBlocks.maxOf { it.size }
        for (i in 0 until longMost) {
            for (block in dataBlocks) if (i < block.size) result += block[i]
        }
        for (i in 0 until structure.correctionOnBlock) {
            for (block in correctionBlocks) result += block[i]
        }
        return result.toIntArray()
    }

    /** Рид–Соломон над GF(256): остаток от деления на порождающий многочлен. */
    private fun correction(data: IntArray, words: Int): IntArray {
        val generating = polynomialGenerating(words)
        val remainder = IntArray(data.size + words)
        data.copyInto(remainder)
        for (i in data.indices) {
            val coef = remainder[i]
            if (coef == 0) continue
            val log = LOG[coef]
            for (j in 0..words) {
                remainder[i + j] = remainder[i + j] xor EXP[(LOG[generating[j]] + log) % 255]
            }
        }
        return IntArray(words) { remainder[data.size + it] }
    }

    private fun polynomialGenerating(words: Int): IntArray {
        var polynomial = intArrayOf(1)
        for (i in 0 until words) {
            val new = IntArray(polynomial.size + 1)
            for (j in polynomial.indices) {
                new[j] = new[j] xor polynomial[j]
                new[j + 1] = new[j + 1] xor multiply(polynomial[j], EXP[i])
            }
            polynomial = new
        }
        return polynomial
    }

    private fun multiply(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[(LOG[a] + LOG[b]) % 255]

    // ── поле ────────────────────────────────────────────────────────────────

    /**
     * Матрица во время сборки: сами клетки и карта занятого.
     *
     * Карта нужна потому, что маска накладывается **только на данные**: замаскируй узор
     * поиска — и сканеру не за что зацепиться.
     */
    private class Field(val size: Int) {
        val cells = BooleanArray(size * size)
        val taken = BooleanArray(size * size)

        fun put(x: Int, y: Int, dark: Boolean) {
            cells[y * size + x] = dark
            taken[y * size + x] = true
        }

        fun take(x: Int, y: Int): Boolean = cells[y * size + x]
        fun taken(x: Int, y: Int): Boolean = taken[y * size + x]

        fun patterns(version: Int) {
            for ((cX, cY) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
                for (dy in -1..7) for (dx in -1..7) {
                    val x = cX + dx
                    val y = cY + dy
                    if (x !in 0 until size || y !in 0 until size) continue
                    val inside = dx in 0..6 && dy in 0..6
                    val dark = inside && (
                        dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4)
                        )
                    put(x, y, dark)
                }
            }

            for (i in 8 until size - 8) {
                put(i, 6, i % 2 == 0)
                put(6, i, i % 2 == 0)
            }

            if (version >= 2) {
                val places = ALIGNMENT[version - 2]
                for (cY in places) for (cX in places) {
                    // Углы, где уже стоят узоры поиска, пропускаются.
                    if ((cX <= 8 && cY <= 8) || (cX <= 8 && cY >= size - 9) ||
                        (cX >= size - 9 && cY <= 8)
                    ) {
                        continue
                    }
                    for (dy in -2..2) for (dx in -2..2) {
                        val edge = dx == -2 || dx == 2 || dy == -2 || dy == 2
                        put(cX + dx, cY + dy, edge || (dx == 0 && dy == 0))
                    }
                }
            }

            // Тёмный модуль: одна клетка, всегда тёмная. Так в стандарте.
            put(8, size - 8, true)

            // Место информации о формате занимается заранее, чтобы данные его обошли.
            for (i in 0..8) {
                if (!taken(i, 8)) put(i, 8, false)
                if (!taken(8, i)) put(8, i, false)
            }
            for (i in 0..7) {
                put(size - 1 - i, 8, false)
                put(8, size - 1 - i, false)
            }

            // Место номера версии тоже резервируется светлым: он пишется после выбора
            // маски, а до тех пор не должен влиять на штрафы.
            if (version >= 7) {
                for (i in 0..17) {
                    put(i / 3, size - 11 + i % 3, false)
                    put(size - 11 + i % 3, i / 3, false)
                }
            }
        }

        /** Данные идут снизу вверх парами столбцов, обходя занятое и шестой столбец. */
        fun data(words: IntArray) {
            var bit = 0
            var x = size - 1
            var up = true
            while (x > 0) {
                if (x == 6) x-- // столбец синхронизации данными не заполняется
                for (i in 0 until size) {
                    val y = if (up) size - 1 - i else i
                    for (dx in 0..1) {
                        val cx = x - dx
                        if (taken(cx, y)) continue
                        val value = if (bit < words.size * 8) {
                            (words[bit / 8] shr (7 - bit % 8)) and 1 == 1
                        } else {
                            false
                        }
                        put(cx, y, value)
                        bit++
                    }
                }
                up = !up
                x -= 2
            }
        }

        /** Маска накладывается XOR — и снимается тем же вызовом. */
        fun mask(number: Int, patternMap: BooleanArray) {
            for (y in 0 until size) for (x in 0 until size) {
                if (patternMap[y * size + x]) continue
                if (condition(number, x, y)) {
                    cells[y * size + x] = !cells[y * size + x]
                }
            }
        }

        private fun condition(number: Int, x: Int, y: Int): Boolean = when (number) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (y / 2 + x / 3) % 2 == 0
            5 -> (x * y) % 2 + (x * y) % 3 == 0
            6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
            else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        }

        /** Информация о формате, тёмный модуль и номер версии — всё после выбора маски. */
        fun service(version: Int, mask: Int) {
            val info = formatBCH(mask)
            // Первая копия — вокруг узора поиска в левом верхнем углу. Порядок именно
            // такой: биты 0–5 идут по СТОЛБЦУ, 9–14 по СТРОКЕ, и между ними три клетки
            // в изломе. Перепутай столбец со строкой — и различия будут ровно в двух
            // линиях, восьмой строке и восьмом столбце; сканер такой код не прочтёт.
            for (i in 0..5) put(8, i, bit(info, i))
            put(8, 7, bit(info, 6))
            put(8, 8, bit(info, 7))
            put(7, 8, bit(info, 8))
            for (i in 9..14) put(14 - i, 8, bit(info, i))

            for (i in 0..7) put(size - 1 - i, 8, bit(info, i))
            for (i in 8..14) put(8, size - 15 + i, bit(info, i))
            put(8, size - 8, true) // тёмный модуль возвращаем на место

            if (version >= 7) {
                val number = versionBCH(version)
                for (i in 0..17) {
                    val bit = (number shr i) and 1 == 1
                    put(i / 3, size - 11 + i % 3, bit)
                    put(size - 11 + i % 3, i / 3, bit)
                }
            }
        }

        private fun bit(value: Int, i: Int): Boolean = (value shr i) and 1 == 1

        /**
         * Штраф маски — четыре правила стандарта.
         *
         * Смысл у них один: узор, случайно похожий на узор поиска, ломает сканер, а
         * большие однотонные пятна и перекос чёрного к белому ухудшают распознавание на
         * плохой картинке.
         */
        fun penalty(): Int {
            var result = 0

            // 1. Полосы одного цвета длиной пять и больше.
            for (y in 0 until size) {
                result += strips { x -> take(x, y) }
            }
            for (x in 0 until size) {
                result += strips { y -> take(x, y) }
            }

            // 2. Квадраты 2×2 одного цвета.
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = take(x, y)
                if (c == take(x + 1, y) && c == take(x, y + 1) && c == take(x + 1, y + 1)) {
                    result += 3
                }
            }

            // 3. Последовательность 1:1:3:1:1 (тёмный-светлый-тёмный-тёмный-тёмный-
            //    светлый-тёмный), с четырьмя светлыми до или после. Это и есть ложный
            //    узор поиска: сканер калибруется именно по такому соотношению.
            for (i in 0 until size) {
                result += patternFalse(BooleanArray(size) { take(it, i) })
                result += patternFalse(BooleanArray(size) { take(i, it) })
            }

            // 4. Перекос доли тёмного от половины. Считается в сотых долях процента:
            //    округли раньше — и на границе получится другой штраф, то есть другая
            //    маска и другая матрица.
            val dark = cells.count { it }
            val hundredths = dark * 10_000 / (size * size)
            result += 10 * (kotlin.math.abs(hundredths - 5_000) / 500)

            return result
        }

        private inline fun strips(color: (Int) -> Boolean): Int {
            var result = 0
            var length = 1
            for (i in 1 until size) {
                if (color(i) == color(i - 1)) {
                    length++
                } else {
                    if (length >= 5) result += 3 + (length - 5)
                    length = 1
                }
            }
            if (length >= 5) result += 3 + (length - 5)
            return result
        }

        /**
         * Сколько раз в полосе встретился ложный узор поиска.
         *
         * Правило стандарта: образец из семи клеток, у которого с одной из сторон четыре
         * светлых (край кода считается светлым). Нашли — 40 штрафа и продолжаем со
         * следующей клетки за образцом; не нашли светлых — продолжаем с середины
         * образца, потому что она может начать следующее совпадение.
         */
        private fun patternFalse(strip: BooleanArray): Int {
            val sample = booleanArrayOf(true, false, true, true, true, false, true)
            var result = 0
            var with = 0
            while (with + 7 <= strip.size) {
                if (!matched(strip, with, sample)) {
                    with++
                    continue
                }
                val after = with + 7
                val lightUntil = (maxOf(with - 4, 0) until with).none { strip[it] }
                val lightAfter = (after until minOf(after + 4, strip.size)).none { strip[it] }
                if (lightUntil || lightAfter) {
                    result += 40
                    with = after
                } else {
                    // Середина образца может начать следующее совпадение:
                    // …тёмный светлый ТЁМНЫЙ тёмный тёмный светлый тёмный…
                    with += 4
                }
            }
            return result
        }

        private fun matched(strip: BooleanArray, with: Int, sample: BooleanArray): Boolean {
            for (i in sample.indices) if (strip[with + i] != sample[i]) return false
            return true
        }
    }

    // ── BCH ─────────────────────────────────────────────────────────────────

    /**
     * Информация о формате: уровень коррекции и номер маски, защищённые BCH(15,5).
     *
     * Маска 0x5412 — из стандарта. Без неё код со всеми нулями в формате выглядел бы
     * пустым полем, и сканер не отличил бы его от мусора.
     */
    private fun formatBCH(mask: Int): Int {
        val data = (УРОВЕНЬ_M shl 3) or mask
        var value = data shl 10
        while (bitness(value) >= 11) {
            value = value xor (0x537 shl (bitness(value) - 11))
        }
        return ((data shl 10) or value) xor 0x5412
    }

    /** Номер версии, защищённый BCH(18,6). Нужен с 7-й версии — до неё его негде взять. */
    private fun versionBCH(version: Int): Int {
        var value = version shl 12
        while (bitness(value) >= 13) {
            value = value xor (0x1F25 shl (bitness(value) - 13))
        }
        return (version shl 12) or value
    }

    private fun bitness(value: Int): Int {
        var n = 0
        var v = value
        while (v != 0) {
            n++
            v = v shr 1
        }
        return n
    }

    // ── таблицы стандарта ───────────────────────────────────────────────────

    private const val УРОВЕНЬ_M = 0b00

    private class Group(val blocks: Int, val words: Int)

    private class Structure(val correctionOnBlock: Int, val groups: List<Group>) {
        val wordData: Int = groups.sumOf { it.blocks * it.words }
        val totalWords: Int = wordData + groups.sumOf { it.blocks } * correctionOnBlock
    }

    /** Версии 1–15, уровень M. Числа из ISO/IEC 18004, таблица 13. */
    private val CAPACITY = listOf(
        Structure(10, listOf(Group(1, 16))),
        Structure(16, listOf(Group(1, 28))),
        Structure(26, listOf(Group(1, 44))),
        Structure(18, listOf(Group(2, 32))),
        Structure(24, listOf(Group(2, 43))),
        Structure(16, listOf(Group(4, 27))),
        Structure(18, listOf(Group(4, 31))),
        Structure(22, listOf(Group(2, 38), Group(2, 39))),
        Structure(22, listOf(Group(3, 36), Group(2, 37))),
        Structure(26, listOf(Group(4, 43), Group(1, 44))),
        Structure(30, listOf(Group(1, 50), Group(4, 51))),
        Structure(22, listOf(Group(6, 36), Group(2, 37))),
        Structure(22, listOf(Group(8, 37), Group(1, 38))),
        Structure(24, listOf(Group(4, 40), Group(5, 41))),
        Structure(24, listOf(Group(5, 41), Group(5, 42))),
    )

    /** Середины выравнивающих узоров, версии 2–15. */
    private val ALIGNMENT = listOf(
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50),
        intArrayOf(6, 30, 54),
        intArrayOf(6, 32, 58),
        intArrayOf(6, 34, 62),
        intArrayOf(6, 26, 46, 66),
        intArrayOf(6, 26, 48, 70),
    )

    /** Таблицы GF(256) по многочлену 0x11D — тому же, что в стандарте. */
    private val EXP = IntArray(256)
    private val LOG = IntArray(256)

    init {
        var value = 1
        for (i in 0 until 255) {
            EXP[i] = value
            LOG[value] = i
            value = value shl 1
            if (value and 0x100 != 0) value = value xor 0x11D
        }
        EXP[255] = EXP[0]
    }
}

/** Готовая матрица: [размер] клеток в стороне, тёмные — те, что рисуются. */
class QrMatrix internal constructor(val size: Int, private val cells: BooleanArray) {

    fun dark(x: Int, y: Int): Boolean = cells[y * size + x]

    /** Строки нулей и единиц — так матрицу сверяют с вектором. */
    fun lines(): List<String> = (0 until size).map { y ->
        (0 until size).joinToString("") { x -> if (dark(x, y)) "1" else "0" }
    }
}

/** Битовая строка: складывает биты слева направо и отдаёт байты. */
private class LineBit {
    private val bits = ArrayList<Boolean>()
    val length: Int get() = bits.size

    fun add(value: Int, howMany: Int) {
        for (i in howMany - 1 downTo 0) bits += (value shr i) and 1 == 1
    }

    fun bytes(): IntArray = IntArray(bits.size / 8) { i ->
        var b = 0
        for (j in 0 until 8) b = (b shl 1) or if (bits[i * 8 + j]) 1 else 0
        b
    }
}
