package io.tima.core.encryption

import io.tima.core.outbox.OutboxEntry
import io.tima.crypto.MessageSerializer
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

    private val sender = DeviceIdentity.generate()
    private val recipient = DeviceIdentity.generate()
    private val secondOwn = DeviceIdentity.generate()

    private val escrowKey = EscrowEpochKey(publicKey = Mlkem768.keyPair().first, version = 7)

    private val recipients = listOf(
        RecipientDevice("устройство-получателя", recipient.encryptionPublic),
        RecipientDevice("моё-второе", secondOwn.encryptionPublic),
    )

    private val sealer = OutgoingSealer(
        senderId = "0f8fad5b-d9cb-469f-a165-70867728950e",
        senderDeviceId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        identity = sender,
    )

    private fun entry(
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
        val envelope = sealer.sealerFor(escrowKey, recipients)(entry())

        val open = PersonalMessages.open(
            envelopeBytes = envelope,
            myDeviceId = "устройство-получателя",
            me = recipient,
            senderSigningPublic = sender.signingPublic,
        ).getOrThrow()

        assertEquals("привет", open.content.plainText())
    }

    @Test
    fun своё_второе_устройство_тоже_прочитает() {
        // Без обёртки на свои устройства отправленное с ПК не читается на телефоне —
        // и выглядит это как пропавшая половина переписки.
        val envelope = sealer.sealerFor(escrowKey, recipients)(entry())

        val open = PersonalMessages.open(
            envelope, myDeviceId = "моё-второе", me = secondOwn,
            senderSigningPublic = sender.signingPublic,
        ).getOrThrow()

        assertEquals("привет", open.content.plainText())
    }

    @Test
    fun чужое_устройство_обёртки_не_находит() {
        // Устройство, которого не было в списке адресатов, обёртки для себя не найдёт —
        // и это не «подмена», а несобранная своя картина.
        val outsider = DeviceIdentity.generate()
        val envelope = sealer.sealerFor(escrowKey, recipients)(entry())

        val outcome = PersonalMessages.open(
            envelope, myDeviceId = "посторонний", me = outsider,
            senderSigningPublic = sender.signingPublic,
        )

        assertTrue(outcome.isFailure)
    }

    /**
     * **Кто прислал — видно из конверта до расшифровки**, и получателю это нужно.
     *
     * Живой канал приносит только `chat_id`, `message_id` и байты конверта — отправителя в
     * событии нет. Открыть же конверт можно, лишь зная ключ подписи отправителя. Выход
     * один: прочитать открытую часть конверта, спросить по ней ключ и проверить подпись.
     *
     * Имя из конверта при этом **подсказка, а не утверждение**: доверенным оно становится
     * после проверки подписи. Соври отправитель чужим идентификатором — мы спросим чужой
     * ключ, подпись не сойдётся, и сообщение станет нечитаемым, а не «чужим».
     */
    @Test
    fun отправитель_читается_из_конверта_до_расшифровки() {
        val envelope = sealer.sealerFor(escrowKey, recipients)(entry())

        val parsed = MessageSerializer.decodeEnvelope(envelope).getOrThrow()

        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e", parsed.meta.senderId)
        assertEquals("7c9e6679-7425-40de-944b-e07fc1f90ae7", parsed.meta.senderDevice)
        assertEquals("bbbbbbbb-0000-0000-0000-0000000000e1", parsed.meta.chatId)
    }

    /**
     * Подмена отправителя не проходит: подпись проверяется тем ключом, который назвали.
     *
     * Это и есть цена подсказки из открытой части — её проверяет подпись, а не доверие.
     */
    @Test
    fun чужим_ключом_подписи_конверт_не_открывается() {
        val envelope = sealer.sealerFor(escrowKey, recipients)(entry())
        val outsider = DeviceIdentity.generate()

        val outcome = PersonalMessages.open(
            envelopeBytes = envelope,
            myDeviceId = "устройство-получателя",
            me = recipient,
            senderSigningPublic = outsider.signingPublic,
        )

        assertTrue(outcome.isFailure, "подпись обязана не сойтись")
    }

    @Test
    fun идентификатор_сообщения_устойчив_к_повтору() {
        // Он лежит ВНУТРИ подписи. Смени его на повторе — и одно сообщение придёт с
        // двумя разными идентификаторами, а записанное себе разойдётся с серверным.
        val key = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals(
            OutgoingSealer.messageIdOf(key),
            OutgoingSealer.messageIdOf(key),
            "один ключ идемпотентности — одно число, всегда",
        )
        assertNotEquals(
            OutgoingSealer.messageIdOf(key),
            OutgoingSealer.messageIdOf("7c9e6679-7425-40de-944b-e07fc1f90ae7"),
        )
    }

    /**
     * **Идентификатор всегда влезает в знаковый int64.**
     *
     * Сервер хранит `message_id` в `bigint`. На проводе поле `uint64`, и число с
     * установленным старшим битом проходит по проводу прекрасно — а на записи Postgres
     * отвечает ошибкой кодирования, и сервер отдаёт `500`.
     *
     * Найдено прогоном по стенду: половина сообщений уходила, половина получала `500`.
     * РОВНО ПОЛОВИНА — старший бит случайного UUID установлен в половине случаев, поэтому
     * предыдущий прогон и был зелёным. Здесь гоняется тысяча ключей: случайность в этой
     * проверке не помощник, а то, из-за чего поломку не увидели раньше.
     */
    @Test
    fun идентификатор_влезает_в_знаковый_int64() {
        for (i in 0 until 1_000) {
            // Меняется ВОСЬМОЙ байт: порядок little-endian, и старший бит числа берётся
            // именно из него. Перебор всех 256 значений включает те, где он установлен —
            // на них всё и падало. `String.format` в общем коде нет, и это правильно: он
            // есть только на JVM.
            val high = ((i * 7) and 0xFF).toString(16).padStart(2, '0')
            val key = ("0f8fad5bd9cb46" + high).padEnd(32, '0')
            val id = OutgoingSealer.messageIdOf(key)
            assertTrue(
                id <= Long.MAX_VALUE.toULong(),
                "идентификатор $id не влезает в bigint сервера",
            )
            assertTrue(id > 0uL, "ноль в протоколе значит «нет идентификатора»")
        }
    }

    /** Старший бит сбрасывается, а остальные шестьдесят три остаются как есть. */
    @Test
    fun сбрасывается_ровно_старший_бит() {
        // Первые 8 байт: ff ff ff ff ff ff ff ff → все единицы, LE или BE — неважно.
        val allUnits = OutgoingSealer.messageIdOf("ffffffff-ffff-ffff-0000-000000000000")
        assertEquals(Long.MAX_VALUE.toULong(), allUnits, "остаться должны все биты, кроме старшего")

        // 0x7f… старшего бита не имеет и обязан пройти без изменений.
        val seniorWithout = OutgoingSealer.messageIdOf("01000000-0000-007f-0000-000000000000")
        assertEquals(0x7F00_0000_0000_0001uL, seniorWithout)
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
        val entry = entry(text = "проверка двойной упаковки")
        val envelope = sealer.sealerFor(escrowKey, recipients)(entry)

        val open = PersonalMessages.open(
            envelope, "устройство-получателя", recipient, sender.signingPublic,
        ).getOrThrow()

        assertEquals("проверка двойной упаковки", open.content.plainText())
    }
}
