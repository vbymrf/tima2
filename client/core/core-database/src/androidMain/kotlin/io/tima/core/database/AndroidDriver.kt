package io.tima.core.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Драйвер базы для Android.
 *
 * **Настройки задаются здесь, а не в общем коде** — то же разделение, что на JVM и
 * Apple, и по той же причине: общего способа задать настройку базы не существует.
 * Общий код только **читает их обратно** ([TimaDatabaseFactory.open]) и падает, если
 * обещание не выполнено. Проверка не формальность: на JVM первая версия честно
 * выполняла `PRAGMA secure_delete = ON`, и настройка не удерживалась — байты стёртой
 * переписки оставались в файле.
 *
 * Где какая настройка и почему:
 *
 * - `foreign_keys` — в `onConfigure`: SQLite требует включать их **до** открытия
 *   транзакций, и Android даёт для этого отдельный вызов;
 * - `secure_delete` — в `onOpen`: это настройка соединения, и ставится она на том же
 *   соединении, на котором потом идут запросы. Без неё `DELETE` оставляет страницы
 *   читаемыми, то есть «удалено» означает только «не показывается» (ADR-0015).
 */
fun androidDatabaseDriver(
    context: Context,
    /** Имя файла. `null` — база в памяти: так гоняются проверки. */
    name: String? = "tima.db",
): SqlDriver {
    val callback = object : AndroidSqliteDriver.Callback(TimaDatabase.Schema) {

        override fun onConfigure(db: SupportSQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            // query, а НЕ execSQL. Android отказывает execSQL на любом выражении,
            // возвращающем строку («Queries can be performed using SQLiteDatabase
            // query or rawQuery methods only»), а `PRAGMA secure_delete = ON` строку
            // возвращает — новое значение. Найдено прогоном на устройстве: собиралось
            // и выглядело правильным, а падало на открытии базы.
            //
            // Это третий поворот одного правила: общего способа задать настройку базы
            // не существует. На JVM PRAGMA строк не возвращает и запрос падает; на
            // Apple и на Android — наоборот.
            db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
        }
    }
    return AndroidSqliteDriver(
        schema = TimaDatabase.Schema,
        context = context,
        name = name,
        callback = callback,
    )
}

/**
 * База, готовая к работе: драйвер платформы плюс проверка настроек.
 *
 * Схему создаёт драйвер (`AndroidSqliteDriver` делает это сам по `Schema`), поэтому
 * `createSchema = false` — повторное создание уронило бы открытие сообщением
 * «table messages already exists». Ту же ошибку уже ловил прогон на iOS.
 */
fun androidDatabase(context: Context, name: String? = "tima.db"): TimaDatabase =
    TimaDatabaseFactory.open(androidDatabaseDriver(context, name), createSchema = false)
