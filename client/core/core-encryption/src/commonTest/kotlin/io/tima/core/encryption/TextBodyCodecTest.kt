package io.tima.core.encryption

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Круг упаковки тела: настоящие protobuf и zstd, без подмен. Смысл проверки в том,
 * что распаковка нормативна (сжатие — нет), и значит именно круг обязан сходиться.
 */
class TextBodyCodecTest {

    @Test
    fun текст_проходит_круг_без_потерь() {
        val текст = "Привет! Как дела? 🙂 Ⅵ ﷽"

        val тело = TextBodyCodec.encodeText(текст)
        val содержимое = TextBodyCodec.decode(тело).getOrThrow()

        assertEquals(текст, содержимое.plainText())
    }

    @Test
    fun пустое_тело_не_роняет_разбор() {
        // Нечитаемое сообщение — обычное состояние входящей машины, а не поломка.
        assertTrue(TextBodyCodec.decode(ByteArray(0)).isFailure)
        assertTrue(TextBodyCodec.decode(byteArrayOf(1, 2, 3)).isFailure, "мусор — тоже неудача, а не исключение")
    }

    @Test
    fun одинаковый_текст_даёт_одинаковые_байты() {
        // Не нормативное требование (нормативна только распаковка), но свойство, на
        // котором держится dedup_key: два вызова подряд не должны давать разные тела.
        val первое = TextBodyCodec.encodeText("привет")
        val второе = TextBodyCodec.encodeText("привет")
        assertTrue(первое.contentEquals(второе))
    }

    @Test
    fun разный_текст_даёт_разные_байты() {
        assertNotEquals(
            TextBodyCodec.encodeText("привет").toList(),
            TextBodyCodec.encodeText("пока").toList(),
        )
    }

    @Test
    fun длинный_текст_сжимается_а_не_растёт() {
        // zstd на повторяющемся тексте обязан выигрывать: если бы тело росло, сжатие
        // стоило бы трафика вместо того, чтобы его экономить.
        val длинный = "одна и та же строка ".repeat(500)
        val тело = TextBodyCodec.encodeText(длинный)
        assertTrue(
            тело.size < длинный.encodeToByteArray().size / 2,
            "ожидалось сжатие, а получилось ${тело.size} из ${длинный.encodeToByteArray().size}",
        )
        assertEquals(длинный, TextBodyCodec.decode(тело).getOrThrow().plainText())
    }
}
