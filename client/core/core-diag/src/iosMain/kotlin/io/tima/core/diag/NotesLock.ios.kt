package io.tima.core.diag

import platform.Foundation.NSRecursiveLock

internal actual class NotesLock actual constructor() {
    private val lock = NSRecursiveLock()

    actual fun <T> hold(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
