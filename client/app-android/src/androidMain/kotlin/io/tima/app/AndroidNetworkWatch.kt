package io.tima.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.network.NetworkState
import io.tima.core.network.NetworkWatch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android: сеть по умолчанию по словам самой системы — У17.
 *
 * `registerDefaultNetworkCallback` будит нас **сам**: таймера здесь нет ни одного, и
 * правило «в простое ничего по таймеру» (ADR-0029 §7) этим не нарушается, а выполняется.
 * Вызовы приходят и в службу переднего плана, и при выключенном экране.
 *
 * ── КАК СИСТЕМА СООБЩАЕТ О ПЕРЕХОДЕ ─────────────────────────────────────────
 *
 * Для сети **по умолчанию** переход Wi-Fi → мобильная приходит одним `onAvailable` с
 * новой сетью, без `onLost` в промежутке: сеть была и осталась. `onLost` приходит, только
 * когда сети по умолчанию не осталось вовсе. Поэтому смену мы узнаём сравнением: пришла
 * `onAvailable` не с той сетью, что была.
 *
 * Первый `onAvailable` приходит сразу при подписке — с текущей сетью. Это не смена, а
 * знакомство, и рвать канал из-за него нельзя: иначе каждый запуск начинался бы с
 * обрыва только что поднятого соединения.
 */
class AndroidNetworkWatch(context: Context) : NetworkWatch {

    private val _state = MutableStateFlow(NetworkState.UNKNOWN)
    override val state: StateFlow<NetworkState> = _state

    // Буфер в одно событие: смена, пришедшая, пока приёмник был занят, не должна
    // потеряться — иначе полуоткрытый сокет доживёт до пинга, ради чего всё и затевалось.
    private val _switched = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val switched: SharedFlow<Unit> = _switched

    @Volatile
    private var current: Network? = null

    init {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            manager?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val previous = current
                        current = network
                        _state.value = NetworkState.AVAILABLE
                        if (previous != null && previous != network) {
                            Journal.note(LogCode.NET_CHANNEL, "сеть сменилась")
                            _switched.tryEmit(Unit)
                        }
                    }

                    override fun onLost(network: Network) {
                        // Потерялась не та сеть, что по умолчанию, — это не наша забота:
                        // система уже переключила нас на другую и скажет об этом сама.
                        if (network != current) return
                        current = null
                        _state.value = NetworkState.LOST
                        Journal.note(LogCode.NET_CHANNEL, "сети нет")
                    }
                },
            )
        }.onFailure {
            // Без подписки остаётся прежнее поведение — паузы по `LinkState`. Хуже, но не
            // поломка, и падать из-за этого незачем.
            Journal.trouble(LogCode.NET_CHANNEL, "не удалось следить за сетью", "почему" to it.message.orEmpty())
        }
    }
}
