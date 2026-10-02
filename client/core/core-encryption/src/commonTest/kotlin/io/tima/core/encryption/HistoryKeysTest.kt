package io.tima.core.encryption

import io.tima.crypto.EnvelopeMeta
import io.tima.crypto.MessageContent
import io.tima.crypto.MessageSerializer
import io.tima.crypto.Mlkem768
import io.tima.crypto.SealedPersonalMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * История на новом устройстве (ИУ2–ИУ3): старое своё устройство перезаворачивает ключ
 * сообщения, сервер отдаёт конверт с одной обёрткой под новое, новое открывает его так же,
 * как пришедшее сообщение, — подпись и обязательство проверяются по исходному конверту.
 */
class HistoryKeysTest {

    private val escrow = Mlkem768.keyPair()
    private val messages = PersonalMessages(EscrowEpochKey(escrow.first, version = 1))
    private val peer = DeviceIdentity.generate()
    private val old = DeviceIdentity.generate()
    private val fresh = DeviceIdentity.generate()

    private val meta = EnvelopeMeta(
        messageId = 77u,
        chatId = "aaaaaaaa-0000-0000-0000-000000000001",
        senderId = "bbbbbbbb-0000-0000-0000-000000000002",
        senderDevice = "dev-собеседник",
        kind = 1,
        createdAtUnixMs = 1_750_000_000_000,
    )

    private val envelope = messages.seal(
        content = MessageContent.text("до нового телефона"),
        meta = meta,
        sender = peer,
        recipients = listOf(RecipientDevice("dev-старое", old.encryptionPublic)),
    ).getOrThrow()

    /** Так конверт отдаёт сервер новому устройству: обёртка — только его. */
    private fun asServerGives(rewrapped: HistoryKeys.Rewrapped): ByteArray {
        val s = MessageSerializer.decodeEnvelope(envelope).getOrThrow()
        return MessageSerializer.encodeEnvelope(
            SealedPersonalMessage(
                formatVersion = s.formatVersion, meta = s.meta, encryptedPayload = s.encryptedPayload,
                escrow = s.escrow, senderEphemeralPub = s.senderEphemeralPub, ratchetEnvelope = s.ratchetEnvelope,
                signature = s.signature, wrappedKeys = mapOf("dev-новое" to rewrapped.wrapped), keyCommitment = s.keyCommitment,
            ),
        )
    }

    @Test
    fun новое_устройство_читает_перезавёрнутое() {
        val r = HistoryKeys.rewrap(envelope, null, "dev-старое", old, fresh.encryptionPublic)!!
        assertEquals(77L, r.messageId)

        val stored = HistoryFrame.toStored(r.ephemeralPub, asServerGives(r))
        assertTrue(HistoryFrame.isHistory(stored))
        assertEquals("bbbbbbbb-0000-0000-0000-000000000002", PersonalMessages.peekSender(stored)?.userId)

        val got = PersonalMessages.open(stored, "dev-новое", fresh, peer.signingPublic).getOrThrow()
        assertEquals("до нового телефона", got.content.plainText())
    }

    @Test
    fun без_своей_обёртки_перезаворачивать_нечего() {
        assertNull(HistoryKeys.rewrap(envelope, null, "dev-чужое", old, fresh.encryptionPublic))
    }

    @Test
    fun чужое_устройство_перезавёрнутое_не_откроет() {
        val r = HistoryKeys.rewrap(envelope, null, "dev-старое", old, fresh.encryptionPublic)!!
        val stored = HistoryFrame.toStored(r.ephemeralPub, asServerGives(r))
        assertTrue(PersonalMessages.open(stored, "dev-новое", DeviceIdentity.generate(), peer.signingPublic).isFailure)
    }

    @Test
    fun обычный_конверт_не_принимается_за_историю() {
        assertTrue(!HistoryFrame.isHistory(envelope))
        assertEquals(null, HistoryFrame.split(envelope).first)
    }
}
