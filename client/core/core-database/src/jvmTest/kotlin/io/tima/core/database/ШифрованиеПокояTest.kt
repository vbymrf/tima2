package io.tima.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.tima.core.encryption.LocalStoreFieldCipher
import io.tima.core.outbox.OutboxEntry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **Открытого текста переписки нет в файле базы** — Plan.md §3.4.2, §3.4.3 (вариант A).
 *
 * Проверять надо файл, а не таблицу: через таблицу видно то, что вернул код, а вопрос
 * стоит про диск. Та же причина, что у [SecureDeleteTest], и та же история: `secure_delete`
 * был обещан документами и **не работал**, пока это не проверили по файлу. Столбец
 * `body_enc` носил имя «зашифровано» с самого начала и лежал открытым — ровно так же.
 *
 * Второй тест — контрольный опыт. Без него первый зелёный по любой причине, например
 * потому, что маркер вообще не дошёл до файла.
 *
 * **Что при этом утекает, если файл прочитали:** кто с кем, когда, сколько сообщений, их
 * состояния и размеры. Содержимое — нет. Это честная граница варианта A.
 */
class ШифрованиеПокояTest {

    private val маркер = "СЕКРЕТНОЕ-СОДЕРЖИМОЕ-ПЕРЕПИСКИ"

    @Test
    fun тела_сообщений_не_находятся_в_файле_базы() {
        val файл = сФайлом("tima-at-rest") { db ->
            SqlOutboxStore(db, LocalStoreFieldCipher(ТЕСТОВЫЙ_СЕКРЕТ)).putIfAbsent(
                OutboxEntry(dedupKey = "d-1", chatId = "chat-1", body = маркер.encodeToByteArray()),
            )
        }
        assertFalse(
            файл.readBytes().содержит(маркер.encodeToByteArray()),
            "тело сообщения лежит в файле базы открытым — шифрование покоя не работает",
        )
    }

    @Test
    fun без_шифра_тело_в_файле_есть_и_это_доказывает_что_проверка_работает() {
        val файл = сФайлом("tima-at-rest-plain") { db ->
            // Мимо хранилища, прямым запросом: так тело попадает в столбец открытым —
            // именно то состояние, в котором база была до этой правки.
            db.messagesQueries.insertQueued(
                dedup_key = "d-1", chat_id = "chat-1", sender_id = "",
                client_ts = 1, state = 0, attempts = 0,
                next_attempt_at = null, reply_to = null,
                body_enc = маркер.encodeToByteArray(),
            )
        }
        assertTrue(
            файл.readBytes().содержит(маркер.encodeToByteArray()),
            "без шифра тело обязано находиться в файле — иначе проверка выше ничего не значит",
        )
    }

    /** Тело, записанное шифром, читается обратно тем же шифром. */
    @Test
    fun записанное_читается_обратно() {
        сФайлом("tima-at-rest-roundtrip") { db ->
            val store = SqlOutboxStore(db, LocalStoreFieldCipher(ТЕСТОВЫЙ_СЕКРЕТ))
            store.putIfAbsent(
                OutboxEntry(dedupKey = "d-1", chatId = "chat-1", body = маркер.encodeToByteArray()),
            )
            assertTrue(
                store.byDedupKey("d-1")!!.body.contentEquals(маркер.encodeToByteArray()),
                "закрытое поле обязано открываться обратно",
            )
        }
    }

    /**
     * Создаёт файловую базу, отдаёт её в [работа], закрывает соединение и возвращает файл.
     *
     * `journal_mode = DELETE` и `VACUUM`: с WAL записанные страницы остаются в отдельном
     * файле, и проверка смотрела бы не туда.
     */
    private fun сФайлом(имя: String, работа: (TimaDatabase) -> Unit): File {
        val файл = File.createTempFile(имя, ".db").also { it.delete() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${файл.absolutePath}")
        try {
            driver.execute(null, "PRAGMA journal_mode = DELETE;", 0)
            TimaDatabase.Schema.create(driver)
            работа(TimaDatabase(driver))
            driver.execute(null, "VACUUM;", 0)
        } finally {
            driver.close()
        }
        файлыНаУборку += файл
        return файл
    }

    private val файлыНаУборку = mutableListOf<File>()

    @AfterTest
    fun убрать() {
        файлыНаУборку.forEach { it.delete() }
    }

    private fun ByteArray.содержит(что: ByteArray): Boolean {
        if (что.isEmpty() || что.size > size) return false
        outer@ for (i in 0..size - что.size) {
            for (j in что.indices) if (this[i + j] != что[j]) continue@outer
            return true
        }
        return false
    }
}
