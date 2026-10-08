package io.tima.core.call

/** iOS: звонков пока нет — и гудков тоже. */
actual object CallTones {
    actual fun ringback(on: Boolean) = Unit
    actual fun busy() = Unit
}
