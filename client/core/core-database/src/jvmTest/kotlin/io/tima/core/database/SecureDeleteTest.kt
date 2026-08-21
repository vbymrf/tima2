package io.tima.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * К3.5: **стёртая строка не находится в файле базы.**
 *
 * Требование ADR-0015. Без `PRAGMA secure_delete` SQLite при `DELETE` только помечает
 * страницу свободной, а байты в файле остаются — то есть «удалено» означает «не
 * показывается». Для мессенджера это не мелочь: человек нажал «удалить переписку»,
 * ему сказали «удалено», а содержимое лежит на диске до следующей перезаписи
 * страницы, которая может не случиться никогда.
 *
 * **Тест только на JVM, и это не лень.** Проверять надо **файл**, а не таблицу: в
 * памяти проверять нечего. Файловая база на iOS-симуляторе потребовала бы пути в
 * песочнице приложения, то есть проверяла бы уже не SQLite, а устройство сандбокса.
 * Сам `PRAGMA` при этом применяется одинаково на всех платформах —
 * [TimaDatabaseFactory.applyPragmas] лежит в общем коде.
 */
class SecureDeleteTest {

    /** Узнаваемая последовательность: её и ищем в файле. */
    private val маркер = "СЕКРЕТНОЕ-СОДЕРЖИМОЕ-ПЕРЕПИСКИ".encodeToByteArray()

    @Test
    fun стёртая_переписка_не_находится_в_файле_базы() {
        val файл = File.createTempFile("tima-secure-delete", ".db").also { it.delete() }
        try {
            записать(файл, secureDelete = true)
            assertFalse(
                файл.readBytes().содержит(маркер),
                "после DELETE с secure_delete байты обязаны быть затёрты, а они на месте",
            )
        } finally {
            файл.delete()
        }
    }

    @Test
    fun без_настройки_байты_остаются_и_это_доказывает_что_проверка_работает() {
        // Контрольный опыт. Без него первый тест зелёный по любой причине — например
        // потому, что маркер вообще не попал в файл, — и ничего не доказывает.
        val файл = File.createTempFile("tima-plain-delete", ".db").also { it.delete() }
        try {
            записать(файл, secureDelete = false)
            assertTrue(
                файл.readBytes().содержит(маркер),
                "без secure_delete байты остаются — иначе проверка выше ничего не значит",
            )
        } finally {
            файл.delete()
        }
    }

    /** Создаёт базу, кладёт сообщение с маркером, удаляет чат и закрывает соединение. */
    private fun записать(файл: File, secureDelete: Boolean) {
        // Настройка едет в СТРОКЕ ПОДКЛЮЧЕНИЯ, а не отдельным PRAGMA. Причина
        // найдена этим же тестом: PRAGMA через driver.execute не удержался — sqlite-jdbc
        // берёт соединение под операцию, и настройка легла не туда, где потом шёл DELETE.
        val driver = JdbcSqliteDriver(
            "jdbc:sqlite:${файл.absolutePath}?secure_delete=${if (secureDelete) "true" else "false"}",
        )
        // journal_mode = DELETE, а не WAL: с WAL удалённые страницы остаются в
        // отдельном файле, и проверка смотрела бы не туда. В бою режим свой, здесь
        // важно проверить именно затирание страницы.
        driver.execute(null, "PRAGMA journal_mode = DELETE;", 0)
        TimaDatabase.Schema.create(driver)
        val db = TimaDatabase(driver)

        db.messagesQueries.insertQueued(
            dedup_key = "d-1", chat_id = "chat-1", sender_id = "me",
            client_ts = 1, state = 0, attempts = 0,
            next_attempt_at = null, reply_to = null, body_enc = маркер,
        )
        // Убедиться, что маркер вообще дошёл до файла: иначе оба теста бессмысленны.
        driver.execute(null, "VACUUM;", 0)
        db.messagesQueries.deleteChat("chat-1")
        driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE);", 0)
        driver.close()
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
