package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Редактор на личной странице (Решения §10.2): те же правила, уходит открытой записью канала. */
class WriteToPageTest {

    private val published = mutableListOf<Pair<OpenBody, Int>>()

    private fun writer(open: OpenBody?, problems: List<String> = emptyList()) = WriteToPage(
        posts = { _, body, level -> published += body to level; PublishStep.Published(7) },
        bodies = object : ComposedBodies {
            override fun problems(doc: ComposedDoc) = problems
            override fun encode(doc: ComposedDoc, reply: ReplyTarget?): ByteArray? = null
            override fun preview(doc: ComposedDoc): RichContent? = null
            override fun save(doc: ComposedDoc) = ""
            override fun load(saved: String): ComposedDoc? = null
            override fun open(doc: ComposedDoc) = open
        },
    )

    private val body = OpenBody("текст", listOf("текст"), "")

    @Test
    fun уходит_с_уровнем_редактора_или_страницы() = runTest {
        assertEquals(PageWriteResult.Published(7), writer(body).write("ch", ComposedDoc.of("текст"), 1))
        writer(body).write("ch", ComposedDoc.of("текст").copy(level = 2), 1)
        assertEquals(listOf(1, 2), published.map { it.second })
    }

    @Test
    fun отказы_до_сети() = runTest {
        assertEquals(PageWriteResult.Empty, writer(body).write("ch", ComposedDoc(), 1))
        assertIs<PageWriteResult.Invalid>(writer(body, listOf("плохо")).write("ch", ComposedDoc.of("x"), 1))
        assertEquals(PageWriteResult.HasFiles, writer(null).write("ch", ComposedDoc.of("x"), 1))
        val wide = "| " + (1..8).joinToString(" | ") + " |\n|" + List(8) { "---" }.joinToString("|") + "|"
        assertIs<PageWriteResult.TableTooWide>(writer(body).write("ch", ComposedDoc.of(wide), 1))
        assertEquals(0, published.size)
    }
}
