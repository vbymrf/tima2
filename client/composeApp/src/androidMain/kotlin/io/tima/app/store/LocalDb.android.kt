package io.tima.app.store

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.tima.app.platform.AndroidAppContext

/** На Android SQLite встроен в систему — отдельная зависимость не нужна. */
actual class LocalDb(private val db: SQLiteDatabase) {

    actual fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())
    }

    actual fun insert(sql: String, args: List<Any?>): Long =
        db.compileStatement(sql).use { st ->
            bind(st, args)
            st.executeInsert()
        }

    actual fun <T> query(sql: String, args: List<Any?>, map: (Row) -> T): List<T> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            val out = ArrayList<T>(c.count)
            val row = CursorRow(c)
            while (c.moveToNext()) out.add(map(row))
            out
        }

    actual fun <T> transaction(body: () -> T): T {
        db.beginTransaction()
        return try {
            val r = body()
            db.setTransactionSuccessful()
            r
        } finally {
            db.endTransaction()
        }
    }

    actual fun close() = db.close()

    private fun bind(st: android.database.sqlite.SQLiteStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val n = i + 1
            when (a) {
                null -> st.bindNull(n)
                is ByteArray -> st.bindBlob(n, a)
                is Long -> st.bindLong(n, a)
                is Int -> st.bindLong(n, a.toLong())
                is Boolean -> st.bindLong(n, if (a) 1 else 0)
                else -> st.bindString(n, a.toString())
            }
        }
    }

    private class CursorRow(private val c: Cursor) : Row {
        override fun long(index: Int): Long = if (c.isNull(index)) 0 else c.getLong(index)
        override fun string(index: Int): String = c.getString(index) ?: ""
        override fun bytes(index: Int): ByteArray? = if (c.isNull(index)) null else c.getBlob(index)
    }
}

actual fun openLocalDb(name: String): LocalDb {
    val file = AndroidAppContext.app.getDatabasePath(name)
    file.parentFile?.mkdirs()
    return LocalDb(SQLiteDatabase.openOrCreateDatabase(file, null))
}
