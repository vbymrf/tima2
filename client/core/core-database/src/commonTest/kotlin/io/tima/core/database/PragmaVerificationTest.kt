package io.tima.core.database

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Проверка самой проверки: [TimaDatabaseFactory.verifyPragmas] обязана падать, когда
 * настройка не действует.
 *
 * Без этого теста верификация — украшение: она молчала бы и на правильной базе, и на
 * неправильной, а узнали бы мы об этом из чужого файла базы.
 */
class PragmaVerificationTest {

    @Test
    fun правильно_настроенный_драйвер_проходит() {
        // testDriver задаёт настройки в строке подключения — как обязан делать
        // платформенный код в бою.
        TimaDatabaseFactory.open(testDriver(), createSchema = false)
    }

    @Test
    fun выключенная_настройка_валит_открытие_базы() {
        val driver = testDriver()
        // Гасим уже после установки — имитируем платформу, которая настройку не
        // удержала.
        driver.execute(null, "PRAGMA foreign_keys = OFF;", 0)
        val ошибка = runCatching { TimaDatabaseFactory.verifyPragmas(driver) }.exceptionOrNull()
        assertTrue(ошибка is IllegalStateException, "ожидалась ошибка открытия, получено: $ошибка")
        assertTrue(
            ошибка.message.orEmpty().contains("foreign_keys"),
            "в сообщении обязано быть имя настройки, иначе искать нечего",
        )
    }
}
