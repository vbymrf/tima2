package io.tima.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import io.tima.app.diag.AppDiagnostics
import io.tima.app.platform.AndroidAppContext

/** Android сообщает о появлении сети сам — таймер для этого не нужен. */
actual fun observeNetworkChanges(onChange: () -> Unit) {
    val cm = AndroidAppContext.app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            AppDiagnostics.add("сеть: появилась — проверяю связь")
            onChange()
        }

        override fun onLost(network: Network) {
            AppDiagnostics.add("сеть: пропала")
            onChange()
        }
    }
    runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }
        .onFailure { AppDiagnostics.add("сеть: не подписаться на смену — ${it.message}") }
}
