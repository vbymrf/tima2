package io.tima.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Драйвер для проверок — своя реализация на каждой платформе.
 *
 * **Зачем expect/actual, если тесты контракта уже идут на хранилище в памяти.**
 * Хранилище в памяти проверяет машину состояний; здесь проверяется **SQL**: что
 * запросы верны, что уникальность работает, что порядок в чате устойчив. Такое
 * можно проверить только настоящим драйвером, а он у каждой платформы свой.
 *
 * На Windows этот набор не запускается: iOS требует симулятора. Поэтому прогон на
 * симуляторе в CI — не «дополнительная уверенность», а единственное место, где
 * проверяется нативный драйвер.
 */
expect fun testDriver(): SqlDriver

/** База с применённой схемой — на пустом драйвере. */
fun testDatabase(): TimaDatabase {
    val driver = testDriver()
    TimaDatabase.Schema.create(driver)
    return TimaDatabase(driver)
}

/** База с применённой схемой и настройками — как в бою, только в памяти. */
fun testDatabaseWithPragmas(): TimaDatabase =
    TimaDatabaseFactory.open(testDriver(), createSchema = true)
