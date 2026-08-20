package io.tima.spike

import com.squareup.zstd.ZSTD_e_end
import com.squareup.zstd.getErrorName
import com.squareup.zstd.zstdCompressor
import com.squareup.zstd.zstdDecompressor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Спайк К1.4б: пригоден ли `com.squareup.zstd:zstd-kmp` вместо `zstd-jni`.
 *
 * Зачем: у `zstd-jni` нет таргетов Apple, и до этой библиотеки план предполагал
 * собирать `libzstd` под iOS самим (в iOS SDK zstd нет — Apple отдаёт zlib, LZFSE,
 * LZ4 и LZMA, но не zstd). Здесь опубликованы iosArm64, iosSimulatorArm64, iosX64,
 * android, jvm, macos, linux, mingw — то есть одна библиотека на все таргеты.
 *
 * **Но API низкоуровневый и потоковый**: прямая обёртка `ZSTD_compressStream2` и
 * `ZSTD_decompressStream`, без one-shot и **без `getFrameContentSize`**. Значит
 * защита от zstd-бомбы меняется, и спайк обязан проверить именно её.
 *
 * В v1 было: спросить у кадра заявленный размер, сверить с потолком, потом
 * распаковать. Здесь: распаковывать потоково и падать при превышении потолка на
 * выходе. Это **строже**: заголовок кадра может врать или отсутствовать
 * (`ZSTD_CONTENTSIZE_UNKNOWN`), а фактический выход не обманешь.
 */
class ZstdKmpSpikeTest {

    /** Потолок распакованного тела. В v1 — 16 МиБ; здесь меньше, чтобы тест был быстрым. */
    private val потолок = 1 shl 20 // 1 МиБ

    @Test
    fun сжатие_и_распаковка_туда_обратно() {
        val исходник = ("узлы вместо плоского текста, " +
            "и разметка ссылается на них по идентификаторам").encodeToByteArray()

        val сжато = сжать(исходник)
        assertTrue(сжато.isNotEmpty(), "пустой результат сжатия")

        val распаковано = распаковать(сжато, потолок)
        assertContentEquals(исходник, распаковано, "round-trip не совпал")
    }

    @Test
    fun повторяющиеся_данные_действительно_сжимаются() {
        // Тело сообщения сжимается до шифрования именно потому, что разметка —
        // это JSON с повторяющимися ключами.
        val исходник = "\"bold\":true,".repeat(4000).encodeToByteArray()
        val сжато = сжать(исходник)
        assertTrue(
            сжато.size * 20 < исходник.size,
            "ожидалось сжатие хотя бы в 20 раз, вышло ${исходник.size} → ${сжато.size}",
        )
        assertContentEquals(исходник, распаковать(сжато, потолок))
    }

    @Test
    fun бомба_отвергается_по_потолку_выхода() {
        // 8 МиБ нулей сжимаются в считаные байты — это и есть zstd-бомба.
        val бомба = сжать(ByteArray(8 shl 20))
        assertTrue(бомба.size < 1000, "бомба должна быть крошечной, вышло ${бомба.size}")

        val ошибка = assertFailsWith<IllegalStateException> {
            распаковать(бомба, потолок)
        }
        assertTrue(
            ошибка.message.orEmpty().contains("потолок"),
            "ожидалось падение по потолку, а не «${ошибка.message}»",
        )
    }

    @Test
    fun ошибка_распознаётся_по_имени_а_не_по_догадке() {
        // Библиотека отдаёт код возврата zstd; getErrorName даёт null, когда это не
        // ошибка. Проверяем оба края, иначе легко принять успех за ошибку.
        assertEquals(null, getErrorName(0), "ноль — не ошибка")
        assertTrue(getErrorName(-1L) != null, "-1 должно опознаваться как ошибка")
    }

    // ── Обёртка поверх потокового API ────────────────────────────────────────
    //
    // Это черновик того, что в К2 переедет в messenger-crypto одним файлом.
    // Компрессор и декомпрессор закрываются через use(): они держат нативный
    // контекст, и в проекте это правило (Plan.md §2.2 п.8, §3.7).

    private fun сжать(исходник: ByteArray): ByteArray = zstdCompressor().use { c ->
        val накопитель = Накопитель(потолок * 2)
        val буфер = ByteArray(64 * 1024)
        var прочитано = 0
        while (true) {
            val код = c.compressStream2(
                outputByteArray = буфер, outputEnd = буфер.size, outputStart = 0,
                inputByteArray = исходник, inputEnd = исходник.size, inputStart = прочитано,
                mode = ZSTD_e_end,
            )
            проверитьКод(код)
            прочитано += c.inputBytesProcessed
            накопитель.добавить(буфер, c.outputBytesProcessed)
            // Ноль означает: кадр закончен и внутренний буфер выдан целиком.
            if (код == 0L) break
        }
        накопитель.байты()
    }

    private fun распаковать(сжато: ByteArray, потолок: Int): ByteArray =
        zstdDecompressor().use { d ->
            val накопитель = Накопитель(потолок)
            val буфер = ByteArray(64 * 1024)
            var прочитано = 0
            while (true) {
                val код = d.decompressStream(
                    outputByteArray = буфер, outputEnd = буфер.size, outputStart = 0,
                    inputByteArray = сжато, inputEnd = сжато.size, inputStart = прочитано,
                )
                проверитьКод(код)
                прочитано += d.inputBytesProcessed
                накопитель.добавить(буфер, d.outputBytesProcessed)
                if (код == 0L) break
                if (d.inputBytesProcessed == 0 && d.outputBytesProcessed == 0) {
                    error("распаковка не движется: обрезанный кадр")
                }
            }
            накопитель.байты()
        }

    private fun проверитьКод(код: Long) {
        val имя = getErrorName(код)
        if (имя != null) error("zstd: $имя")
    }

    /**
     * Копит выход и **падает при превышении потолка**. Здесь и живёт защита от
     * бомбы: не в доверии к заголовку кадра, а в отказе принимать больше, чем
     * договорились.
     */
    private class Накопитель(private val потолок: Int) {
        private val части = mutableListOf<ByteArray>()
        private var всего = 0

        fun добавить(буфер: ByteArray, сколько: Int) {
            if (сколько <= 0) return
            if (всего + сколько > потолок) {
                error("превышен потолок распакованного тела: ${всего + сколько} > $потолок")
            }
            части += буфер.copyOf(сколько)
            всего += сколько
        }

        fun байты(): ByteArray {
            val итог = ByteArray(всего)
            var смещение = 0
            for (часть in части) {
                часть.copyInto(итог, смещение)
                смещение += часть.size
            }
            return итог
        }
    }
}
