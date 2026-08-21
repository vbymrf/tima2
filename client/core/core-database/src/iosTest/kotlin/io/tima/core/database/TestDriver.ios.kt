package io.tima.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver

/**
 * Нативный драйвер в памяти. Это единственное место, где он вообще проверяется: на
 * машине разработки под Windows симулятора нет, значит прогон идёт только в CI.
 *
 * **Схему создаёт сам `inMemoryDriver`** — общий код её больше не создаёт, иначе все
 * тесты падают с «table messages already exists».
 *
 * **Настройки задаются здесь, и через `executeQuery`.** На Apple `PRAGMA x = ON`
 * возвращает строку, и нативный драйвер отвечает на `execute` отказом: «Queries can
 * be performed using SQLiteDatabase query or rawQuery methods only». На JVM
 * наоборот — там строк нет и работает `execute`. Общего способа нет, поэтому
 * установка живёт в платформенном коде, а общий код только проверяет результат.
 */
actual fun testDriver(): SqlDriver = inMemoryDriver(TimaDatabase.Schema).also { driver ->
    for (name in TimaDatabaseFactory.REQUIRED) {
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA $name = ON;",
            mapper = { cursor ->
                while (cursor.next().value) Unit
                QueryResult.Unit
            },
            parameters = 0,
        )
    }
}
