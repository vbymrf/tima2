package io.tima.core.network

/** На iOS разрешения имён здесь нет: карточку проверяет адрес в ссылке (`LinkSafety`). */
actual suspend fun resolvesInside(host: String): Boolean = false
