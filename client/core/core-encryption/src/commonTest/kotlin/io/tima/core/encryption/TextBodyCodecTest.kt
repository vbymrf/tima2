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
    fun набранное_с_знаками_приходит_оформленным() {
        // ПЛАН-(РС) РС2–РС4: набранные знаки — в узлы и разметку, на приёме — блоки для экрана.
        val line = TextBodyCodec.decodeLine(TextBodyCodec.encodeTyped("# Поход\nСбор **в 9:00**\n- вода\n- зонт"))!!
        assertEquals("Поход\nСбор в 9:00\nвода\nзонт", line.text, "текст для списка и поиска — без знаков, по строкам")
        val blocks = line.rich!!.blocks
        assertTrue(blocks[0] is io.tima.domain.chat.RichBlock.Heading)
        val paragraph = blocks[1] as io.tima.domain.chat.RichBlock.Paragraph
        assertEquals("Сбор в 9:00", paragraph.text.text)
        assertEquals(io.tima.domain.chat.SpanKind.BOLD, paragraph.text.spans.single().kind)
        assertEquals(2, (blocks[2] as io.tima.domain.chat.RichBlock.Bullets).items.size)
    }

    @Test
    fun строка_ввода_тем_же_разбором() {
        // Каждый вид отрезка разборщика должен найтись в модели экрана — иначе valueOf упадёт.
        val spans = TypingMarksOverQuickMarkdown.of("# Заг **ж** *к* ++ч++ ==м== ~~з~~ `к` #тег @ник\n--> ц **н")
        val styles = spans.map { it.style }.toSet()
        assertEquals(io.tima.domain.chat.TypingStyle.entries.toSet(), styles)
    }

    @Test
    fun набранное_без_знаков_уходит_без_оформления() {
        val line = TextBodyCodec.decodeLine(TextBodyCodec.encodeTyped("просто текст"))!!
        assertEquals("просто текст", line.text)
        assertEquals(null, line.rich)
        // То же тело, что у прежнего пути: 99% сообщений не дорожают (ADR-0011 §3).
        assertEquals(TextBodyCodec.encodeText("просто текст").toList(), TextBodyCodec.encodeTyped("просто текст").toList())
    }

    @Test
    fun знаки_без_разбора_когда_оформление_не_проходит_проверку() {
        // Узлов больше 4096 знаков — строгая проверка не пропустит; уходит текстом как есть.
        val long = "**" + "а".repeat(5000) + "**"
        val line = TextBodyCodec.decodeLine(TextBodyCodec.encodeTyped(long))!!
        assertEquals(long, line.text)
        assertEquals(null, line.rich)
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
