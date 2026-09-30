package io.tima.core.call

/** На ПК кодеры только программные — пробовать нечего. */
actual suspend fun probeCodecs(progress: (String) -> Unit): String? = null
