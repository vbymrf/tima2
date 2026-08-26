package io.tima.core.database

import io.tima.core.encryption.LocalStoreFieldCipher
import io.tima.core.outbox.OutboxEntry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * База на диске для ПК: создаётся, открывается второй раз, и версию схемы уважает.
 *
 * **Второй запуск — главная проверка.** До этого файла на JVM не было боевого драйвера
 * вовсе, и путь «создать схему» вызывался безусловно: первый запуск прошёл бы, а второй
 * упал бы на «table messages already exists». Такое находят не тестом, а первым живым
 * запуском у человека, у которого уже есть переписка.
 */
class DesktopDriverTest {

    private val files = mutableListOf<File>()

    private fun newFile(name: String): File =
        File.createTempFile(name, ".db").also { it.delete(); files += it }

    @AfterTest
    fun remove() {
        files.forEach { it.delete() }
    }

    @Test
    fun новая_база_создаётся_и_настройки_действуют() {
        val file = newFile("tima-desktop-new")

        val db = desktopDatabase(file)

        // Открытие само проверяет настройки: не действуют — не откроется.
        assertEquals(0, db.messagesQueries.countAll().executeAsOne())
        assertTrue(file.exists() && file.length() > 0)
    }

    @Test
    fun второй_запуск_открывает_ту_же_базу_и_переписка_на_месте() {
        val file = newFile("tima-desktop-reopen")
        val cipher = LocalStoreFieldCipher(TEST_SECRET)

        SqlOutboxStore(desktopDatabase(file), cipher).putIfAbsent(
            OutboxEntry(dedupKey = "d-1", chatId = "chat-1", body = "привет".encodeToByteArray()),
        )

        // Второй запуск: тот же файл, новое соединение.
        val again = SqlOutboxStore(desktopDatabase(file), cipher)

        assertEquals("привет", again.byDedupKey("d-1")?.body?.decodeToString())
    }

    /**
     * База от более новой версии приложения не открывается.
     *
     * Молча писать в неизвестную схему — это потеря переписки, а не неудобство: старая
     * версия не знает про столбцы, которых не понимает, и «починит» их своим представлением
     * о схеме.
     */
    @Test
    fun база_от_более_новой_версии_отвергается() {
        val file = newFile("tima-desktop-future")
        desktopDatabase(file)
        versionPut(file, TimaDatabase.Schema.version + 5)

        val refusal = assertFailsWith<IllegalStateException> { desktopDatabase(file) }

        assertTrue(
            refusal.message.orEmpty().contains("новее"),
            "отказ обязан объяснять причину, а не падать где-нибудь ниже: ${refusal.message}",
        )
    }

    /**
     * База с таблицами, но без записанной версии, тоже отвергается.
     *
     * Так выглядит чужая база или база, созданная мимо этого пути. Создавать схему поверх
     * неё нельзя: неизвестно, что в ней лежит.
     */
    @Test
    fun база_с_таблицами_но_без_версии_отвергается() {
        val file = newFile("tima-desktop-noversion")
        desktopDatabase(file)
        versionPut(file, 0)

        val refusal = assertFailsWith<IllegalStateException> { desktopDatabase(file) }

        assertTrue(refusal.message.orEmpty().contains("версия схемы не записана"), refusal.message.orEmpty())
    }

    private fun versionPut(file: File, version: Long) {
        val driver = desktopDatabaseDriver(file)
        driver.execute(null, "PRAGMA user_version = $version;", 0)
        driver.close()
    }
}
