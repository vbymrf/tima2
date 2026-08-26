package io.tima.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Открытие базы: **проверка, что обязательные настройки действительно действуют**.
 *
 * ── ПОЧЕМУ ЗДЕСЬ ТОЛЬКО ПРОВЕРКА, БЕЗ УСТАНОВКИ ─────────────────────────────
 *
 * Первая версия ставила настройки сама и считала дело сделанным. Две проверки
 * подряд показали, что так нельзя, и каждая — по своей причине.
 *
 * 1. **На JVM установка не держится.** `sqlite-jdbc` берёт соединение под операцию,
 *    и `PRAGMA`, выполненный отдельно, ложится не на то соединение, где потом идёт
 *    `DELETE`. Байты стёртой переписки остались в файле, хотя код выглядел
 *    правильным ([SecureDeleteTest]).
 *
 * 2. **Платформы расходятся в том, чем вообще является `PRAGMA x = ON`.** На
 *    iOS-симуляторе он возвращает строку, и нативный драйвер отвечает на `execute`
 *    отказом: «Queries can be performed using SQLiteDatabase query or rawQuery
 *    methods only». На JVM он строк не возвращает, и `executeQuery` падает с «Query
 *    does not return results». То есть ни один из двух способов не работает на обеих
 *    платформах.
 *
 * Отсюда разделение обязанностей: **настройки задаёт платформа при создании
 * драйвера** — строкой подключения или конфигурацией, — а общий код только читает их
 * обратно и падает, если обещание не выполнено. Забыл настроить драйвер — база не
 * откроется, и это лучше, чем открыться без свойства, обещанного человеку.
 *
 * Чтение настройки одинаково на всех платформах: `PRAGMA x` без присваивания —
 * запрос и есть запрос.
 */
object TimaDatabaseFactory {

    /**
     * @param driver драйвер платформы, **уже настроенный**.
     * @param createSchema создавать схему (новая база) или нет.
     * @throws IllegalStateException если требуемая настройка не действует.
     */
    fun open(driver: SqlDriver, createSchema: Boolean): TimaDatabase {
        verifyPragmas(driver)
        if (createSchema) TimaDatabase.Schema.create(driver)
        return TimaDatabase(driver)
    }

    /**
     * Настройки, каждая — требование, а не вкус.
     *
     * `secure_delete` — требование ADR-0015: без него `DELETE` только помечает
     * страницу свободной, и стёртая переписка остаётся читаемой в файле базы. То есть
     * «удалено» означало бы «не показывается», а это не удаление.
     *
     * `foreign_keys` — в SQLite по умолчанию **выключены**, и связи между таблицами
     * без них не проверяются вовсе. Вложение, потерявшее сообщение, стало бы
     * невидимым мусором, растущим со временем.
     */
    val REQUIRED: List<String> = listOf("secure_delete", "foreign_keys")

    /** Читает настройки обратно и падает, если хоть одна не действует. */
    fun verifyPragmas(driver: SqlDriver) {
        val disabled = REQUIRED.filterNot { readFlag(driver, it) }
        check(disabled.isEmpty()) {
            "не действуют настройки базы: ${disabled.joinToString()}. " +
                "Их задаёт платформа при создании драйвера — строкой подключения или " +
                "конфигурацией. Отдельным PRAGMA после открытия не выйдет: на JVM он " +
                "ложится на другое соединение, а на Apple execute для него запрещён"
        }
    }

    private fun readFlag(driver: SqlDriver, name: String): Boolean =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA $name;",
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) != 0L else false)
            },
            parameters = 0,
        ).value
}
