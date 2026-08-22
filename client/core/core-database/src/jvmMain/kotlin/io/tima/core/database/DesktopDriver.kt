package io.tima.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

/**
 * База на диске для ПК.
 *
 * До этого файла у JVM не было **боевого** драйвера вовсе: в модуле лежали только
 * тестовые, в памяти. То есть приложение для ПК не могло открыть базу — и заметить это
 * можно было ровно тогда, когда его впервые запускают с настоящим хранилищем.
 *
 * ── НАСТРОЙКИ ЕДУТ В СТРОКЕ ПОДКЛЮЧЕНИЯ ─────────────────────────────────────
 *
 * Не отдельным `PRAGMA` после открытия, и это найденный случай, а не стиль: `sqlite-jdbc`
 * берёт соединение под операцию, и `PRAGMA`, выполненный отдельно, ложится не на то
 * соединение, где потом идёт `DELETE`. Байты стёртой переписки остались в файле, хотя код
 * выглядел правильным ([SecureDeleteTest]). Проверяет это [TimaDatabaseFactory], читая
 * настройки обратно.
 *
 * ── ВЕРСИЯ СХЕМЫ ────────────────────────────────────────────────────────────
 *
 * `PRAGMA user_version` — то же место, где версию держит драйвер Android, поэтому число
 * означает одно и то же на обеих платформах. Пустая база создаётся, старая переносится,
 * а база **от более новой версии приложения** отвергается: открывать её значит писать в
 * неизвестную схему, а это потеря переписки, а не неудобство.
 */
fun desktopDatabaseDriver(файл: File): SqlDriver {
    файл.parentFile?.mkdirs()
    return JdbcSqliteDriver(
        "jdbc:sqlite:${файл.absolutePath}?secure_delete=true&foreign_keys=true",
    )
}

/** Открыть или создать базу на диске: схема приводится к нужной версии. */
fun desktopDatabase(файл: File): TimaDatabase {
    val driver = desktopDatabaseDriver(файл)
    привестиСхему(driver)
    return TimaDatabaseFactory.open(driver, createSchema = false)
}

/**
 * Создать схему или перенести её на нужную версию.
 *
 * Отдельная функция, а не часть [TimaDatabaseFactory]: запись `PRAGMA user_version`
 * платформозависима — на Apple `execute` для присваивающего PRAGMA запрещён, — и общий
 * код такого не выдержит. Читается версия одинаково везде, пишется по-своему; ровно то же
 * разделение, что у остальных настроек базы.
 */
internal fun привестиСхему(driver: SqlDriver) {
    val нужная = TimaDatabase.Schema.version
    val текущая = версияБазы(driver)
    when {
        текущая == 0L -> {
            // Ноль означает «версию никто не ставил». У пустого файла так и есть; у файла
            // с таблицами это чужая база или база, созданную мимо этого пути, — и
            // создавать схему поверх неё нельзя.
            check(таблицПусто(driver)) {
                "в базе уже есть таблицы, но версия схемы не записана: " +
                    "открыть такую нельзя, не зная, что в ней лежит"
            }
            TimaDatabase.Schema.create(driver)
            записатьВерсию(driver, нужная)
        }

        текущая < нужная -> {
            TimaDatabase.Schema.migrate(driver, текущая, нужная)
            записатьВерсию(driver, нужная)
        }

        текущая > нужная -> error(
            "база записана версией приложения новее этой: схема $текущая, а мы умеем $нужная. " +
                "Открывать её нельзя: запись в неизвестную схему теряет переписку",
        )
    }
}

private fun версияБазы(driver: SqlDriver): Long = driver.executeQuery(
    identifier = null,
    sql = "PRAGMA user_version;",
    mapper = { cursor ->
        QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
    },
    parameters = 0,
).value

private fun записатьВерсию(driver: SqlDriver, версия: Long) {
    // Присваивающий PRAGMA строк не возвращает — на JVM это execute. На Apple тот же
    // вызов запрещён, и потому эта функция здесь, а не в общем коде.
    driver.execute(null, "PRAGMA user_version = $версия;", 0)
}

private fun таблицПусто(driver: SqlDriver): Boolean = driver.executeQuery(
    identifier = null,
    sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%';",
    mapper = { cursor ->
        QueryResult.Value(if (cursor.next().value) (cursor.getLong(0) ?: 0L) == 0L else true)
    },
    parameters = 0,
).value
