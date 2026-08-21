package io.tima.crypto

import com.squareup.zstd.ZSTD_e_end
import com.squareup.zstd.getErrorName
import com.squareup.zstd.zstdCompressor
import com.squareup.zstd.zstdDecompressor

/**
 * Одноразовое сжатие и распаковка zstd поверх потокового API `zstd-kmp`.
 *
 * **Почему обёртка вообще есть.** До К2 здесь стоял `zstd-jni`, у которого есть
 * `Zstd.compress` и `Zstd.decompress` из одного вызова. Но у `zstd-jni` нет
 * таргетов Apple, а без них iOS не отправит ни одного сообщения: тело каждого
 * сжимается до шифрования. `com.squareup.zstd:zstd-kmp` таргеты Apple публикует,
 * но отдаёт **потоковый** API — прямую обёртку `ZSTD_compressStream2` и
 * `ZSTD_decompressStream`. Один вызов из него надо собрать.
 *
 * **Что изменилось в защите от бомбы — это важнее самой обёртки.** В `zstd-jni`
 * был `getFrameContentSize`: у кадра спрашивали заявленный размер, сверяли с
 * потолком и только потом распаковывали. В `zstd-kmp` такой функции **нет**, и
 * проверка переехала на выход: распаковка идёт потоком и падает, как только
 * выдано больше договорённого.
 *
 * Это **строже**, а не слабее. Заголовок кадра может врать или отсутствовать
 * вовсе (`ZSTD_CONTENTSIZE_UNKNOWN`) — доверие к нему было доверием к данным
 * противника. Фактический объём выхода обмануть нечем.
 *
 * Проверено на симуляторе iOS в спайке К1.4б: round-trip, степень сжатия и
 * отказ от бомбы из 8 МиБ нулей.
 */
internal object Zstd {

    /** Размер рабочего буфера обмена с нативным кодом. */
    private const val CHUNK = 64 * 1024

    /**
     * `zstd(data)` одним вызовом.
     *
     * Уровень сжатия не задаётся: `zstdCompressor()` параметра не принимает, а
     * константы уровня библиотека не публикует — только `setParameter(int, int)` с
     * числовым идентификатором zstd. Уровень по умолчанию у zstd равен 3, то есть
     * ровно тому, что стояло здесь до перехода. И это ни на что не влияет:
     * нормативна только распаковка, байты сжатого не нормативны ни для одного
     * контракта (schema/proto/README.md).
     *
     * Потолок на выходе тоже есть: сжатие обычно уменьшает, но на несжимаемых
     * данных zstd добавляет служебные байты, поэтому запас — вдвое от входа плюс
     * килобайт на заголовок кадра.
     */
    fun compress(data: ByteArray): ByteArray = zstdCompressor().use { c ->
        val out = Accumulator(data.size * 2 + 1024)
        val buffer = ByteArray(CHUNK)
        var consumed = 0
        while (true) {
            val code = c.compressStream2(
                outputByteArray = buffer, outputEnd = buffer.size, outputStart = 0,
                inputByteArray = data, inputEnd = data.size, inputStart = consumed,
                mode = ZSTD_e_end,
            )
            checkCode(code)
            consumed += c.inputBytesProcessed
            out.append(buffer, c.outputBytesProcessed)
            // Ноль означает: кадр закончен и внутренний буфер выдан целиком.
            if (code == 0L) break
        }
        out.bytes()
    }

    /**
     * `unzstd(data)` с потолком на распакованном.
     *
     * @param maxOutput сколько байт согласны принять. Превышение — [IllegalStateException],
     *   и это не «на всякий случай»: сжатый кадр в несколько сотен байт
     *   распаковывается в гигабайты, если ему позволить.
     */
    fun decompress(data: ByteArray, maxOutput: Long): ByteArray {
        require(maxOutput in 1..Int.MAX_VALUE.toLong()) { "потолок вне разумного: $maxOutput" }
        return zstdDecompressor().use { d ->
            val out = Accumulator(maxOutput.toInt())
            val buffer = ByteArray(CHUNK)
            var consumed = 0
            while (true) {
                val code = d.decompressStream(
                    outputByteArray = buffer, outputEnd = buffer.size, outputStart = 0,
                    inputByteArray = data, inputEnd = data.size, inputStart = consumed,
                )
                checkCode(code)
                consumed += d.inputBytesProcessed
                out.append(buffer, d.outputBytesProcessed)
                if (code == 0L) break
                // Ни байта не вошло и ни байта не вышло — кадр обрезан. Без этой
                // проверки цикл вертелся бы вечно на усечённом входе.
                if (d.inputBytesProcessed == 0 && d.outputBytesProcessed == 0) {
                    error("распаковка не движется: обрезанный кадр zstd")
                }
            }
            out.bytes()
        }
    }

    /** Библиотека отдаёт код возврата zstd; `getErrorName` даёт null, когда это не ошибка. */
    private fun checkCode(code: Long) {
        val name = getErrorName(code)
        if (name != null) error("zstd: $name")
    }

    /**
     * Копит выход и падает при превышении потолка. **Здесь и живёт защита от
     * бомбы** — не в доверии к заголовку кадра, а в отказе принять больше, чем
     * договорились.
     */
    private class Accumulator(private val limit: Int) {
        private val parts = mutableListOf<ByteArray>()
        private var total = 0

        fun append(buffer: ByteArray, count: Int) {
            if (count <= 0) return
            if (total + count > limit) {
                error("превышен потолок распакованного тела: ${total + count} > $limit байт")
            }
            parts += buffer.copyOf(count)
            total += count
        }

        fun bytes(): ByteArray {
            val result = ByteArray(total)
            var offset = 0
            for (part in parts) {
                part.copyInto(result, offset)
                offset += part.size
            }
            return result
        }
    }
}
