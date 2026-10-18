package io.tima.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet6Address
import java.net.InetAddress

actual suspend fun resolvesInside(host: String): Boolean = withContext(Dispatchers.IO) {
    val addresses = runCatching { InetAddress.getAllByName(host.trim('[', ']')) }.getOrNull() ?: return@withContext true
    addresses.isEmpty() || addresses.any { a ->
        a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress ||
            (a is Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC) ||
            // 100.64/10 — адреса операторской сети (CGNAT), 169.254 ловит isLinkLocal.
            (a.address.size == 4 && (a.address[0].toInt() and 0xFF) == 100 && (a.address[1].toInt() and 0xC0) == 64)
    }
}
