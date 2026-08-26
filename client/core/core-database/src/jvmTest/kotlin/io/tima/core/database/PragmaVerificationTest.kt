package io.tima.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Проверка самой проверки: [TimaDatabaseFactory.verifyPragmas] обязана падать, когда
 * настройка не действует.
 *
 * Без этого теста верификация — украшение: она молчала бы и на правильной базе, и на
 * неправильной, а узнали бы мы об этом из чужого файла базы.
 *
 * **Тест только на JVM,** и причина не в лени: чтобы проверить отказ, нужно уметь
 * *выключить* настройку, а способ выключения платформенный. На JVM достаточно не
 * указывать её в строке подключения — и это ближе всего к настоящей ошибке
 * настройки, которую тест и должен ловить. Сама [TimaDatabaseFactory.verifyPragmas]
 * лежит в общем коде, и на iOS её работу подтверждает положительный случай:
 * правильно настроенный драйвер проходит.
 */
class PragmaVerificationTest {

    @Test
    fun ненастроенный_драйвер_валит_открытие_базы() {
        // Строка подключения без параметров — то есть платформа настройки не задала.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val error = runCatching { TimaDatabaseFactory.verifyPragmas(driver) }.exceptionOrNull()

        assertTrue(error is IllegalStateException, "ожидалась ошибка открытия, получено: $error")
        assertTrue(
            error.message.orEmpty().contains("secure_delete"),
            "в сообщении обязано быть имя настройки, иначе искать нечего",
        )
    }

    @Test
    fun настроенный_драйвер_проходит() {
        TimaDatabaseFactory.open(testDriver(), createSchema = false)
    }
}
