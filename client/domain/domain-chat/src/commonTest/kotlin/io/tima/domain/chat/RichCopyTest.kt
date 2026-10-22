package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** Текст сообщения целиком для копии (заказчик 2026-10-22): ссылки, вставленное, подкладка. */
class RichCopyTest {

    private val none: (String, String, Long) -> CopiedRef? = { _, _, _ -> null }

    @Test
    fun ссылка_со_своим_текстом_текст_и_адрес_без_текста_и_карточкой_только_адрес() {
        val rich = RichContent(
            listOf(
                RichBlock.Link("https://tima.io/a", "button", "Наш сайт", ""),
                RichBlock.Link("https://tima.io/b", "button", "", ""),
                RichBlock.Link("https://tima.io/c", "card", "Заголовок страницы", "описание"),
            ),
        )
        assertEquals("Наш сайт https://tima.io/a\nhttps://tima.io/b\nhttps://tima.io/c", RichCopy.text(rich, none, "[недоступно]"))
    }

    @Test
    fun ссылка_внутри_абзаца_раскрывается_на_месте() {
        val t = RichText("Смотри тут и https://x.ru дальше", listOf(RichSpan(SpanKind.LINK, 7, 10, "https://tima.io/doc"), RichSpan(SpanKind.LINK, 13, 25, "https://x.ru")))
        assertEquals("Смотри тут https://tima.io/doc и https://x.ru дальше", RichCopy.inline(t))
    }

    @Test
    fun вставленное_и_подкладка_целиком_с_автором_один_уровень() {
        val inner = RichContent(listOf(RichBlock.Paragraph(RichText("строка 1")), RichBlock.Paragraph(RichText("строка 2")), RichBlock.Quoted("group", "g", 9, false)))
        val rich = RichContent(
            listOf(
                RichBlock.Paragraph(RichText("Смотрите:")),
                RichBlock.Quoted("group", "g", 5, author = true, lines = 1),
                RichBlock.Underlay("group", "g", 6, author = false, lines = 3),
            ),
        )
        val refs: (String, String, Long) -> CopiedRef? = { _, _, n ->
            when (n) {
                5L -> CopiedRef("Аня", inner, null)
                9L -> CopiedRef("Боря", RichContent(listOf(RichBlock.Paragraph(RichText("глубже")))), null)
                else -> null
            }
        }
        assertEquals("Смотрите:\nАня\nстрока 1\nстрока 2\n[недоступно]", RichCopy.text(rich, refs, "[недоступно]"))
    }

    @Test
    fun список_ряд_и_таблица_строками() {
        val rich = RichContent(
            listOf(
                RichBlock.Bullets(listOf(Bullet(RichText("вода"), children = listOf(Bullet(RichText("2 л")))), Bullet(RichText("зонт")))),
                RichBlock.Row(listOf(RowCell(50, RichText("слева")), RowCell(50, RichText("справа")))),
                RichBlock.Table(listOf(listOf(TableCell(RichText("а")), TableCell(RichText("б"))))),
            ),
        )
        assertEquals("• вода\n  • 2 л\n• зонт\nслева\nсправа\nа | б", RichCopy.text(rich, none, "-"))
    }
}
