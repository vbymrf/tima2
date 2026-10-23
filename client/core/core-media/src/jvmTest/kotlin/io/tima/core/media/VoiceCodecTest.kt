package io.tima.core.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Голосовое (ПЛАН-(ВМ) ВМ2, ВМ4): Opus в Ogg туда и обратно, волна, ускорение, проигрывание. */
class VoiceCodecTest {

    /** «Речь» — гудок с меняющейся громкостью: слоги и паузы. */
    private fun speech(rate: Int, ms: Int): ShortArray = ShortArray(rate * ms / 1000) { i ->
        val t = i.toDouble() / rate
        val syllable = if ((t * 4).toInt() % 3 == 2) 0.05 else 1.0
        (sin(2 * PI * 220 * t) * 8000 * syllable).toInt().toShort()
    }

    private fun encode(rate: Int, ms: Int): Pair<ByteArray, VoiceWave> {
        val enc = assertNotNull(VoiceDevices.encoder(rate))
        val pcm = speech(rate, ms)
        val wave = VoiceWave()
        val packets = (0 until pcm.size / enc.frame).map { f ->
            val frame = pcm.copyOfRange(f * enc.frame, (f + 1) * enc.frame)
            wave.add(frame, frame.size)
            enc.encode(frame)
        }
        val samples48 = packets.size.toLong() * 960
        return OggOpus.write(packets, enc.preSkip, samples48, rate) to wave
    }

    @Test
    fun контрольная_сумма_страницы_как_в_ogg() {
        // Проверочное значение CRC Ogg (многочлен 0x04C11DB7, начальное 0) для «123456789».
        assertEquals(0x89A1897F.toInt(), OggOpus.crc("123456789".encodeToByteArray()))
    }

    @Test
    fun opus_в_ogg_туда_и_обратно_на_48_и_16_кгц() {
        for (rate in intArrayOf(48_000, 16_000)) {
            val (ogg, _) = encode(rate, 3000)
            val stream = assertNotNull(OggOpus.read(ogg), "файл $rate Гц не читается")
            assertEquals(150, stream.packets.size, "пакетов по 20 мс в 3 с")
            assertEquals(3000, stream.durationMs, "длительность")
            assertEquals(rate, stream.inputRate)
            // Около 24 кбит/с: 3 с — порядка 9 КБ, а не сотни.
            assertTrue(ogg.size in 3_000..20_000, "размер ${ogg.size}")
            val dec = assertNotNull(VoiceDevices.decoder())
            val out = ShortArray(5760)
            var total = 0L
            var loud = 0
            for (p in stream.packets) {
                val n = dec.decode(p, out)
                total += n
                for (i in 0 until n) if (abs(out[i].toInt()) > 2000) loud++
            }
            assertEquals(150L * 960, total, "раскодировано отсчётов 48 кГц")
            assertTrue(loud > 10_000, "звук после декодера не пропал: громких отсчётов $loud")
        }
    }

    @Test
    fun страницы_несут_верную_контрольную_сумму() {
        val (ogg, _) = encode(48_000, 2000)
        var pos = 0
        var pages = 0
        while (pos < ogg.size) {
            val count = ogg[pos + 26].toInt() and 0xFF
            var len = 27 + count
            for (s in 0 until count) len += ogg[pos + 27 + s].toInt() and 0xFF
            val page = ogg.copyOfRange(pos, pos + len)
            val stored = (page[22].toInt() and 0xFF) or ((page[23].toInt() and 0xFF) shl 8) or
                ((page[24].toInt() and 0xFF) shl 16) or ((page[25].toInt() and 0xFF) shl 24)
            for (i in 22..25) page[i] = 0
            assertEquals(OggOpus.crc(page), stored, "страница $pages")
            pos += len
            pages++
        }
        assertTrue(pages >= 3, "заголовок, теги и звук — не меньше трёх страниц")
        // Первая — начало потока, последняя — конец.
        assertEquals(0x02, ogg[5].toInt())
    }

    @Test
    fun волна_шестьдесят_четыре_доли_и_паузы_тише_слогов() {
        val (_, wave) = encode(48_000, 3000)
        val bars = wave.bars()
        assertEquals(VoiceWave.BARS, bars.size)
        val values = bars.map { it.toInt() and 0xFF }
        assertEquals(255, values.max(), "самая громкая доля — во всю высоту")
        assertTrue(values.min() < 100, "паузы видно: самая тихая доля ${values.min()}")
        // Тишина — плоский низкий рисунок, а не растянутый шум.
        val silent = VoiceWave().apply { add(ShortArray(960), 960) }.bars()
        assertTrue(silent.all { (it.toInt() and 0xFF) < 10 })
    }

    @Test
    fun ускорение_укорачивает_звук_и_не_глушит_его() {
        val pcm = speech(48_000, 4000)
        for (speed in floatArrayOf(1.5f, 2f)) {
            val stretch = TimeStretch(48_000, speed)
            var out = 0
            var loud = 0
            var at = 0
            while (at < pcm.size) {
                val n = minOf(960, pcm.size - at)
                val piece = stretch.put(pcm.copyOfRange(at, at + n), n)
                out += piece.size
                loud += piece.count { abs(it.toInt()) > 2000 }
                at += n
            }
            val expected = pcm.size / speed
            assertTrue(abs(out - expected) < 48_000 * 0.1, "×$speed: вышло $out отсчётов, ждали около $expected")
            assertTrue(loud > out / 10, "×$speed: звук не пропал")
        }
    }

    @Test
    fun проигрывание_до_конца_и_с_середины() = runBlocking {
        val (ogg, _) = encode(48_000, 2000)
        val stream = assertNotNull(OggOpus.read(ogg))
        val written = mutableListOf<Int>()
        val sink = object : PcmSink {
            override val rate = 48_000
            override fun write(buf: ShortArray, n: Int) { synchronized(written) { written += n } }
            override fun flush() {}
            override fun close() {}
        }
        val player = VoicePlayback(stream, this, sinkOf = { sink })
        val ended = CompletableDeferred<Unit>()
        player.onEnded = { ended.complete(Unit) }
        player.play()
        withTimeout(10_000) { ended.await() }
        assertEquals(2000, player.state.value.positionMs)
        assertTrue(player.state.value.ended)
        val full = synchronized(written) { written.sum() }
        assertTrue(abs(full - 2000 * 48) <= 960, "сыграно $full отсчётов, ждали ${2000 * 48}")

        // С середины на 2×: вторая секунда за полсекунды звука.
        synchronized(written) { written.clear() }
        val again = CompletableDeferred<Unit>()
        player.onEnded = { again.complete(Unit) }
        player.setSpeed(2f)
        player.seek(1000)
        player.play()
        withTimeout(10_000) { again.await() }
        val half = synchronized(written) { written.sum() }
        assertTrue(abs(half - 1000 * 48 / 2) < 48 * 120, "с середины на 2×: $half отсчётов")
        player.release()
    }
}
