package io.tima.core.encryption

import io.tima.core.outbox.OutboxEntry
import io.tima.crypto.CanonicalBytes
import io.tima.crypto.GroupMessageMeta
import io.tima.crypto.MessageSerializer
import io.tima.crypto.Mlkem768
import io.tima.crypto.VerificationFailure
import io.tima.domain.chat.EditBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Правка (ADR-0045) на настоящем круге: тело правки из набранного, конверт версии 3 у личной
 * переписки и подпись `tima.group_message.v3` у группы — собираются и открываются получателем.
 */
class EditsTest {

    private val sender = DeviceIdentity.generate()
    private val recipient = DeviceIdentity.generate()

    @Test
    fun правка_меняет_только_текст_узлов() {
        val original = TextBodyCodec.encodeTyped("# Поход\nСбор **в 9:00**")
        assertEquals("# Поход\nСбор **в 9:00**", TextBodyCodec.editSource(original, emptyList()))

        val first = assertIs<EditBody.Changes>(TextBodyCodec.encodeEdit(original, emptyList(), "# Поход\nСбор **в 10:00**", marks = true))
        val line = TextBodyCodec.decodeEdited(original, listOf(first.body))!!
        assertEquals("Поход\nСбор в 10:00", line.text)

        // Вторая правка считается от того, что видно: исходное с первой правкой.
        assertEquals("# Поход\nСбор **в 10:00**", TextBodyCodec.editSource(original, listOf(first.body)))
        assertIs<EditBody.Same>(TextBodyCodec.encodeEdit(original, listOf(first.body), "# Поход\nСбор **в 10:00**", marks = true))
        assertIs<EditBody.Structure>(TextBodyCodec.encodeEdit(original, emptyList(), "# Поход\nСбор\n- зонт", marks = true))
    }

    /**
     * Ряд и виджеты знаками не выразить — правка в редакторе (заказчик 2026-10-18): документ из
     * сообщения собирается обратно в то же строение; меняются тексты, а добавленный кусок — отказ.
     */
    @Test
    fun ряд_с_виджетами_правится_в_редакторе() {
        val doc = io.tima.domain.chat.ComposedDoc(
            listOf(
                io.tima.domain.chat.ComposedItem.Text("# Поход\nСбор **в 9:00**"),
                io.tima.domain.chat.ComposedItem.Row(
                    listOf(
                        io.tima.domain.chat.ComposedCell(40, "слева *так*", style = mapOf("background" to "amber-soft")),
                        io.tima.domain.chat.ComposedCell(60, "", widgets = listOf(io.tima.domain.chat.ComposedWidget.Emoji("🎉"))),
                    ),
                ),
                io.tima.domain.chat.ComposedItem.Widget(io.tima.domain.chat.ComposedWidget.Place(47.2, 39.7, "вход")),
                io.tima.domain.chat.ComposedItem.Text("конец", mapOf("align" to "center")),
            ),
        )
        val body = ComposedBodiesOverKodium.encode(doc)!!
        assertNull(TextBodyCodec.editSource(body, emptyList()), "строкой ввода не выразить")
        val opened = assertNotNull(TextBodyCodec.editDoc(body, emptyList()), "редактор открывает сообщение")
        assertEquals(5, opened.items.size, "заголовок и абзац — отдельными кусками")
        assertIs<EditBody.Same>(TextBodyCodec.encodeEditDoc(body, emptyList(), opened))

        val row = opened.items[2] as io.tima.domain.chat.ComposedItem.Row
        val changed = opened.copy(
            items = opened.items.toMutableList().apply {
                set(2, row.copy(cells = row.cells.mapIndexed { i, c -> if (i == 0) c.copy(text = "слева *иначе*") else c }))
            },
        )
        val edit = assertIs<EditBody.Changes>(TextBodyCodec.encodeEditDoc(body, emptyList(), changed))
        assertEquals(true, TextBodyCodec.decodeEdited(body, listOf(edit.body))!!.text.contains("слева иначе"))
        // Вторая правка — от исходного с первой.
        assertNotNull(TextBodyCodec.editDoc(body, listOf(edit.body)))

        val grown = opened.copy(items = opened.items + io.tima.domain.chat.ComposedItem.Text("ещё"))
        assertIs<EditBody.Structure>(TextBodyCodec.encodeEditDoc(body, emptyList(), grown), "добавленный кусок — другое строение")
    }

    @Test
    fun простое_сообщение_правится_простым_текстом() {
        val original = TextBodyCodec.encodeTyped("просто")
        val edit = assertIs<EditBody.Changes>(TextBodyCodec.encodeEdit(original, emptyList(), "стало **так**", marks = true))
        // У сообщения без оформления знаки остаются текстом: строение правка не меняет.
        assertEquals("стало **так**", TextBodyCodec.decodeEdited(original, listOf(edit.body))!!.text)
    }

    @Test
    fun ответ_уходит_блоком_и_правится_по_тексту() {
        val reply = io.tima.domain.chat.ReplyTarget("group", "g-1", 42, "u-7")
        val body = TextBodyCodec.encodeReply("да, **в девять**", marks = true, reply = reply)
        val line = TextBodyCodec.decodeLine(body)!!
        assertEquals("да, в девять", line.text, "блок ответа текста не добавляет")
        val first = line.rich!!.blocks.first()
        assertEquals(io.tima.domain.chat.RichBlock.Reply("u-7", "g-1", 42), first)
        // Правка ответа: в строку ввода — текст без блока ответа; ответ после правки на месте.
        assertEquals("да, **в девять**", TextBodyCodec.editSource(body, emptyList()))
        val edit = assertIs<EditBody.Changes>(TextBodyCodec.encodeEdit(body, emptyList(), "да, **в десять**", marks = true))
        val after = TextBodyCodec.decodeEdited(body, listOf(edit.body))!!
        assertEquals("да, в десять", after.text)
        assertEquals(first, after.rich!!.blocks.first())
        // Простой текст с ответом правится простым текстом.
        val plain = TextBodyCodec.encodeReply("ок", marks = true, reply = reply)
        assertEquals("ок", TextBodyCodec.editSource(plain, emptyList()))
        assertIs<EditBody.Changes>(TextBodyCodec.encodeEdit(plain, emptyList(), "ладно", marks = true))
    }

    @Test
    fun таблицу_строкой_ввода_не_правят() {
        assertNull(TextBodyCodec.editSource(TextBodyCodec.encodeTyped("| a | b |\n|---|---|\n| 1 | 2 |"), emptyList()))
    }

    /**
     * Сообщение с Samsung 2026-10-18 (без таблицы — её редактор больше не правит): абзацы, ряд, простые
     * абзацы подряд разными кусками. Склеенные простые абзацы разбирались обратно одним — «изменить нельзя».
     */
    @Test
    fun простые_абзацы_подряд_и_ряд_правятся_в_редакторе() {
        val doc = io.tima.domain.chat.ComposedDoc(
            listOf(
                io.tima.domain.chat.ComposedItem.Text("раз\nдва\n*три*"),
                io.tima.domain.chat.ComposedItem.Row(listOf(io.tima.domain.chat.ComposedCell(50, "слева"), io.tima.domain.chat.ComposedCell(50, "справа"))),
                io.tima.domain.chat.ComposedItem.Text("четыре\nпять"),
                io.tima.domain.chat.ComposedItem.Text("шесть"),
            ),
        )
        val body = ComposedBodiesOverKodium.encode(doc)!!
        val opened = assertNotNull(TextBodyCodec.editDoc(body, emptyList()))
        assertIs<EditBody.Same>(TextBodyCodec.encodeEditDoc(body, emptyList(), opened))
    }

    /**
     * Таблицы в редакторе больше нет (заказчик 2026-10-18): старое сообщение с таблицей показывается
     * как раньше, но не правится.
     */
    @Test
    fun старую_таблицу_редактор_не_правит() {
        val body = TextBodyCodec.encodeTyped("до\n| a | **b** |\n|---|:---:|\n| 1 | << |")
        assertNull(TextBodyCodec.editDoc(body, emptyList()))
    }

    /** Ряд из строк со слиянием и пустой ячейкой (2026-10-18) — собирается и открывается для правки. */
    @Test
    fun ряд_из_строк_со_слиянием_правится() {
        val cells = listOf(
            io.tima.domain.chat.ComposedCell(50, "шапка"), io.tima.domain.chat.ComposedCell(50, merge = "left"),
            io.tima.domain.chat.ComposedCell(50, "", style = mapOf("background" to "amber-soft")), io.tima.domain.chat.ComposedCell(50, "справа"),
        )
        val doc = io.tima.domain.chat.ComposedDoc(listOf(io.tima.domain.chat.ComposedItem.Row(cells, columns = 2)))
        val body = ComposedBodiesOverKodium.encode(doc)!!
        val line = TextBodyCodec.decodeLine(body)!!
        val row = line.rich!!.blocks.single() as io.tima.domain.chat.RichBlock.Row
        assertEquals(2, row.columns)
        assertEquals(listOf("", "left", "", ""), row.cells.map { it.merge })
        val opened = assertNotNull(TextBodyCodec.editDoc(body, emptyList()))
        assertEquals(doc.items.single(), opened.items.single(), "пустая ячейка с фоном и слитая — на месте")
        val changed = opened.copy(items = listOf((opened.items.single() as io.tima.domain.chat.ComposedItem.Row).let { r -> r.copy(cells = r.cells.mapIndexed { i, c -> if (i == 3) c.copy(text = "иначе") else c }) }))
        assertIs<EditBody.Changes>(TextBodyCodec.encodeEditDoc(body, emptyList(), changed))
    }

    @Test
    fun личная_правка_уходит_версией_3_и_открывается() {
        val sealer = OutgoingSealer(
            senderId = "0f8fad5b-d9cb-469f-a165-70867728950e",
            senderDeviceId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
            identity = sender,
        )
        val escrowKey = EscrowEpochKey(publicKey = Mlkem768.keyPair().first, version = 7)
        val original = TextBodyCodec.encodeTyped("было")
        val body = assertIs<EditBody.Changes>(TextBodyCodec.encodeEdit(original, emptyList(), "стало", marks = true)).body
        val envelope = sealer.sealerFor(escrowKey, listOf(RecipientDevice("получатель", recipient.encryptionPublic)))(
            OutboxEntry(
                dedupKey = "0f8fad5b-d9cb-469f-a165-70867728950e",
                chatId = "bbbbbbbb-0000-0000-0000-0000000000e1",
                body = body,
                editOf = 41,
                createdAtMs = 1_771_200_000_000,
            ),
        )
        assertEquals(CanonicalBytes.FORMAT_VERSION_EDIT, MessageSerializer.decodeEnvelope(envelope).getOrThrow().formatVersion)
        assertEquals(41L, PersonalMessages.peekSender(envelope)!!.editOf)

        val open = PersonalMessages.open(envelope, "получатель", recipient, sender.signingPublic).getOrThrow()
        assertEquals(41uL, open.meta.editOf)
        // Тело правки дошло как есть — не пересобранное через содержимое, которое правок не несёт.
        assertEquals("стало", TextBodyCodec.decodeEdited(original, listOf(open.body))!!.text)
    }

    @Test
    fun правка_в_группе_подписана_версией_3() {
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val meta = GroupMessageMeta(
            groupId = "gggggggg-0000-0000-0000-000000000001",
            senderId = "bbbbbbbb-0000-0000-0000-000000000002",
            senderDevice = "cccccccc-0000-0000-0000-000000000003",
            kind = 1,
            createdAtUnixMs = 1_750_000_000_000,
            gkVersion = 7,
        )
        val body = assertIs<EditBody.Changes>(TextBodyCodec.encodeEdit(TextBodyCodec.encodeTyped("было"), emptyList(), "стало", true)).body
        val sealed = GroupMessages.sealPrepared(body, meta, sender, key, editOf = 9).getOrThrow()
        fun open(editOf: Long) = GroupMessages.open(
            groupId = meta.groupId, senderId = meta.senderId, senderDevice = meta.senderDevice, kind = 1,
            createdAtUnixMs = meta.createdAtUnixMs, threadRoot = 0, replyTo = 0, gkVersion = 7,
            payload = sealed.payload, signature = sealed.signature, senderSigningPublic = sender.signingPublic,
            groupKey = key, keyCommitment = sealed.keyCommitment, editOf = editOf,
        )
        assertNotNull(open(9).getOrThrow())
        // Номер исходного под подписью: перенести правку на другое сообщение нельзя.
        assertIs<VerificationFailure>(open(8).exceptionOrNull())
        assertIs<VerificationFailure>(open(0).exceptionOrNull())

        // Открытое сообщение группы — та же версия 3 с пустым обязательством.
        val plainMeta = meta.copy(gkVersion = 0)
        val plain = GroupMessages.sealPreparedPlain(body, plainMeta, sender, editOf = 9).getOrThrow()
        val openedPlain = GroupMessages.open(
            groupId = meta.groupId, senderId = meta.senderId, senderDevice = meta.senderDevice, kind = 1,
            createdAtUnixMs = meta.createdAtUnixMs, threadRoot = 0, replyTo = 0, gkVersion = 0,
            payload = plain.payload, signature = plain.signature, senderSigningPublic = sender.signingPublic,
            groupKey = ByteArray(0), editOf = 9,
        )
        assertNotNull(openedPlain.getOrThrow())
    }
}
