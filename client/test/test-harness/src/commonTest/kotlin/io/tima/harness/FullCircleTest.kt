package io.tima.harness

import io.tima.core.database.SqlInboxStore
import io.tima.core.database.TimaDatabase
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.EscrowEpochKey
import io.tima.core.encryption.EscrowKeyVerifier
import io.tima.core.encryption.OutgoingSealer
import io.tima.core.encryption.PersonalMessages
import io.tima.core.encryption.RecipientDevice
import io.tima.core.outbox.Inbox
import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OpenOutcome
import io.tima.core.outbox.OutboxState
import io.tima.crypto.EscrowConfigSignature
import io.tima.crypto.EscrowKeyMeta
import io.tima.crypto.Mlkem768
import io.kodium.Kodium
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Полный круг **без сервера и без подмен в криптографии**: текст, который набрал
 * человек, доходит до текста, который увидит собеседник.
 *
 * ```
 * SendMessage → тело (zstd+protobuf) → очередь → конверт (ML-KEM + Ed25519 + SecretBox)
 *            → транспорт (фейк) → входящая машина → открытие конверта → текст
 * ```
 *
 * Подменён **только транспорт**. Всё остальное настоящее, включая ключ эпохи escrow
 * (ML-KEM-768) и его проверку подписью анклава: цепочка доверия здесь та же, что
 * будет на стенде, — просто анклав свой, тестовый.
 *
 * **Зачем это, если каждая часть проверена своим тестом.** Потому что стыки не
 * проверяются частями. Именно на стыке живут ошибки вроде «тело упаковано дважды»,
 * «конверт собран под чужую эпоху» или «получатель не в списке обёрток» — каждая из
 * них проходит все частные проверки и ломает переписку целиком.
 */
class FullCircleTest {

    // ── тестовый анклав: ключ эпохи и подпись к нему ─────────────────────────

    private val анклав = Kodium.generateKeyPair()
    private val ключЭпохи = Mlkem768.keyPair().first
    private val эпоха = 7

    private val сейчас = 1_771_200_000_000L

    private val мета = EscrowKeyMeta(
        id = эпоха.toLong(),
        region = "ru",
        epoch = "2026-08",
        chatId = "chat-1",
        publicKey = ключЭпохи,
        validFromUnixMs = сейчас - 1_000,
        validToUnixMs = сейчас + 1_000_000,
        destroyAtUnixMs = сейчас + 100_000_000,
    )

    /** Проверенный ключ эпохи — только так его и можно получить. */
    private val проверенный: EscrowEpochKey = EscrowKeyVerifier.verify(
        enclaveSigningPub = анклав.getPublicKey().signingKey,
        id = мета.id,
        region = мета.region,
        chatId = мета.chatId,
        epoch = мета.epoch,
        publicKey = мета.publicKey,
        signature = Kodium.signDetached(
            анклав,
            EscrowConfigSignature.keyMetaSigningBytes(мета),
        ).getOrThrow(),
        validFromMs = мета.validFromUnixMs,
        validToMs = мета.validToUnixMs,
        destroyAtMs = мета.destroyAtUnixMs,
        nowMs = сейчас,
    ).getOrThrow()

    // ── два устройства: отправитель и получатель ─────────────────────────────

    private val отправитель = DeviceIdentity.generate()
    private val получатель = DeviceIdentity.generate()

    private val sealer = OutgoingSealer(
        senderId = "0f8fad5b-d9cb-469f-a165-70867728950e",
        senderDeviceId = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
        identity = отправитель,
    ).sealerFor(
        escrowKey = проверенный,
        recipients = listOf(RecipientDevice("устройство-получателя", получатель.encryptionPublic)),
    )

    /** Очередь отправителя: с настоящим сборщиком конвертов. */
    private val у_отправителя = ChatHarness(harnessDriver(), sealWith = sealer)

    /** Своя база получателя: у него отдельное устройство, значит и хранилище своё. */
    private val база_получателя = TimaDatabase(harnessDriver())
    private val у_получателя = Inbox(SqlInboxStore(база_получателя, харнессШифр()), nowMs = { сейчас })

    @Test
    fun текст_доходит_до_собеседника_целиком() = runTest {
        val текст = "Привет! Это первое сквозное сообщение. 🙂"

        // 1. Человек нажал «отправить».
        у_отправителя.send("chat-1", текст)

        // 2. Очередь запечатала и отдала транспорту.
        assertEquals(1, у_отправителя.pumpOnce())
        assertEquals(1, у_отправителя.transport.deliveredCount())
        val конверт = у_отправителя.transport.attempts.single().envelope

        // 3. Тот же конверт пришёл получателю живым каналом.
        у_получателя.receive("chat-1", messageId = 1, envelope = конверт)

        // 4. Получатель открыл его своим ключом.
        у_получателя.openNext(
            open = { запись ->
                PersonalMessages.open(
                    envelopeBytes = запись.envelope,
                    myDeviceId = "устройство-получателя",
                    me = получатель,
                    senderSigningPublic = отправитель.signingPublic,
                ).fold(
                    onSuccess = { OpenOutcome.Opened(it.content.plainText().encodeToByteArray()) },
                    onFailure = { OpenOutcome.NoKey(it.message ?: "не открылось") },
                )
            },
        )

        assertEquals(текст, теломПолучателя(1), "до собеседника обязан дойти тот же текст")
        assertEquals(
            IncomingState.STORED,
            SqlInboxStore(база_получателя, харнессШифр()).byKey("chat-1", 1)?.state,
        )
    }

    @Test
    fun конверт_не_читается_чужим_устройством() = runTest {
        // Обратная сторона того же круга: конверт адресован обёртками, и устройство, для
        // которого обёртки нет, прочитать его не может. Иначе шифрование было бы
        // украшением.
        у_отправителя.send("chat-1", "секрет")
        у_отправителя.pumpOnce()
        val конверт = у_отправителя.transport.attempts.single().envelope

        val посторонний = DeviceIdentity.generate()
        у_получателя.receive("chat-1", 1, конверт)

        у_получателя.openNext(
            open = { запись ->
                PersonalMessages.open(запись.envelope, "посторонний", посторонний, отправитель.signingPublic)
                    .fold(
                        onSuccess = { OpenOutcome.Opened(byteArrayOf()) },
                        onFailure = { OpenOutcome.NoKey("нет обёртки для этого устройства") },
                    )
            },
        )

        assertEquals(
            IncomingState.UNDECRYPTABLE,
            SqlInboxStore(база_получателя, харнессШифр()).byKey("chat-1", 1)?.state,
            "нечитаемое остаётся видимым, а не исчезает",
        )
    }

    @Test
    fun повтор_после_обрыва_доходит_и_открывается() = runTest {
        // Тот же круг, но через обрыв. Проверяется то, что повтор внутри одной эпохи
        // уходит ТЕМИ ЖЕ байтами: конверт берётся из кэша, а не собирается заново.
        // Отсюда два следствия, оба наблюдаемые: сервер опознаёт повтор по dedup_key и
        // не создаёт второго сообщения, а получатель открывает конверт как обычный.
        // Пересборка происходит только при смене эпохи — это отдельный сценарий.
        у_отправителя.transport.then(FakeTransport.Behaviour.Offline(retryAfterMs = 1_000))
        у_отправителя.send("chat-1", "после обрыва")

        у_отправителя.pumpOnce()
        assertEquals(0, у_отправителя.transport.deliveredCount())
        у_отправителя.passTime(1_000)
        у_отправителя.pumpOnce()

        assertEquals(2, у_отправителя.transport.attempts.size)
        assertEquals(1, у_отправителя.transport.deliveredCount())
        assertTrue(
            у_отправителя.transport.attempts[0].envelope
                .contentEquals(у_отправителя.transport.attempts[1].envelope),
            "повтор внутри эпохи обязан уйти теми же байтами: конверт берётся из кэша",
        )

        у_получателя.receive("chat-1", 1, у_отправителя.transport.attempts[1].envelope)
        у_получателя.openNext(
            open = { запись ->
                PersonalMessages.open(запись.envelope, "устройство-получателя", получатель, отправитель.signingPublic)
                    .fold(
                        onSuccess = { OpenOutcome.Opened(it.content.plainText().encodeToByteArray()) },
                        onFailure = { OpenOutcome.NoKey(it.message ?: "не открылось") },
                    )
            },
        )

        assertEquals("после обрыва", теломПолучателя(1))
        assertEquals(0, у_отправителя.pending().size, "у отправителя очередь пуста")
    }

    @Test
    fun непроверенный_ключ_эпохи_запечатать_не_даёт() {
        // Замыкание цепочки доверия: сборщик конвертов принимает только то, что вернул
        // проверяющий. Подменённый ключ до запечатывания не доходит вовсе.
        val подменённый = Mlkem768.keyPair().first
        val исход = EscrowKeyVerifier.verify(
            enclaveSigningPub = анклав.getPublicKey().signingKey,
            id = мета.id,
            region = мета.region,
            chatId = мета.chatId,
            epoch = мета.epoch,
            publicKey = подменённый,
            signature = Kodium.signDetached(
                анклав,
                EscrowConfigSignature.keyMetaSigningBytes(мета),
            ).getOrThrow(),
            validFromMs = мета.validFromUnixMs,
            validToMs = мета.validToUnixMs,
            destroyAtMs = мета.destroyAtUnixMs,
            nowMs = сейчас,
        )

        assertTrue(исход.isFailure, "подпись покрывает ключ: подмена обязана быть замечена")
    }

    @Test
    fun очередь_и_переписка_согласны_после_круга() = runTest {
        // У отправителя сообщение обязано остаться видимым как отправленное — то есть
        // очередь и переписка не расходятся.
        у_отправителя.send("chat-1", "проверка согласия")
        у_отправителя.pumpOnce()

        val переписка = у_отправителя.chatPage("chat-1")
        assertEquals(1, переписка.size)
        assertEquals(OutboxState.SENT, переписка.single().state)
        assertTrue(переписка.single().outgoing)
        assertEquals(0, у_отправителя.pending().size)
    }

    /**
     * Тело, ЛЕЖАЩЕЕ В БАЗЕ получателя.
     *
     * Раньше здесь проверялась переменная, в которую писала лямбда `persist`. Она и
     * скрывала настоящую поломку: лямбда была у каждого вызывающего своя и всюду пустая
     * или тестовая, а в базу тело не ложилось вовсе — состояние `STORED` означало
     * «разобрано и потеряно». Теперь проверка смотрит туда, откуда читает экран.
     */
    private fun теломПолучателя(messageId: Long): String? {
        val строка = база_получателя.messagesQueries
            .byDedupKey("chat-1/$messageId")
            .executeAsOneOrNull() ?: return null
        return харнессШифр().open(строка.body_enc)?.decodeToString()
    }
}
