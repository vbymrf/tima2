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
        val text = "Привет! Как дела? 🙂 Ⅵ ﷽"

        val body = TextBodyCodec.encodeText(text)
        val content = TextBodyCodec.decode(body).getOrThrow()

        assertEquals(text, content.plainText())
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
        val first = TextBodyCodec.encodeText("привет")
        val second = TextBodyCodec.encodeText("привет")
        assertTrue(first.contentEquals(second))
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
        val long = "одна и та же строка ".repeat(500)
        val body = TextBodyCodec.encodeText(long)
        assertTrue(
            body.size < long.encodeToByteArray().size / 2,
            "ожидалось сжатие, а получилось ${body.size} из ${long.encodeToByteArray().size}",
        )
        assertEquals(long, TextBodyCodec.decode(body).getOrThrow().plainText())
    }
}
