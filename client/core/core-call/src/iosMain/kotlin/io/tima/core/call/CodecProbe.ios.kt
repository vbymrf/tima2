package io.tima.core.call

/** Приложения под iOS нет. */
actual suspend fun probeCodecs(progress: (String) -> Unit): String? = null
