package io.tima.core.encryption

import io.tima.core.outbox.OutboxEntry
import io.tima.crypto.Mlkem768
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Запечатывание для очереди: **настоящий круг** — конверт собирается сборщиком очереди
 * и открывается получателем.
 *
 * Ключ эпохи escrow здесь настоящий (ML-KEM-768), а не набор байт: `EscrowModule`
 * инкапсулирует на него, и подделка длиной 1184 байта проверила бы только длину.
 */
class OutgoingSealerTest {

    private val отправитель = DeviceIdentity.generate()
    private val получатель = DeviceIdentity.generate()
    private val второеСвоё = DeviceIdentity.generate()

    private val escrowKey = EscrowEpochKey(publicKey = Mlkem768.keyPair().first, version = 7)

    private val адресаты = listOf(
        RecipientDevice("устройство-получателя", получатель.encryptionPublic),
        RecipientDevice("моё-второе", второеСвоё.encryptionPublic),
    )

    private val sealer = OutgoingSealer(
        senderId = "0f8fad5b-d9cb-469f-a165-70867728950e",
        senderDeviceId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        identity = отправитель,
    )

    private fun запись(
        dedupKey: String = "0f8fad5b-d9cb-469f-a165-70867728950e",
        text: String = "привет",
    ) = OutboxEntry(
        dedupKey = dedupKey,
        chatId = "bbbbbbbb-0000-0000-0000-0000000000e1",
        body = TextBodyCodec.encodeText(text),
        createdAtMs = 1_771_200_000_000,
    )

    @Test
    fun запечатанное_открывается_получателем() = run {
        val конверт = sealer.sealerFor(escrowKey, адресаты)(запись())

        val открыто = PersonalMessages.open(
            envelopeBytes = конверт,
            myDeviceId = "устройство-получателя",
            me = получатель,
            senderSigningPublic = отправитель.signingPublic,
        ).getOrThrow()

        assertEquals("привет", открыто.content.plainText())
    }

    @Test
    fun своё_второе_устройство_тоже_прочитает() {
        // Без обёртки на свои устройства отправленное с ПК не читается на телефоне —
        // и выглядит это как пропавшая половина переписки.
        val конверт = sealer.sealerFor(escrowKey, адресаты)(запись())

        val открыто = PersonalMessages.open(
            конверт, myDeviceId = "моё-второе", me = второеСвоё,
            senderSigningPublic = отправитель.signingPublic,
        ).getOrThrow()

        assertEquals("привет", открыто.content.plainText())
    }

    @Test
    fun чужое_устройство_обёртки_не_находит() {
        // Устройство, которого не было в списке адресатов, обёртки для себя не найдёт —
        // и это не «подмена», а несобранная своя картина.
        val посторонний = DeviceIdentity.generate()
        val конверт = sealer.sealerFor(escrowKey, адресаты)(запись())

        val исход = PersonalMessages.open(
            конверт, myDeviceId = "посторонний", me = посторонний,
            senderSigningPublic = отправитель.signingPublic,
        )

        assertTrue(исход.isFailure)
    }

    @Test
    fun идентификатор_сообщения_устойчив_к_повтору() {
        // Он лежит ВНУТРИ подписи. Смени его на повторе — и одно сообщение придёт с
        // двумя разными идентификаторами, а записанное себе разойдётся с серверным.
        val ключ = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals(
            OutgoingSealer.messageIdOf(ключ),
            OutgoingSealer.messageIdOf(ключ),
            "один ключ идемпотентности — одно число, всегда",
        )
        assertNotEquals(
            OutgoingSealer.messageIdOf(ключ),
            OutgoingSealer.messageIdOf("7c9e6679-7425-40de-944b-e07fc1f90ae7"),
        )
    }

    @Test
    fun идентификатор_не_бывает_нулевым() {
        // Ноль в протоколе значит «нет идентификатора» — так читается replyTo.
        assertEquals(1uL, OutgoingSealer.messageIdOf("00000000-0000-0000-a165-70867728950e"))
    }

    @Test
    fun короткий_ключ_идемпотентности_отвергается() {
        assertFailsWith<IllegalArgumentException> { OutgoingSealer.messageIdOf("abc") }
    }

    @Test
    fun без_адресатов_конверт_не_собирается() {
        // Конверт без обёрток ключа не прочитает никто, включая отправителя. Такое
        // лучше не давать выразить.
        assertFailsWith<IllegalArgumentException> { sealer.sealerFor(escrowKey, emptyList()) }
    }

    @Test
    fun тело_не_упаковывается_повторно() {
        // Тело в записи очереди уже zstd(protobuf). Упакуй его ещё раз — и байты
        // разойдутся с теми, что легли в базу, то есть подпись перестанет относиться к
        // тому, что человек видит у себя.
        val запись = запись(text = "проверка двойной упаковки")
        val конверт = sealer.sealerFor(escrowKey, адресаты)(запись)

        val открыто = PersonalMessages.open(
            конверт, "устройство-получателя", получатель, отправитель.signingPublic,
        ).getOrThrow()

        assertEquals("проверка двойной упаковки", открыто.content.plainText())
    }
}
