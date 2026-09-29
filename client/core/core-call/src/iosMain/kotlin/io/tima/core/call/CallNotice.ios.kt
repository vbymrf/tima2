package io.tima.core.call

/** Звонков здесь нет — и говорить системе нечего. */
actual fun callOngoing(title: String, text: String, hangUpLabel: String, connectedAt: Long) = Unit

actual fun callOngoingOff() = Unit

actual fun callProximity(on: Boolean) = Unit
