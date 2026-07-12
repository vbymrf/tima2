package io.tima.crypto

import io.kodium.Kodium
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Roundtrip полного протокола (слои 1+2+4 + подпись) и негативные сценарии.
 * Здесь рандом боевой — проверяется семантика, не байты (байты — в VectorsTest).
 */
class ProtocolRoundTripTest {

    private val escrowKeyPair = Mlkem768.keyPair() // Pair(public, secret)
    private val escrowModule = EscrowModule(escrowKeyPair.first, escrowKeyVersion = 1)
    private val sealer = PersonalMessageSealer(escrowModule)

    private val senderDeviceKey = Kodium.generateKeyPair()
    private val recipientDeviceKey = Kodium.generateKeyPair()
    private val senderSecondDeviceKey = Kodium.generateKeyPair()

    private val meta = EnvelopeMeta(
        messageId = 7u,
        chatId = "aaaaaaaa-0000-0000-0000-000000000001",
        senderId = "bbbbbbbb-0000-0000-0000-000000000002",
        senderDevice = "cccccccc-0000-0000-0000-000000000003",
        kind = 1, // CK_TEXT
        createdAtUnixMs = 1_750_000_000_000,
    )

    private val payload = "Съешь ещё этих мягких французских булок 🥖".encodeToByteArray()

    private fun seal(): SealedPersonalMessage = sealer.seal(
        meta = meta,
        payloadPlaintext = payload,
        senderDeviceKey = senderDeviceKey,
        recipientDevices = listOf(
            DeviceAddress("recipient-dev-1", recipientDeviceKey.getPublicKey().encryptionKey),
            DeviceAddress("sender-dev-2", senderSecondDeviceKey.getPublicKey().encryptionKey),
        ),
    ).getOrThrow()

    @Test
    fun `путь B - получатель разворачивает wrapped_key и читает payload`() {
        val message = seal()
        val opened = PersonalMessageSealer.openWithWrappedKey(
            message, "recipient-dev-1", recipientDeviceKey, senderDeviceKey.getPublicKey().signingKey,
        ).getOrThrow()
        assertEquals(payload.toHex(), opened.toHex())
    }

    @Test
    fun `мультиустройство - второе устройство отправителя тоже читает`() {
        val message = seal()
        val opened = PersonalMessageSealer.openWithWrappedKey(
            message, "sender-dev-2", senderSecondDeviceKey, senderDeviceKey.getPublicKey().signingKey,
        ).getOrThrow()
        assertEquals(payload.toHex(), opened.toHex())
    }

    @Test
    fun `escrow - stub-анклав восстанавливает message_key и открывает конверт`() {
        val message = seal()
        val messageKey = EscrowModule.unwrap(message.escrow, escrowKeyPair.second).getOrThrow()
        val opened = EnvelopeCipher.open(messageKey, message.encryptedPayload).getOrThrow()
        assertEquals(payload.toHex(), opened.toHex())
        assertEquals(1, message.escrow.escrowKeyVersion)
        assertEquals(Mlkem768.CiphertextSize, message.escrow.mlkemCt.size)
    }

    @Test
    fun `подмена payload - подпись не проходит, расшифровка не выполняется`() {
        val message = seal()
        val tampered = SealedPersonalMessage(
            formatVersion = message.formatVersion,
            meta = message.meta,
            encryptedPayload = message.encryptedPayload.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() },
            escrow = message.escrow,
            senderEphemeralPub = message.senderEphemeralPub,
            ratchetEnvelope = message.ratchetEnvelope,
            signature = message.signature,
            wrappedKeys = message.wrappedKeys,
        )
        val result = PersonalMessageSealer.openWithWrappedKey(
            tampered, "recipient-dev-1", recipientDeviceKey, senderDeviceKey.getPublicKey().signingKey,
        )
        assertTrue(result.isFailure, "Повреждённый payload обязан провалить подпись")
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `подмена метаданных - подпись не проходит`() {
        val message = seal()
        val tampered = SealedPersonalMessage(
            formatVersion = message.formatVersion,
            meta = message.meta.copy(senderId = "dddddddd-0000-0000-0000-00000000000d"),
            encryptedPayload = message.encryptedPayload,
            escrow = message.escrow,
            senderEphemeralPub = message.senderEphemeralPub,
            ratchetEnvelope = message.ratchetEnvelope,
            signature = message.signature,
            wrappedKeys = message.wrappedKeys,
        )
        val result = PersonalMessageSealer.openWithWrappedKey(
            tampered, "recipient-dev-1", recipientDeviceKey, senderDeviceKey.getPublicKey().signingKey,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    @Test
    fun `подмена wrapped_key - провал расшифровки, не поддельный текст`() {
        val message = seal()
        val foreign = WrappedKeyService.wrap(
            Kodium.generateKeyPair(), recipientDeviceKey.getPublicKey().encryptionKey, Kodium.generateHighEntropyKey(),
        ).getOrThrow()
        val tampered = SealedPersonalMessage(
            formatVersion = message.formatVersion,
            meta = message.meta,
            encryptedPayload = message.encryptedPayload,
            escrow = message.escrow,
            senderEphemeralPub = message.senderEphemeralPub,
            ratchetEnvelope = message.ratchetEnvelope,
            signature = message.signature,
            wrappedKeys = mapOf("recipient-dev-1" to foreign),
        )
        // Подпись валидна (wrapped_keys в canonical_bytes не входят), но конверт не откроется:
        // чужой wrapped_key даёт либо провал Box.open, либо ключ, не проходящий MAC SecretBox.
        val result = PersonalMessageSealer.openWithWrappedKey(
            tampered, "recipient-dev-1", recipientDeviceKey, senderDeviceKey.getPublicKey().signingKey,
        )
        assertTrue(result.isFailure, "Подменённый wrapped_key не должен давать расшифровку")
    }

    @Test
    fun `чужое устройство - wrapped_key не разворачивается`() {
        val message = seal()
        val outsider = Kodium.generateKeyPair()
        val result = PersonalMessageSealer.openWithWrappedKey(
            message, "recipient-dev-1", outsider, senderDeviceKey.getPublicKey().signingKey,
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `нет wrapped_key для устройства - явная ошибка`() {
        val message = seal()
        val result = PersonalMessageSealer.openWithWrappedKey(
            message, "unknown-dev", recipientDeviceKey, senderDeviceKey.getPublicKey().signingKey,
        )
        assertTrue(result.isFailure)
        result.exceptionOrNull()?.message?.let { assertTrue("unknown-dev" in it) } ?: fail("Нет сообщения об ошибке")
    }

    @Test
    fun `повреждённый escrow_blob - unwrap падает, а не возвращает мусор`() {
        val message = seal()
        val damaged = EscrowBlob(
            mlkemCt = message.escrow.mlkemCt.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() },
            wrappedMessageKey = message.escrow.wrappedMessageKey,
            escrowKeyVersion = message.escrow.escrowKeyVersion,
        )
        // ML-KEM: implicit rejection даёт другой shared → SecretBox MAC провалится
        assertTrue(EscrowModule.unwrap(damaged, escrowKeyPair.second).isFailure)
    }
}
