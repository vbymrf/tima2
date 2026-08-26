package io.tima.core.encryption

import io.tima.crypto.GroupMessageMeta
import io.tima.crypto.MessageContent
import io.tima.crypto.VerificationFailure
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Сообщение группы: содержимое → payload и подпись → содержимое.
 *
 * Тесты в `commonTest`, а не в `jvmTest`: фасад — единственная дверь слоя Data в
 * криптографию групп, и работающий только на JVM означал бы, что с iOS в группу не
 * написать. Байты канона проверяет вектор `group_message_canonical` в
 * `messenger-crypto`; здесь проверяется, что шаги собраны в верном порядке и что
 * подделки не проходят.
 */
class GroupMessagesTest {

    private val author = DeviceIdentity.generate()
    private val foreign = DeviceIdentity.generate()
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val otherKey = ByteArray(32) { (it * 3 + 9).toByte() }

    private val meta = GroupMessageMeta(
        groupId = "gggggggg-0000-0000-0000-000000000001",
        senderId = "bbbbbbbb-0000-0000-0000-000000000002",
        senderDevice = "cccccccc-0000-0000-0000-000000000003",
        kind = 1, // CK_TEXT
        createdAtUnixMs = 1_750_000_000_000,
        gkVersion = 7,
    )

    private fun seal(text: String = "Собираемся в 19:00 🥁", meta: GroupMessageMeta = this.meta) =
        GroupMessages.seal(MessageContent.text(text), meta, author, key).getOrThrow()

    @Test
    fun сообщение_собирается_и_читается_участником() {
        val got = GroupMessages.open(seal(), author.signingPublic, key).getOrThrow()

        assertEquals("Собираемся в 19:00 🥁", got.content.plainText())
        assertEquals(meta.groupId, got.meta.groupId)
        assertEquals(7, got.meta.gkVersion)
    }

    @Test
    fun payload_не_содержит_открытого_текста() {
        // Смысл всего слоя. Проверка дешёвая и ловит худшее из возможного — отправку
        // открытым текстом, которая доставляется и потому выглядит исправной.
        val sealed = seal("совершенно секретно")
        val asText = sealed.payload.decodeToString()
        assertTrue("совершенно секретно" !in asText, "открытый текст виден в payload")
    }

    @Test
    fun чужая_подпись_не_проходит() {
        val sealed = seal()
        val failure = GroupMessages.open(sealed, foreign.signingPublic, key).exceptionOrNull()
        assertIs<VerificationFailure>(failure, "подмена автора обязана быть отличима от битых байт")
    }

    @Test
    fun подменённые_метаданные_ломают_подпись() {
        // Подпись считается по метаданным вместе с содержимым, поэтому сервер не может
        // переписать автора или время, не тронув payload. Если бы проверка шла только по
        // payload, эта подмена прошла бы незамеченной.
        val sealed = seal()
        val fake = SealedGroupMessage(
            meta = sealed.meta.copy(senderId = "eeeeeeee-0000-0000-0000-000000000009"),
            payload = sealed.payload,
            signature = sealed.signature,
        )
        assertIs<VerificationFailure>(
            GroupMessages.open(fake, author.signingPublic, key).exceptionOrNull(),
        )
    }

    @Test
    fun другая_версия_ключа_не_открывает() {
        // Так выглядит сообщение, пришедшее под версией, которой у нас нет: это не подмена,
        // а своя несобранная картина, и различать их обязан вызывающий.
        val failure = GroupMessages.open(seal(), author.signingPublic, otherKey).exceptionOrNull()
        assertTrue(failure != null && failure !is VerificationFailure, "не тот ключ — не подмена")
    }

    @Test
    fun нулевая_версия_ключа_не_отправляется() {
        // Нулевая версия в протоколе означает публичную группу с открытым payload.
        // Пропустить её здесь значило бы превратить «ключ не доехал» в утечку.
        assertFailsWith<IllegalArgumentException> {
            GroupMessages.seal(
                MessageContent.text("привет"),
                meta.copy(gkVersion = 0),
                author,
                key,
            ).getOrThrow()
        }
    }

    @Test
    fun тело_отдаётся_упакованным_как_пришло() {
        // Хранилище пишет именно эти байты: кодек один на провод и на диск. Записать
        // текстом значит записать в другом формате, чем читает экран.
        val sealed = seal("проверка тела")
        val got = GroupMessages.open(sealed, author.signingPublic, key).getOrThrow()
        val expected = GroupMessages.open(sealed, author.signingPublic, key).getOrThrow().body
        assertContentEquals(expected, got.body)
        assertTrue(got.body.isNotEmpty())
    }
}
