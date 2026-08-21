package io.tima.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * В памяти, а не файлом: проверки не должны оставлять следов на диске и не должны
 * зависеть от прошлого прогона.
 *
 * Настройки едут **в строке подключения**, а не PRAGMA после открытия. Причина
 * найдена тестом [SecureDeleteTest]: `sqlite-jdbc` берёт соединение под операцию, и
 * PRAGMA ложится не на то соединение, где потом идут запросы.
 */
actual fun testDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY + "?secure_delete=true&foreign_keys=true")
