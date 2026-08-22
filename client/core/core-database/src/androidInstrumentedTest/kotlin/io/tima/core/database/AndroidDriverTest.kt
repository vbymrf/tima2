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
    private val имяБазы = "tima-проверка.db"

    @AfterTest
    fun убрать() {
        context.deleteDatabase(имяБазы)
    }

    @Test
    fun настройки_соединения_удержались() {
        // Общий код читает их обратно и падает, если обещание не выполнено. Значит
        // успешное открытие здесь и есть проверка.
        val db = androidDatabase(context, имяБазы)

        db.messagesQueries.countAll().executeAsOne()
    }

    @Test
    fun стёртая_переписка_не_находится_в_файле() {
        val db = androidDatabase(context, имяБазы)
        val store = SqlOutboxStore(db)
        val отпечаток = "секретная-строка-переписки-для-поиска-в-файле"

        store.putIfAbsent(
            OutboxEntry(
                dedupKey = "dedup-1",
                chatId = "chat-1",
                body = отпечаток.encodeToByteArray(),
                state = OutboxState.QUEUED,
                createdAtMs = 1_771_200_000_000,
            ),
        )
        db.messagesQueries.deleteChat("chat-1")

        val файл = context.getDatabasePath(имяБазы)
        assertTrue(файл.isFile, "файл базы обязан существовать: ${файл.path}")
        val байты = файл.readBytes()

        assertFalse(
            содержит(байты, отпечаток.encodeToByteArray()),
            "байты стёртой переписки остались в файле: secure_delete не сработал",
        )
    }

    @Test
    fun запись_переживает_переоткрытие_базы() {
        // Файл, а не память: локальная база обязана переживать перезапуск приложения.
        SqlOutboxStore(androidDatabase(context, имяБазы)).putIfAbsent(
            OutboxEntry(dedupKey = "dedup-1", chatId = "chat-1", body = byteArrayOf(1)),
        )

        val снова = SqlOutboxStore(androidDatabase(context, имяБазы))

        assertEquals("chat-1", снова.byDedupKey("dedup-1")?.chatId)
    }

    private fun содержит(где: ByteArray, что: ByteArray): Boolean {
        if (что.isEmpty() || что.size > где.size) return false
        outer@ for (i in 0..где.size - что.size) {
            for (j in что.indices) if (где[i + j] != что[j]) continue@outer
            return true
        }
        return false
    }
}
