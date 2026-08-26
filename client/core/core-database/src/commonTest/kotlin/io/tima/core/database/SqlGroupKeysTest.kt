package io.tima.core.database

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Групповые ключи на настоящем SQL и с настоящим шифром покоя.
 *
 * Главная проверка здесь не «записалось и прочиталось», а две другие: что в файле базы
 * ключа нет открытым и что все версии остаются доступными. Первое — смысл шифрования
 * покоя, второе — условие читаемости истории после ротации.
 */
class SqlGroupKeysTest {

    private val db = testDatabase()
    private val cipher = testCipher()
    private val keys = SqlGroupKeys(db, cipher)

    private val group = "gggggggg-0000-0000-0000-000000000001"
    private fun key(n: Int) = ByteArray(32) { (it + n).toByte() }

    @Test
    fun ключ_записывается_и_читается() {
        keys.put(group, version = 1, key = key(1))
        assertContentEquals(key(1), keys.key(group, 1))
    }

    @Test
    fun в_базе_ключ_лежит_закрытым() {
        // Смысл всего слоя: кто дошёл до файла базы, не дошёл до переписки. Проверка
        // читает столбец напрямую, минуя шифр, — иначе она проверяла бы сама себя.
        keys.put(group, version = 1, key = key(1))
        val inColumn = db.groupKeysQueries.groupKey(group, 1).executeAsOne()
        assertTrue(!inColumn.contentEquals(key(1)), "ключ лежит в базе открытым")
    }

    @Test
    fun хранятся_все_версии_а_не_последняя() {
        // Сообщение недельной давности зашифровано старой версией, и ротация происходит
        // при каждом входе и выходе участника. Потеря старых версий делает историю группы
        // нечитаемой при первом же новом участнике.
        keys.put(group, 1, key(1))
        keys.put(group, 2, key(2))
        keys.put(group, 3, key(3))

        assertContentEquals(key(1), keys.key(group, 1))
        assertContentEquals(key(3), keys.key(group, 3))
        assertEquals(listOf(1, 2, 3), keys.versions(group))
        assertEquals(3, keys.latestVersion(group))
    }

    @Test
    fun у_группы_без_ключей_последней_версии_нет() {
        // Признак «писать в группу нечем» получается из самого запроса, без счёта строк.
        assertNull(keys.latestVersion(group))
        assertNull(keys.key(group, 1))
        assertEquals(emptyList(), keys.versions(group))
    }

    @Test
    fun повторная_запись_той_же_версии_заменяет() {
        // Одна и та же версия может приехать дважды: выдача обёрток не обещает
        // однократности. Вторая запись обязана не падать и не плодить строк.
        keys.put(group, 1, key(1))
        keys.put(group, 1, key(1))
        assertEquals(listOf(1), keys.versions(group))
    }

    @Test
    fun забытая_группа_не_оставляет_ключей() {
        // Ключ переживший удаление группы хуже, чем сообщения: по нему читается вся
        // история, включая ту часть, которой на устройстве уже нет.
        keys.put(group, 1, key(1))
        keys.put(group, 2, key(2))
        keys.forget(group)
        assertEquals(emptyList(), keys.versions(group))
        assertNull(keys.key(group, 1))
    }

    @Test
    fun ключи_разных_групп_не_смешиваются() {
        val other = "gggggggg-0000-0000-0000-000000000002"
        keys.put(group, 1, key(1))
        keys.put(other, 1, key(9))
        assertContentEquals(key(1), keys.key(group, 1))
        assertContentEquals(key(9), keys.key(other, 1))
    }
}
