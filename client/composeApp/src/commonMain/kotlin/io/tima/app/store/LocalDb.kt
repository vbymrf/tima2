package io.tima.app.store

/**
 * Строка выборки. Читается по номеру столбца — так же, как её вернул запрос.
 */
interface Row {
    fun long(index: Int): Long
    fun string(index: Int): String
    fun bytes(index: Int): ByteArray?
}

/**
 * Тонкая прослойка над SQLite.
 *
 * Поверхность намеренно узкая: выполнить, выбрать, транзакция, закрыть. Всё, что
 * знает про сообщения, живёт этажом выше — в [MessageStore]. Так базу можно
 * заменить, не трогая ничего остального.
 *
 * SQLite выбран не из любви к зависимостям, а из требования: мы обязаны физически
 * стирать по сроку (ADR-0015). В дописываемом файле выборочно стереть нельзя —
 * пришлось бы переписывать его целиком, то есть писать свою плохую базу вместо
 * готовой.
 */
expect class LocalDb {
    /** Выполнить запрос без выборки (INSERT/UPDATE/DELETE/DDL). */
    fun exec(sql: String, args: List<Any?> = emptyList())

    /** Выполнить и вернуть вставленный идентификатор строки. */
    fun insert(sql: String, args: List<Any?> = emptyList()): Long

    /** Выборка. [map] вызывается на каждой строке. */
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (Row) -> T): List<T>

    /** Всё внутри — одной транзакцией; исключение откатывает. */
    fun <T> transaction(body: () -> T): T

    fun close()
}

/** Открыть (и при необходимости создать) базу с этим именем в каталоге приложения. */
expect fun openLocalDb(name: String): LocalDb
