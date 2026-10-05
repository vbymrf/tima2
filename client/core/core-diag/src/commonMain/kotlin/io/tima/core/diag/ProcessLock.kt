package io.tima.core.diag

/**
 * Замок для общего кода: `synchronized` и `@Synchronized` есть только у JVM, и общий код,
 * написанный с ними, собирается под Android и ПК, а под iOS — нет.
 *
 * Тот же приём, что у [NotesLock]: на JVM — монитор объекта, на iOS — `NSRecursiveLock`.
 * Повторный вход разрешён на обеих платформах.
 */
expect class ProcessLock() {
    fun <T> hold(block: () -> T): T
}
