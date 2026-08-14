package io.tima.app.store

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** На ПК SQLite приходит драйвером JDBC — в системе его нет. */
actual class LocalDb(private val conn: Connection) {

    actual fun exec(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.execute()
        }
    }

    actual fun insert(sql: String, args: List<Any?>): Long =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeUpdate()
            conn.createStatement().use { s ->
                s.executeQuery("SELECT last_insert_rowid()").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }

    actual fun <T> query(sql: String, args: List<Any?>, map: (Row) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val out = ArrayList<T>()
                val row = JdbcRow(rs)
                while (rs.next()) out.add(map(row))
                out
            }
        }

    actual fun <T> transaction(body: () -> T): T {
        conn.autoCommit = false
        return try {
            val r = body()
            conn.commit()
            r
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    actual fun close() = conn.close()

    private fun bind(st: java.sql.PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val n = i + 1
            when (a) {
                null -> st.setNull(n, java.sql.Types.NULL)
                is ByteArray -> st.setBytes(n, a)
                is Long -> st.setLong(n, a)
                is Int -> st.setInt(n, a)
                is Boolean -> st.setInt(n, if (a) 1 else 0)
                else -> st.setString(n, a.toString())
            }
        }
    }

    // JDBC считает столбцы с единицы, а вызывающий код — с нуля, как на Android.
    // Приводим здесь, чтобы вызывающий код не знал, какая под ним реализация.
    private class JdbcRow(private val rs: ResultSet) : Row {
        override fun long(index: Int): Long = rs.getLong(index + 1)
        override fun string(index: Int): String = rs.getString(index + 1) ?: ""
        override fun bytes(index: Int): ByteArray? = rs.getBytes(index + 1)
    }
}

actual fun openLocalDb(name: String): LocalDb {
    val dir = File(System.getProperty("user.home"), ".tima").apply { mkdirs() }
    val conn = DriverManager.getConnection("jdbc:sqlite:${File(dir, name).absolutePath}")
    conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
    return LocalDb(conn)
}
