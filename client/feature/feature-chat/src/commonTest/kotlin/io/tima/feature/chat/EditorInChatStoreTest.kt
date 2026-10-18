package io.tima.feature.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.ComposedBodies
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.Drafts
import io.tima.domain.chat.InlineKind
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.ParagraphKind
import io.tima.domain.chat.ReplyTarget
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.SendComposed
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Редактор в переписке (Решения §9, §10): набранное переезжает в редактор, «назад» оставляет чип,
 * стрелка шлёт собранное, корзина даёт 5 секунд «Вернуть»; модель редактора — шаги и курсор.
 */
class EditorInChatStoreTest {

    private val stream = MutableStateFlow<List<ChatLine>>(emptyList())
    private val sent = mutableListOf<String>()
    private val saved = mutableMapOf<String, String>()

    private val codec = object : MessageBodyCodec {
        override fun encodeText(text: String) = text.encodeToByteArray()
        override fun decodeText(body: ByteArray) = body.decodeToString()
    }

    /** Тело-подделка: текст кусков через «|»; черновик — то же. */
    private val bodies = object : ComposedBodies {
        private fun flat(doc: ComposedDoc) = doc.items.joinToString("|") { (it as? ComposedItem.Text)?.source ?: "w" }
        override fun problems(doc: ComposedDoc) = emptyList<String>()
        override fun encode(doc: ComposedDoc, reply: ReplyTarget?) = ("ред:" + flat(doc)).encodeToByteArray()
        override fun preview(doc: ComposedDoc): RichContent? = null
        override fun save(doc: ComposedDoc) = flat(doc)
        override fun load(saved: String) = ComposedDoc(saved.split("|").map { ComposedItem.Text(it) })
    }

    private val drafts = object : Drafts {
        override fun load(chatId: String) = saved[chatId]
        override fun save(chatId: String, text: String) { if (text.isEmpty()) saved.remove(chatId) else saved[chatId] = text }
    }

    private val queue = OutgoingQueue { _, _, body, _, _, _ -> sent += body.decodeToString(); true }

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = ChatStore(
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        chatId = "chat-1",
        observe = ObserveChat(ChatFeed { _, _ -> stream }),
        send = SendMessage(queue, codec, DedupKeys { "d-${sent.size}" }),
        scope = scope,
        drafts = drafts,
        composer = SendComposed(queue, bodies, DedupKeys { "c-${sent.size}" }),
    )

    @Test
    fun набранное_переезжает_в_редактор_и_возвращается_чипом() = runTest {
        val s = store(backgroundScope)
        runCurrent()
        s.draftChanged("привет")
        val doc = s.openEditor()!!
        assertEquals("привет", (doc.items.single() as ComposedItem.Text).source)
        assertTrue(s.state.value.editorOpen)
        assertEquals("", s.state.value.draft, "строка пустеет — текст теперь в редакторе")

        s.editorBack(ComposedDoc(listOf(ComposedItem.Text("# Сбор"), ComposedItem.Text("в 9"))))
        runCurrent()
        assertFalse(s.state.value.editorOpen)
        assertEquals(2, s.state.value.composed!!.rows)
        assertEquals("# Сбор|в 9", saved["chat-1|editor"], "«назад» сохраняет черновик редактора")

        s.sendPressed()
        runCurrent()
        assertEquals(listOf("ред:# Сбор|в 9"), sent, "стрелка шлёт собранное, а не строку")
        assertNull(s.state.value.composed)
        assertNull(saved["chat-1|editor"])
    }

    @Test
    fun черновик_редактора_переживает_перезапуск() = runTest {
        saved["chat-1|editor"] = "a|b"
        val s = store(backgroundScope)
        runCurrent()
        assertEquals(2, s.state.value.composed!!.rows)
    }

    @Test
    fun корзина_и_вернуть_5_секунд() = runTest {
        val s = store(backgroundScope)
        runCurrent()
        s.openEditor()
        s.editorBack(ComposedDoc.of("x"))
        runCurrent()
        s.dropComposed()
        assertNull(s.state.value.composed)
        s.restoreComposed()
        assertEquals("x", s.state.value.composed!!.firstLine)
        s.dropComposed()
        advanceTimeBy(5_001)
        runCurrent()
        assertNull(s.state.value.droppedComposed, "через 5 секунд вернуть нечего")
        assertNull(saved["chat-1|editor"])
    }

    @Test
    fun модель_набор_подряд_одним_шагом_кнопки_отдельными() {
        val m = EditorModel(ComposedDoc.of(""))
        for (text in listOf("с", "сб", "сбо", "сбор")) m.changed(0, TextFieldValue(text, TextRange(text.length)))
        m.changed(0, TextFieldValue("сбор ", TextRange(5)))
        m.changed(0, TextFieldValue("сбор в", TextRange(6)))
        m.changed(0, TextFieldValue("сбор в", TextRange(0, 4)))
        m.inline(InlineKind.BOLD)
        assertEquals("**сбор** в", m.focusedText)
        assertEquals(TextRange(2, 6), m.field.selection, "выделение остаётся на слове")
        m.paragraph(ParagraphKind.HEADING1)
        assertEquals("# **сбор** в", m.focusedText)
        m.undo()
        assertEquals("**сбор** в", m.focusedText)
        m.undo()
        assertEquals("сбор в", m.focusedText)
        m.undo()
        m.undo()
        assertEquals("сбор", m.focusedText, "пробел — свой шаг, слово до него — один")
        m.undo()
        assertEquals("", m.focusedText)
        assertFalse(m.canUndo)
        m.redo()
        assertEquals("сбор", m.focusedText)
    }

    @Test
    fun модель_вставка_сдвиг_и_удаление_кусков() {
        val m = EditorModel(ComposedDoc.of("a"))
        m.insert(ComposedItem.Widget(ComposedWidget.Emoji("🎉")))
        assertEquals(listOf("a", "w", ""), m.doc.items.map { (it as? ComposedItem.Text)?.source ?: "w" }, "после виджета — пустой текст")
        assertEquals(1, m.focus)
        m.move(1, -1)
        assertTrue(m.doc.items.first() is ComposedItem.Widget)
        m.remove(0)
        assertEquals(listOf("a", ""), m.doc.items.map { (it as ComposedItem.Text).source })
        m.toggleMarks()
        assertFalse(m.doc.marks)
        m.level(3)
        assertEquals(3, m.doc.level)
        m.undo()
        m.undo()
        assertTrue(m.doc.marks, "знаки и уровень отменяются, как и всё")
    }

    @Test
    fun вставка_в_место_курсора_без_экранирования() {
        val m = EditorModel(ComposedDoc.of("ab"))
        m.changed(0, TextFieldValue("ab", TextRange(1)))
        m.paste("**x**")
        assertEquals("a**x**b", m.focusedText)
        m.insert(ComposedItem.Widget(ComposedWidget.Emoji("🎉")))
        m.select(1)
        m.paste("новый")
        assertEquals("новый", (m.doc.items[2] as ComposedItem.Text).source, "курсор на виджете — текст новым куском после него")
    }

    @Test
    fun модель_ряды_ячейки_и_перенос() {
        val m = EditorModel(ComposedDoc.of("a"))
        m.layout(io.tima.domain.chat.ComposedLayout::addRow)
        assertEquals(io.tima.domain.chat.Spot(1, 0), m.spot, "курсор — в первой ячейке нового ряда")
        m.insert(ComposedItem.Widget(ComposedWidget.Emoji("🎉")))
        val row = m.doc.items[1] as ComposedItem.Row
        assertEquals(listOf<ComposedWidget>(ComposedWidget.Emoji("🎉")), row.cells[0].widgets, "виджет — в выбранную ячейку")
        m.focusedCell(1, 1)
        m.changedCell(1, 1, TextFieldValue("правая", TextRange(0, 6)))
        m.inline(InlineKind.BOLD)
        assertEquals("**правая**", (m.doc.items[1] as ComposedItem.Row).cells[1].text)
        m.drop(io.tima.domain.chat.Spot(1, 0, 0), io.tima.domain.chat.Drop.IntoCell(1, 1))
        val moved = m.doc.items[1] as ComposedItem.Row
        assertTrue(moved.cells[0].widgets.isEmpty())
        assertEquals(1, moved.cells[1].widgets.size)
        m.undo()
        assertEquals(1, (m.doc.items[1] as ComposedItem.Row).cells[0].widgets.size, "перенос отменяется")
    }

    @Test
    fun основное_уходит_когда_продолжение_получило_номер() = runTest {
        val marks = mutableSetOf<String>()
        val s = ChatStore(
            io = kotlinx.coroutines.Dispatchers.Unconfined,
            chatId = "chat-1",
            observe = ObserveChat(ChatFeed { _, _ -> stream }),
            send = SendMessage(queue, codec, DedupKeys { "d-${sent.size}" }),
            scope = backgroundScope,
            composer = SendComposed(
                queue,
                object : ComposedBodies by bodies {
                    override fun encode(doc: ComposedDoc, reply: ReplyTarget?) = doc.split().first.items.joinToString("|") { item ->
                        ((item as? ComposedItem.Widget)?.widget as? ComposedWidget.Underlay)?.let { "u:${it.message}" }
                            ?: (item as? ComposedItem.Text)?.source.orEmpty()
                    }.encodeToByteArray()
                },
                DedupKeys { "c-${sent.size}" },
                continuations = object : io.tima.domain.chat.ContinuationMarks {
                    override fun mark(dedupKey: String) { marks += dedupKey }
                    override fun marked(dedupKey: String) = dedupKey in marks
                },
            ),
        )
        runCurrent()
        s.sendComposed(ComposedDoc(listOf(ComposedItem.Text("открытое"), ComposedItem.Continuation(3), ComposedItem.Text("закрытое"))))
        assertEquals(listOf("закрытое"), sent, "сначала продолжение")
        stream.value = listOf(
            ChatLine(dedupKey = "c-0", chatId = "chat-1", display = io.tima.domain.chat.MessageDisplay.SENT, text = "закрытое",
                outgoing = true, atMs = 1, localId = 1, serverId = 77, onlyContinuation = true),
        )
        runCurrent()
        assertEquals(listOf("закрытое", "открытое|u:77"), sent, "основное — с подкладкой на номер продолжения")
        assertTrue(s.state.value.lines.isEmpty(), "продолжение в ленте не показывается")
        assertEquals(77L, s.state.value.continuations.single().serverId, "но подкладке есть что показать")
    }

    @Test
    fun основное_ждущее_номера_переживает_перезапуск() = runTest {
        val marks = mutableSetOf<String>()
        fun make(scope: kotlinx.coroutines.CoroutineScope) = ChatStore(
            io = kotlinx.coroutines.Dispatchers.Unconfined,
            chatId = "chat-1",
            observe = ObserveChat(ChatFeed { _, _ -> stream }),
            send = SendMessage(queue, codec, DedupKeys { "d-${sent.size}" }),
            scope = scope,
            drafts = drafts,
            composer = SendComposed(
                queue,
                object : ComposedBodies by bodies {
                    override fun encode(doc: ComposedDoc, reply: ReplyTarget?) = doc.split().first.items.joinToString("|") { item ->
                        ((item as? ComposedItem.Widget)?.widget as? ComposedWidget.Underlay)?.let { "u:${it.message}" }
                            ?: (item as? ComposedItem.Text)?.source.orEmpty()
                    }.encodeToByteArray()
                },
                DedupKeys { "c-${sent.size}" },
                continuations = object : io.tima.domain.chat.ContinuationMarks {
                    override fun mark(dedupKey: String) { marks += dedupKey }
                    override fun marked(dedupKey: String) = dedupKey in marks
                },
            ),
        )
        val first = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())
        make(first).sendComposed(ComposedDoc(listOf(ComposedItem.Text("открытое"), ComposedItem.Continuation(3), ComposedItem.Text("закрытое"))))
        runCurrent()
        assertTrue(saved["chat-1|pending"].orEmpty().startsWith("c-0"), "ожидание записано: ключ продолжения и документ")
        first.coroutineContext[kotlinx.coroutines.Job]?.cancel() // «перезапуск»: прежнего store нет
        make(backgroundScope)
        runCurrent()
        stream.value = listOf(
            ChatLine(dedupKey = "c-0", chatId = "chat-1", display = io.tima.domain.chat.MessageDisplay.SENT, text = "закрытое",
                outgoing = true, atMs = 1, localId = 1, serverId = 77, onlyContinuation = true),
        )
        runCurrent()
        assertEquals(listOf("закрытое", "открытое|u:77"), sent, "основное ушло после перезапуска с подкладкой")
        assertNull(saved["chat-1|pending"], "ожидание снято")
    }

    @Test
    fun объединение_ячейки_таблицы_кнопкой() {
        val table = "| a | b |\n|---|---|\n| c | d |"
        val m = EditorModel(ComposedDoc.of(table))
        // Курсор в ячейке «d» — с левой.
        m.changed(0, TextFieldValue(table, TextRange(table.indexOf("d"))))
        assertTrue(m.tableJoin("<<"))
        assertEquals("| a | b |\n|---|---|\n| c | << |", m.focusedText)
        // Курсор вне таблицы — ничего.
        val plain = EditorModel(ComposedDoc.of("просто текст"))
        plain.changed(0, TextFieldValue("просто текст", TextRange(3)))
        assertFalse(plain.tableJoin("^^"))
    }
}
