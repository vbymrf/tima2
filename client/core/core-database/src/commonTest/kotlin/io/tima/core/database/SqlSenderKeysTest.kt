package io.tima.core.database

import io.tima.core.outbox.Inbox
import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OpenOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Подпись автора на устройстве (план КК, КК13; заказчик 2026-10-29 «Показывать с пометкой, ключи подписи
 * хранить»): ключи подписи переживают перезапуск и закрепляются, непроверенное ждёт ключа в базе.
 */
class SqlSenderKeysTest {

    private val db = testDatabase()
    private val keys = SqlSenderKeys(db)
    private val pub = ByteArray(32) { 1 }

    @Test
    fun ключ_закрепляется_при_первой_встрече() {
        assertTrue(keys.put(SenderKey("d-1", "u-1", pub)))
        assertFalse(keys.put(SenderKey("d-1", "u-1", ByteArray(32) { 2 })), "другой ключ у того же устройства принят")
        assertTrue(keys.get("d-1")!!.signingPub.contentEquals(pub))
    }

    @Test
    fun отзыв_отмечается_у_записанного_ключа() {
        keys.put(SenderKey("d-1", "u-1", pub))
        keys.put(SenderKey("d-1", "u-1", pub, revokedAtMs = 5, revokedReason = "reregistered"))
        val got = keys.get("d-1")!!
        assertEquals(5, got.revokedAtMs)
        assertTrue(got.confirms, "перерегистрация — не кража: подпись подтверждает автора")
    }

    /** Р11, развилка 7а: отзыв по краже — подпись автора не подтверждает. */
    @Test
    fun отзыв_по_краже_не_подтверждает_автора() {
        assertFalse(SenderKey("d-1", "u-1", pub, revokedAtMs = 5, revokedReason = "disputed").confirms)
        assertTrue(SenderKey("d-1", "u-1", pub).confirms)
        assertTrue(SenderKey("d-1", "u-1", pub, revokedAtMs = 5, revokedReason = "").confirms)
    }

    /** План КК, КК9: ключи сообщений на устройстве — под шифром покоя, не перезаписываются. */
    @Test
    fun ключ_сообщения_хранится_под_шифром_и_не_перезаписывается() {
        val keysOfMessages = SqlMessageKeys(db, testCipher())
        val key = ByteArray(32) { 7 }
        keysOfMessages.put("c-1", 3, key)
        keysOfMessages.put("c-1", 3, ByteArray(32) { 8 })
        assertTrue(key.contentEquals(keysOfMessages.get("c-1", 3)!!))
        assertEquals(setOf(3L), keysOfMessages.of("c-1").keys)
        assertEquals(listOf("c-1"), keysOfMessages.chats())
        val raw = db.messageKeysQueries.messageKey("c-1", 3).executeAsOne()
        assertFalse(raw.contentEquals(key), "ключ лежит открытым")
    }

    @Test
    fun непроверенное_хранится_в_базе_и_подтверждается_или_скрывается() {
        val cipher = testCipher()
        val store = SqlInboxStore(db, cipher)
        val inbox = Inbox(store, nowMs = { 1 })
        val envelope = byteArrayOf(5, 6, 7)
        inbox.receive("c-1", 1, envelope)
        inbox.receive("c-1", 2, envelope)
        inbox.openNext(open = { OpenOutcome.Opened(byteArrayOf(1), "u-1", unverified = true) })
        inbox.openNext(open = { OpenOutcome.Opened(byteArrayOf(1), "u-1", unverified = true) })
        assertEquals(setOf(1L, 2L), inbox.unverifiedEnvelopes().map { it.messageId }.toSet())
        assertEquals(1L, db.messagesQueries.byDedupKey("c-1/1").executeAsOne().unverified)

        inbox.verified("c-1", 1)
        assertEquals(0L, db.messagesQueries.byDedupKey("c-1/1").executeAsOne().unverified)
        inbox.forged("c-1", 2, envelope, "подпись не сходится")
        assertEquals(IncomingState.UNDECRYPTABLE, store.byKey("c-1", 2)!!.state)
        assertTrue(inbox.unverifiedEnvelopes().isEmpty())
        assertNull(store.byKey("c-1", 2)!!.firstReason)
    }
}
