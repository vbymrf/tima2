package io.tima.core.encryption

import io.tima.crypto.AccountMnemonic
import io.tima.crypto.EnvelopeMeta
import io.tima.crypto.MessageContent
import io.tima.crypto.MessageSerializer
import io.tima.crypto.Mlkem768
import io.tima.crypto.SealedPersonalMessage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Копия ключей по модели Matrix (§3а): пара копии из фразы, обёртка ключа сообщения в копию,
 * восстановление на новом устройстве тем же разбором, что живые сообщения.
 */
class KeyCopyTest {

    /** Фиксированная фраза — из нулевой энтропии: вектор ниже закреплён на ней. */
    private val words = AccountMnemonic.entropyToMnemonic(ByteArray(16))

    private val escrow = Mlkem768.keyPair()
    private val messages = PersonalMessages(EscrowEpochKey(escrow.first, version = 1))
    private val peer = DeviceIdentity.generate()
    private val phone = DeviceIdentity.generate()
    private val fresh = DeviceIdentity.generate()

    private val envelope = messages.seal(
        content = MessageContent.text("в копию и обратно"),
        meta = EnvelopeMeta(
            messageId = 501u,
            chatId = "aaaaaaaa-0000-0000-0000-000000000501",
            senderId = "bbbbbbbb-0000-0000-0000-000000000502",
            senderDevice = "dev-собеседник",
            kind = 1,
            createdAtUnixMs = 1_750_000_000_000,
        ),
        sender = peer,
        recipients = listOf(RecipientDevice("dev-телефон", phone.encryptionPublic)),
    ).getOrThrow()

    @Test
    fun пара_копии_выводится_из_фразы_и_закреплена() {
        val one = KeyCopy.fromWords(words, epoch = 1)!!
        // Вектор закреплён: смена метки или вывода сделала бы все копии нечитаемыми. Значение
        // посчитано этим же кодом 2026-10-05 — это сторож от случайной правки, а не внешняя
        // проверка: внешний вектор (schema/test-vectors) на эту метку не заводился.
        assertEquals(
            "8c3fa7260490557ed8c4efcea0f1bb131989a9ae750ec57dc5d4ead9eda13715",
            one.encryptionPublic.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
        )
        assertContentEquals(one.encryptionPublic, KeyCopy.fromWords(words, epoch = 1)!!.encryptionPublic)
        assertFalse(one.encryptionPublic.contentEquals(KeyCopy.fromWords(words, epoch = 2)!!.encryptionPublic), "эпохи обязаны различаться")
        val identity = AccountMnemonic.identityFromMnemonic(words).getPublicKey().encryptionKey
        assertFalse(one.encryptionPublic.contentEquals(identity), "ключ копии не обязан совпадать с ключом личности")
        assertNull(KeyCopy.fromWords(listOf("не", "фраза"), epoch = 1))
    }

    @Test
    fun ключ_копии_подписан_ключом_личности() {
        val pub = KeyCopy.fromWords(words, 1)!!.encryptionPublic
        val sig = KeyCopy.sign(words, 1, pub)!!
        val identity = AccountMnemonic.identityFromMnemonic(words).getPublicKey().signingKey
        assertTrue(KeyCopy.verify(identity, 1, pub, sig))
        assertFalse(KeyCopy.verify(identity, 2, pub, sig), "подпись привязана к эпохе")
        assertFalse(KeyCopy.verify(DeviceIdentity.generate().signingPublic, 1, pub, sig), "чужой ключ личности")
    }

    @Test
    fun сообщение_из_копии_открывается_на_новом_устройстве() {
        val copy = KeyCopy.fromWords(words, 1)!!
        // Телефон пополняет копию: перезаворачивает ключ из своей обёртки под открытый ключ копии.
        val blob = assertNotNull(KeyCopy.wrapMessage(envelope, null, "dev-телефон", phone, copy.encryptionPublic))

        // Так страницу копии отдаёт сервер: обёртка для key-copy в конверте, эфемерал рядом.
        val s = MessageSerializer.decodeEnvelope(envelope).getOrThrow()
        val page = MessageSerializer.encodeEnvelope(
            SealedPersonalMessage(
                formatVersion = s.formatVersion, meta = s.meta, encryptedPayload = s.encryptedPayload,
                escrow = s.escrow, senderEphemeralPub = s.senderEphemeralPub, ratchetEnvelope = s.ratchetEnvelope,
                signature = s.signature, wrappedKeys = mapOf(KeyCopy.RECIPIENT to blob.copyOfRange(32, blob.size)),
                keyCommitment = s.keyCommitment,
            ),
        )

        // Новое устройство с фразой: копия → своя обёртка → тот же разбор.
        val stored = assertNotNull(KeyCopy.restoreFor(page, blob.copyOfRange(0, 32), copy, "dev-новое", fresh.encryptionPublic))
        val got = PersonalMessages.open(stored, "dev-новое", fresh, peer.signingPublic).getOrThrow()
        assertEquals("в копию и обратно", got.content.plainText())

        // Другой эпохой копию не открыть.
        assertNull(KeyCopy.restoreFor(page, blob.copyOfRange(0, 32), KeyCopy.fromWords(words, 2)!!, "dev-новое", fresh.encryptionPublic))
    }

    @Test
    fun ключ_группы_в_копии_и_обратно() {
        val copy = KeyCopy.fromWords(words, 1)!!
        val gk = ByteArray(32) { it.toByte() }
        val blob = assertNotNull(KeyCopy.wrapGroupKey(copy.encryptionPublic, gk))
        assertContentEquals(gk, KeyCopy.openGroupKey(copy, blob))
        assertNull(KeyCopy.openGroupKey(KeyCopy.fromWords(words, 2)!!, blob))
    }
}
