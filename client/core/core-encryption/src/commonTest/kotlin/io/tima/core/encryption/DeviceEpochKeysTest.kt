package io.tima.core.encryption

import io.tima.crypto.EnvelopeMeta
import io.tima.crypto.MessageContent
import io.tima.crypto.Mlkem768
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ключ шифрования устройства на эпоху (ПЛАН-(ПС) ПС3): завёрнутое под ключ эпохи открывается
 * устройством с этим ключом в кольце и не открывается основным ключом; уничтоженный ключ эпохи
 * своё прошлое уносит. Подпись ключа эпохи проверяется ключом подписи устройства.
 */
class DeviceEpochKeysTest {

    private val messages = PersonalMessages(EscrowEpochKey(Mlkem768.keyPair().first, version = 1))
    private val sender = DeviceIdentity.generate()
    private val recipient = DeviceIdentity.generate()
    private val meta = EnvelopeMeta(
        messageId = 7u,
        chatId = "aaaaaaaa-0000-0000-0000-000000000001",
        senderId = "bbbbbbbb-0000-0000-0000-000000000002",
        senderDevice = "cccccccc-0000-0000-0000-000000000003",
        kind = 1,
        createdAtUnixMs = 1_750_000_000_000,
    )

    private fun sealToPub(pub: ByteArray) = messages.seal(
        content = MessageContent.text("эпоха"), meta = meta, sender = sender,
        recipients = listOf(RecipientDevice("dev-r", pub)),
    ).getOrThrow()

    @Test
    fun завёрнутое_под_ключ_эпохи_открывается_кольцом() {
        val october = DeviceEpochKeys.generate()
        val envelope = sealToPub(DeviceEpochKeys.publicOf(october))
        val ring = recipient.withEpochKeys(listOf(october))
        val got = PersonalMessages.open(envelope, "dev-r", ring, sender.signingPublic).getOrThrow()
        assertEquals("эпоха", got.content.plainText())
    }

    @Test
    fun без_ключа_эпохи_прошлое_не_открывается() {
        // Ключ эпохи уничтожен — основной ключ устройства обёртку под него не открывает: в этом
        // и защита прошлого от утечки ключа устройства потом.
        val october = DeviceEpochKeys.generate()
        val envelope = sealToPub(DeviceEpochKeys.publicOf(october))
        assertTrue(PersonalMessages.open(envelope, "dev-r", recipient, sender.signingPublic).isFailure)
        val november = DeviceEpochKeys.generate()
        assertTrue(PersonalMessages.open(envelope, "dev-r", recipient.withEpochKeys(listOf(november)), sender.signingPublic).isFailure)
    }

    @Test
    fun основной_ключ_в_кольце_по_прежнему_работает() {
        val envelope = sealToPub(recipient.encryptionPublic)
        val ring = recipient.withEpochKeys(listOf(DeviceEpochKeys.generate()))
        assertEquals("эпоха", PersonalMessages.open(envelope, "dev-r", ring, sender.signingPublic).getOrThrow().content.plainText())
    }

    @Test
    fun подпись_ключа_эпохи_проверяется_ключом_подписи_устройства() {
        val pub = DeviceEpochKeys.publicOf(DeviceEpochKeys.generate())
        val sig = DeviceEpochKeys.sign(recipient, "dev-r", "2026-10", pub)!!
        assertTrue(DeviceEpochKeys.valid(recipient.signingPublic, "dev-r", "2026-10", pub, sig))
        assertFalse(DeviceEpochKeys.valid(sender.signingPublic, "dev-r", "2026-10", pub, sig), "чужой ключ подписи")
        assertFalse(DeviceEpochKeys.valid(recipient.signingPublic, "dev-x", "2026-10", pub, sig), "другое устройство")
        assertFalse(DeviceEpochKeys.valid(recipient.signingPublic, "dev-r", "2026-11", pub, sig), "другая эпоха")
    }
}
