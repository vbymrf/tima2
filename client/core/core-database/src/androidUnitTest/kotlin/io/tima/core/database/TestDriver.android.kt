package io.tima.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * Драйвер для **хостовых** проверок Android — то есть тех, что идут на JVM машины
 * сборки, без устройства и без эмулятора.
 *
 * Здесь `sqlite-jdbc`, а не `AndroidSqliteDriver`, и это не небрежность: на хосте
 * системного SQLite Android не существует вовсе. Настоящий драйвер платформы
 * проверяется прогоном на устройстве (`androidInstrumentedTest`) — и только им:
 * удержались ли настройки соединения, видно лишь там, где эти соединения настоящие.
 *
 * Поэтому этот набор ничего нового про Android не доказывает и дублирует прогон на
 * JVM. Держится он ради одного: общие тесты обязаны компилироваться и проходить для
 * каждого таргета, иначе однажды выяснится, что для Android они не собирались никогда.
 */
actual fun testDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY + "?secure_delete=true&foreign_keys=true")
        .also { TimaDatabase.Schema.create(it) }
