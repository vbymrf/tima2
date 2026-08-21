package io.tima.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Открытие базы: схема, настройки и **проверка, что настройки действительно
 * применились**.
 *
 * ── ПОЧЕМУ ЗДЕСЬ ПРОВЕРКА, А НЕ ТОЛЬКО УСТАНОВКА ────────────────────────────
 *
 * Первая версия этого объекта просто выполняла `PRAGMA secure_delete = ON` через
 * `driver.execute` и считала дело сделанным. Тест [SecureDeleteTest] показал, что
 * **настройка не удержалась**: `sqlite-jdbc` берёт соединение под операцию, и
 * `PRAGMA` лёг не на то соединение, где потом шёл `DELETE`. Байты стёртой переписки
 * остались в файле, хотя код выглядел правильным.
 *
 * Это ровно тот класс дефекта, который живёт годами: обещание дано, механизм
 * «включён», проверить некому. Поэтому теперь настройки **сначала ставятся, потом
 * читаются обратно**, и расхождение — ошибка при открытии базы, а не тихая потеря
 * свойства.
 *
 * Из этого следует требование к платформенному коду: `secure_delete` и
 * `foreign_keys` надо задавать **при создании драйвера** — в строке подключения или
 * конфигурации, — а не надеяться на `PRAGMA` после. Забыл — база не откроется, и это
 * лучше, чем открыться без свойства, которое обещано человеку.
 */
object TimaDatabaseFactory {

    /**
     * @param driver уже открытый драйвер платформы.
     * @param createSchema создавать схему (новая база) или нет.
     * @throws IllegalStateException если требуемая настройка не применилась.
     */
    fun open(driver: SqlDriver, createSchema: Boolean): TimaDatabase {
        applyPragmas(driver)
        verifyPragmas(driver)
        if (createSchema) TimaDatabase.Schema.create(driver)
        return TimaDatabase(driver)
    }

    /**
     * Настройки, каждая — требование, а не вкус.
     *
     * `secure_delete` — требование [ADR-0015]: без него `DELETE` только помечает
     * страницу свободной, и стёртая переписка остаётся читаемой в файле базы. То есть
     * «удалено» означало бы «не показывается», а это не удаление.
     *
     * `foreign_keys` — в SQLite по умолчанию **выключены**, и связи между таблицами
     * без них не проверяются вовсе. Вложение, потерявшее сообщение, стало бы
     * невидимым мусором, растущим со временем.
     *
     * Установка здесь — попытка, а не гарантия: держится она или нет, решает
     * [verifyPragmas].
     */
    fun applyPragmas(driver: SqlDriver) {
        driver.execute(null, "PRAGMA secure_delete = ON;", 0)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
    }

    /** Читает настройки обратно и падает, если хоть одна не действует. */
    fun verifyPragmas(driver: SqlDriver) {
        val выключенные = REQUIRED.filterNot { readFlag(driver, it) }
        check(выключенные.isEmpty()) {
            "не применились настройки базы: ${выключенные.joinToString()}. " +
                "Их надо задавать при создании драйвера — в строке подключения или " +
                "конфигурации, — а не PRAGMA после открытия: на пуле соединений PRAGMA " +
                "ложится не на то соединение, где идут запросы"
        }
    }

    private val REQUIRED = listOf("secure_delete", "foreign_keys")

    private fun readFlag(driver: SqlDriver, name: String): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA $name;",
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getLong(0) != 0L else false,
                )
            },
            parameters = 0,
        ).value
}
