package io.tima.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver

/**
 * Нативный драйвер в памяти. Это единственное место, где он вообще проверяется:
 * на машине разработки под Windows симулятора нет, значит прогон идёт только в CI.
 */
actual fun testDriver(): SqlDriver = inMemoryDriver(TimaDatabase.Schema)
