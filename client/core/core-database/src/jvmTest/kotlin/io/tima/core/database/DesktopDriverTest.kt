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

    private val файлы = mutableListOf<File>()

    private fun новыйФайл(имя: String): File =
        File.createTempFile(имя, ".db").also { it.delete(); файлы += it }

    @AfterTest
    fun убрать() {
        файлы.forEach { it.delete() }
    }

    @Test
    fun новая_база_создаётся_и_настройки_действуют() {
        val файл = новыйФайл("tima-desktop-new")

        val db = desktopDatabase(файл)

        // Открытие само проверяет настройки: не действуют — не откроется.
        assertEquals(0, db.messagesQueries.countAll().executeAsOne())
        assertTrue(файл.exists() && файл.length() > 0)
    }

    @Test
    fun второй_запуск_открывает_ту_же_базу_и_переписка_на_месте() {
        val файл = новыйФайл("tima-desktop-reopen")
        val шифр = LocalStoreFieldCipher(ТЕСТОВЫЙ_СЕКРЕТ)

        SqlOutboxStore(desktopDatabase(файл), шифр).putIfAbsent(
            OutboxEntry(dedupKey = "d-1", chatId = "chat-1", body = "привет".encodeToByteArray()),
        )

        // Второй запуск: тот же файл, новое соединение.
        val снова = SqlOutboxStore(desktopDatabase(файл), шифр)

        assertEquals("привет", снова.byDedupKey("d-1")?.body?.decodeToString())
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
        val файл = новыйФайл("tima-desktop-future")
        desktopDatabase(файл)
        поставитьВерсию(файл, TimaDatabase.Schema.version + 5)

        val отказ = assertFailsWith<IllegalStateException> { desktopDatabase(файл) }

        assertTrue(
            отказ.message.orEmpty().contains("новее"),
            "отказ обязан объяснять причину, а не падать где-нибудь ниже: ${отказ.message}",
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
        val файл = новыйФайл("tima-desktop-noversion")
        desktopDatabase(файл)
        поставитьВерсию(файл, 0)

        val отказ = assertFailsWith<IllegalStateException> { desktopDatabase(файл) }

        assertTrue(отказ.message.orEmpty().contains("версия схемы не записана"), отказ.message.orEmpty())
    }

    private fun поставитьВерсию(файл: File, версия: Long) {
        val driver = desktopDatabaseDriver(файл)
        driver.execute(null, "PRAGMA user_version = $версия;", 0)
        driver.close()
    }
}
