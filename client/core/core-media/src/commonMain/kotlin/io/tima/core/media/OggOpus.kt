package io.tima.core.media

/**
 * Opus в Ogg (RFC 7845) — контейнер голосового (ПЛАН-(ВМ) Р1). Свой, а не библиотечный: нужны
 * две вещи — разложить пакеты Opus по страницам и собрать их обратно, это сотня строк, а не
 * зависимость.
 *
 * Время в Ogg Opus всегда в отсчётах 48 кГц, с какой бы частотой ни писал микрофон: так велит
 * RFC 7845 §4. [preSkip] — сколько отсчётов от начала выбросить (задержка кодера), позиция
 * последней страницы обрезает хвост последнего пакета.
 */
object OggOpus {

    /** Разобранный файл: пакеты Opus по порядку и сколько отсчётов 48 кГц в нём слышно. */
    class Stream(val packets: List<ByteArray>, val preSkip: Int, val samples: Long, val inputRate: Int) {
        val durationMs: Long get() = samples / 48
    }

    private const val SERIAL = 0x54494D41 // «TIMA»

    /**
     * Собрать файл. [packets] — пакеты по 20 мс, [samples] — сколько отсчётов 48 кГц записано
     * на самом деле (без задержки кодера), [inputRate] — частота микрофона (только для сведения).
     */
    fun write(packets: List<ByteArray>, preSkip: Int, samples: Long, inputRate: Int): ByteArray {
        val out = Bytes()
        var sequence = 0
        val head = Bytes().apply {
            ascii("OpusHead"); u8(1); u8(1); u16(preSkip); u32(inputRate.toLong()); u16(0); u8(0)
        }.toArray()
        val tags = Bytes().apply {
            ascii("OpusTags"); u32(VENDOR.length.toLong()); ascii(VENDOR); u32(0)
        }.toArray()
        page(out, listOf(head), granule = 0, sequence = sequence++, flags = BOS)
        page(out, listOf(tags), granule = 0, sequence = sequence++, flags = if (packets.isEmpty()) EOS else 0)
        val end = preSkip + samples
        var granule = preSkip.toLong()
        var i = 0
        while (i < packets.size) {
            // Страница — пока таблица кусков не переполнена: у пакета 20 мс кусок один-два.
            val onPage = mutableListOf<ByteArray>()
            var segments = 0
            while (i < packets.size) {
                val need = packets[i].size / 255 + 1
                if (segments + need > 255 || (onPage.isNotEmpty() && onPage.size >= PACKETS_PER_PAGE)) break
                onPage += packets[i]
                segments += need
                granule += packetSamples(packets[i])
                i++
            }
            val last = i == packets.size
            page(out, onPage, granule = if (last) end else minOf(granule, end), sequence = sequence++, flags = if (last) EOS else 0)
        }
        return out.toArray()
    }

    /** Разобрать файл; не Ogg Opus или битый — `null`. */
    fun read(bytes: ByteArray): Stream? = runCatching {
        val packets = mutableListOf<ByteArray>()
        var current = Bytes()
        var pos = 0
        var lastGranule = 0L
        while (pos + 27 <= bytes.size) {
            if (bytes[pos] != 'O'.code.toByte() || bytes[pos + 1] != 'g'.code.toByte() ||
                bytes[pos + 2] != 'g'.code.toByte() || bytes[pos + 3] != 'S'.code.toByte()
            ) return null
            val granule = le64(bytes, pos + 6)
            val count = bytes[pos + 26].toInt() and 0xFF
            var data = pos + 27 + count
            for (s in 0 until count) {
                val len = bytes[pos + 27 + s].toInt() and 0xFF
                current.raw(bytes, data, len)
                data += len
                if (len < 255) {
                    packets += current.toArray()
                    current = Bytes()
                }
            }
            if (granule >= 0) lastGranule = granule
            pos = data
        }
        if (packets.size < 2) return null
        val head = packets[0]
        if (head.size < 19 || head.decodeToString(0, 8) != "OpusHead") return null
        val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
        val rate = le32(head, 12).toInt()
        val audio = packets.drop(2)
        Stream(audio, preSkip, maxOf(0L, lastGranule - preSkip), rate)
    }.getOrNull()

    /** Сколько отсчётов 48 кГц в пакете (RFC 6716 §3.1): по оглавлению пакета. */
    fun packetSamples(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xFF
        val config = toc shr 3
        val frame = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3]
            config < 16 -> if (config and 1 == 0) 480 else 960
            else -> intArrayOf(120, 240, 480, 960)[config and 3]
        }
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size > 1) packet[1].toInt() and 0x3F else 1
        }
        return frame * frames
    }

    private const val VENDOR = "TIMA"
    private const val BOS = 0x02
    private const val EOS = 0x04
    private const val PACKETS_PER_PAGE = 50 // секунда звука на странице

    private fun page(out: Bytes, packets: List<ByteArray>, granule: Long, sequence: Int, flags: Int) {
        val lacing = Bytes()
        for (p in packets) {
            repeat(p.size / 255) { lacing.u8(255) }
            lacing.u8(p.size % 255)
        }
        val header = Bytes().apply {
            ascii("OggS"); u8(0); u8(flags); u64(granule); u32(SERIAL.toLong()); u32(sequence.toLong()); u32(0)
            u8(lacing.size); raw(lacing.toArray(), 0, lacing.size)
        }
        val page = Bytes().apply {
            raw(header.toArray(), 0, header.size)
            for (p in packets) raw(p, 0, p.size)
        }.toArray()
        val crc = crc(page)
        page[22] = crc.toByte(); page[23] = (crc ushr 8).toByte(); page[24] = (crc ushr 16).toByte(); page[25] = (crc ushr 24).toByte()
        out.raw(page, 0, page.size)
    }

    private val CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** CRC страницы Ogg: многочлен 0x04C11DB7, без отражения, начальное 0. */
    fun crc(page: ByteArray): Int {
        var c = 0
        for (b in page) c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun le64(b: ByteArray, at: Int): Long = le32(b, at) or (le32(b, at + 4) shl 32)

    /** Растущий буфер байтов с записью в порядке little-endian. */
    private class Bytes {
        private var buf = ByteArray(256)
        var size = 0
            private set

        private fun room(n: Int) {
            if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
        }

        fun u8(v: Int) { room(1); buf[size++] = v.toByte() }
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
        fun u64(v: Long) { u32(v and 0xFFFFFFFFL); u32(v ushr 32) }
        fun ascii(s: String) { for (c in s) u8(c.code) }
        fun raw(src: ByteArray, from: Int, len: Int) { room(len); src.copyInto(buf, size, from, from + len); size += len }
        fun toArray(): ByteArray = buf.copyOf(size)
    }
}
