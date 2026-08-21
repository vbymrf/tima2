package io.tima.harness

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.inMemoryDriver
import io.tima.core.database.TimaDatabase

/** Схему создаёт сам драйвер — повторный `Schema.create` уронил бы весь набор. */
actual fun harnessDriver(): SqlDriver = inMemoryDriver(TimaDatabase.Schema)
