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

    @Test
    fun простое_сообщение_правится_простым_текстом() {
        val original = TextBodyCodec.encodeTyped("просто")
        val edit = assertIs<EditBody.Changes>(TextBodyCodec.encodeEdit(original, emptyList(), "стало **так**", marks = true))
        // У сообщения без оформления знаки остаются текстом: строение правка не меняет.
        assertEquals("стало **так**", TextBodyCodec.decodeEdited(original, listOf(edit.body))!!.text)
    }

    @Test
    fun таблицу_строкой_ввода_не_правят() {
        assertNull(TextBodyCodec.editSource(TextBodyCodec.encodeTyped("| a | b |\n|---|---|\n| 1 | 2 |"), emptyList()))
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
