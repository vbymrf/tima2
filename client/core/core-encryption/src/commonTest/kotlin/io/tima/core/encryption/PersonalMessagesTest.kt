package io.tima.core.encryption

import io.tima.crypto.EnvelopeMeta
import io.tima.crypto.EntityType
import io.tima.crypto.Markup
import io.tima.crypto.MarkupEntity
import io.tima.crypto.MessageContent
import io.tima.crypto.Mlkem768
import io.tima.crypto.VerificationFailure
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Проверка фасада целиком: содержимое → конверт → содержимое.
 *
 * Тесты стоят в `commonTest`, а не в `jvmTest`, намеренно: фасад — единственная
 * дверь слоя Data в криптографию, и если она работает только на JVM, то iOS не
 * отправит ни одного сообщения. Байты векторов проверяет `messenger-crypto`; здесь
 * проверяется, что четыре шага собраны в правильном порядке.
 */
class PersonalMessagesTest {

    private val escrow = Mlkem768.keyPair() // Pair(открытый, закрытый)
    private val messages = PersonalMessages(EscrowEpochKey(escrow.first, version = 1))

    private val sender = DeviceIdentity.generate()
    private val recipient = DeviceIdentity.generate()
    private val senderSecond = DeviceIdentity.generate()

    private val meta = EnvelopeMeta(
        messageId = 42u,
        chatId = "aaaaaaaa-0000-0000-0000-000000000001",
        senderId = "bbbbbbbb-0000-0000-0000-000000000002",
        senderDevice = "cccccccc-0000-0000-0000-000000000003",
        kind = 1, // CK_TEXT
        createdAtUnixMs = 1_750_000_000_000,
    )

    private fun sealTo(vararg devices: Pair<String, DeviceIdentity>): ByteArray =
        messages.seal(
            content = MessageContent.text("Съешь ещё этих мягких французских булок 🥖"),
            meta = meta,
            sender = sender,
            recipients = devices.map { (id, who) -> RecipientDevice(id, who.encryptionPublic) },
        ).getOrThrow()

    @Test
    fun сообщение_собирается_и_читается_получателем() {
        val envelope = sealTo("dev-получатель" to recipient)

        val got = PersonalMessages.open(
            envelopeBytes = envelope,
            myDeviceId = "dev-получатель",
            me = recipient,
            senderSigningPublic = sender.signingPublic,
        ).getOrThrow()

        assertEquals("Съешь ещё этих мягких французских булок 🥖", got.content.plainText())
        assertEquals(meta.messageId, got.meta.messageId)
        assertEquals(meta.chatId, got.meta.chatId)
    }

    @Test
    fun своё_второе_устройство_тоже_читает() {
        // Без обёртки на свои устройства отправленное с телефона не открылось бы на
        // ПК. Это не удобство, а условие работы истории на новом устройстве.
        val envelope = sealTo(
            "dev-получатель" to recipient,
            "dev-своё-второе" to senderSecond,
        )

        val got = PersonalMessages.open(
            envelope, "dev-своё-второе", senderSecond, sender.signingPublic,
        ).getOrThrow()

        assertEquals("Съешь ещё этих мягких французских булок 🥖", got.content.plainText())
    }

    @Test
    fun разметка_переживает_путь_туда_и_обратно() {
        // Разметка ссылается на узлы по идентификаторам (ADR-0011), и это как раз то,
        // что легко потерять при сборке тела: узлы едут отдельно от разметки.
        val content = MessageContent(
            nodes = listOf("Заголовок", "и текст"),
            markup = Markup(
                // n — идентификаторы узлов параллельно nodes; сущность ссылается на
                // узел по идентификатору, а не по смещению в склеенной строке. В этом
                // и смысл ADR-0011, и это то, что легко потерять при сборке тела.
                n = listOf(1, 2),
                entities = listOf(MarkupEntity(type = EntityType.BOLD, nodeId = 1, length = 9)),
            ),
        )
        val envelope = messages.seal(
            content, meta, sender, listOf(RecipientDevice("dev-1", recipient.encryptionPublic)),
        ).getOrThrow()

        val got = PersonalMessages.open(envelope, "dev-1", recipient, sender.signingPublic)
            .getOrThrow()

        assertEquals(listOf("Заголовок", "и текст"), got.content.nodes)
        assertTrue(got.content.hasMarkup, "разметка потерялась на пути")
    }

    @Test
    fun чужая_подпись_отвергается_и_именно_как_подмена() {
        val envelope = sealTo("dev-получатель" to recipient)
        val foreign = DeviceIdentity.generate()

        val result = PersonalMessages.open(
            envelope, "dev-получатель", recipient, foreign.signingPublic,
        )

        assertTrue(result.isFailure, "конверт с чужой подписью не должен открываться")
        // Тип важен: подмена и «нет обёртки для устройства» — разные события, и
        // вызывающий обязан их различать. Одинаковое исключение это различие стёрло бы.
        assertIs<VerificationFailure>(result.exceptionOrNull())
    }

    @Test
    fun устройство_без_своей_обёртки_не_открывает() {
        val envelope = sealTo("dev-получатель" to recipient)

        val result = PersonalMessages.open(
            envelope, "dev-которого-не-приглашали", senderSecond, sender.signingPublic,
        )

        assertTrue(result.isFailure)
        // Не VerificationFailure: подпись как раз в порядке, просто ключа нет.
        assertTrue(
            result.exceptionOrNull() !is VerificationFailure,
            "отсутствие обёртки не должно выглядеть как подмена: это разные причины",
        )
    }

    @Test
    fun ключи_устройства_переживают_выгрузку_и_восстановление() {
        // Мост в хранилище: ключ уезжает байтами и возвращается. Если восстановление
        // даёт другой ключ, старая переписка становится нечитаемой — и заметно это
        // будет только на втором запуске приложения.
        val raw = recipient.exportRaw()
        val restored = DeviceIdentity.fromRaw(raw)

        assertContentEquals(recipient.encryptionPublic, restored.encryptionPublic)
        assertContentEquals(recipient.signingPublic, restored.signingPublic)

        val envelope = sealTo("dev-получатель" to recipient)
        val got = PersonalMessages.open(
            envelope, "dev-получатель", restored, sender.signingPublic,
        ).getOrThrow()
        assertEquals("Съешь ещё этих мягких французских булок 🥖", got.content.plainText())
    }

    @Test
    fun пустой_список_адресатов_отвергается_на_входе() {
        // Конверт без обёрток технически собирается, но не открывается никем — то
        // есть это сообщение, потерянное молча. Лучше отказ на входе.
        val result = messages.seal(MessageContent.text("привет"), meta, sender, emptyList())
        assertTrue(result.isFailure, "пустой список адресатов должен отвергаться")
    }
}
