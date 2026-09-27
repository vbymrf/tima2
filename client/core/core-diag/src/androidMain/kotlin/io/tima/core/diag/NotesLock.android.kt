package io.tima.core.diag

internal actual class NotesLock actual constructor() {
    actual fun <T> hold(block: () -> T): T = synchronized(this) { block() }
}
