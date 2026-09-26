package io.tima.app

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.network.NetworkState
import io.tima.core.network.NetworkWatch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.NetworkInterface

/**
 * Сеть ПК — опросом интерфейсов (У17 для ПК).
 *
 * ── ЗАЧЕМ ─────────────────────────────────────────────────────────────────
 *
 * До 2026-09-26 у ПК наблюдателя не было вовсе (`NetworkWatch.NONE`): смену сети канал
 * узнавал только по обрыву, а поднимался — по таймеру. Включение VPN на стенде (интерфейс
 * `happ-tun`, маршрут по умолчанию ушёл в туннель) оборвало канал, и два звонка на ПК не
 * пришли. На Android то же самое ловит `AndroidNetworkWatch` — колбэком системы.
 *
 * ── ПОЧЕМУ ОПРОС ──────────────────────────────────────────────────────────
 *
 * У Windows есть уведомления о смене сети, но только через COM (`INetworkListManager`) —
 * это своя обвязка ради одного события. Опрос `NetworkInterface` раз в две секунды стоит
 * миллисекунды и ловит ровно то, что нужно: поднялся или опустился интерфейс, сменились
 * адреса. VPN-туннель — это новый интерфейс, и его появление — смена сети.
 */
class DesktopNetworkWatch : NetworkWatch {

    private val _state = MutableStateFlow(NetworkState.UNKNOWN)
    override val state: StateFlow<NetworkState> = _state

    private val _switched = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val switched: SharedFlow<Unit> = _switched

    @Volatile
    private var last: String? = null

    init {
        Thread({ watch() }, "tima-network-watch").apply { isDaemon = true }.start()
    }

    private fun watch() {
        while (true) {
            runCatching { look() }
            Thread.sleep(POLL_MS)
        }
    }

    private fun look() {
        val up = NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .map { nic -> nic.name + "=" + nic.inetAddresses.toList().map { it.hostAddress }.sorted().joinToString(",") }
            .filter { !it.endsWith("=") }
            .sorted()
        val now = up.joinToString(" ")
        val previous = last
        last = now
        _state.value = if (up.isEmpty()) NetworkState.LOST else NetworkState.AVAILABLE
        if (previous == null || previous == now) return
        if (up.isEmpty()) {
            Journal.note(LogCode.NET_CHANNEL, "сети нет")
        } else {
            // Имена интерфейсов — в журнал: «сеть сменилась» без них не отличить VPN от
            // переключения Wi-Fi. Адресов не пишем: имени достаточно.
            Journal.note(LogCode.NET_CHANNEL, "сеть сменилась", "интерфейсы" to up.joinToString(" ") { it.substringBefore('=') })
            _switched.tryEmit(Unit)
        }
    }

    private companion object {
        const val POLL_MS = 2_000L
    }
}
