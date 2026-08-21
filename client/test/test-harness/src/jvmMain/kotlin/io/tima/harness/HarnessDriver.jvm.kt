package io.tima.harness

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.tima.core.database.TimaDatabase

/**
 * В памяти: сценарий не должен оставлять следов на диске и зависеть от прошлого
 * прогона. Настройки едут в строке подключения — `sqlite-jdbc` берёт соединение под
 * операцию, и `PRAGMA` после открытия ложится не на то соединение (найдено тестом
 * физического стирания в `core-database`).
 */
actual fun harnessDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY + "?secure_delete=true&foreign_keys=true")
        .also { TimaDatabase.Schema.create(it) }
