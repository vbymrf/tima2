package io.tima.core.database

import androidx.test.platform.app.InstrumentationRegistry
import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.OutboxState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Драйвер Android — **на устройстве**, потому что проверяется не SQL, а то, удержались
 * ли настройки соединения.
 *
 * Это ровно та проверка, которая днём раньше поймала ложное обещание на JVM: код
 * выполнял `PRAGMA secure_delete = ON`, выглядел правильным, а байты стёртой переписки
 * оставались в файле — настройка ложилась не на то соединение. Здесь связка другая
 * (`SupportSQLiteOpenHelper`, одно соединение), и верить ей на слово нельзя тем же
 * образом.
 */
class AndroidDriverTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "tima-проверка.db"

    @AfterTest
    fun remove() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun настройки_соединения_удержались() {
        // Общий код читает их обратно и падает, если обещание не выполнено. Значит
        // успешное открытие здесь и есть проверка.
        val db = androidDatabase(context, databaseName)

        db.messagesQueries.countAll().executeAsOne()
    }

    @Test
    fun стёртая_переписка_не_находится_в_файле() {
        val db = androidDatabase(context, databaseName)
        val store = SqlOutboxStore(db, testCipher())
        val fingerprint = "секретная-строка-переписки-для-поиска-в-файле"

        store.putIfAbsent(
            OutboxEntry(
                dedupKey = "dedup-1",
                chatId = "chat-1",
                body = fingerprint.encodeToByteArray(),
                state = OutboxState.QUEUED,
                createdAtMs = 1_771_200_000_000,
            ),
        )
        db.messagesQueries.deleteChat("chat-1")

        val file = context.getDatabasePath(databaseName)
        assertTrue(file.isFile, "файл базы обязан существовать: ${file.path}")
        val bytes = file.readBytes()

        assertFalse(
            contains(bytes, fingerprint.encodeToByteArray()),
            "байты стёртой переписки остались в файле: secure_delete не сработал",
        )
    }

    @Test
    fun запись_переживает_переоткрытие_базы() {
        // Файл, а не память: локальная база обязана переживать перезапуск приложения.
        SqlOutboxStore(androidDatabase(context, databaseName), testCipher()).putIfAbsent(
            OutboxEntry(dedupKey = "dedup-1", chatId = "chat-1", body = byteArrayOf(1)),
        )

        val again = SqlOutboxStore(androidDatabase(context, databaseName), testCipher())

        assertEquals("chat-1", again.byDedupKey("dedup-1")?.chatId)
    }

    private fun contains(where: ByteArray, what: ByteArray): Boolean {
        if (what.isEmpty() || what.size > where.size) return false
        outer@ for (i in 0..where.size - what.size) {
            for (j in what.indices) if (where[i + j] != what[j]) continue@outer
            return true
        }
        return false
    }
}
