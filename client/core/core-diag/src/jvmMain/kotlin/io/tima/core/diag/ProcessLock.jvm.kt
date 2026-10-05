package io.tima.core.diag

actual class ProcessLock actual constructor() {
    actual fun <T> hold(block: () -> T): T = synchronized(this) { block() }
}
