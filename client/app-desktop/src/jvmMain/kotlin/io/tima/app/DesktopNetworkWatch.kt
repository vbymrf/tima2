package io.tima.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.network.NetworkState
import io.tima.core.network.NetworkWatch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** `GetBestInterface` из iphlpapi: через какой интерфейс Windows пошлёт пакет на адрес. */
@Suppress("FunctionName")
private interface IpHelper : Library {
    fun GetBestInterface(destination: Int, bestIndex: IntByReference): Int
}

/**
 * Сеть ПК — **по маршруту до сервера** (У17 для ПК; заказчик 2026-09-26).
 *
 * ── ЗАЧЕМ ─────────────────────────────────────────────────────────────────
 *
 * До 2026-09-26 у ПК наблюдателя не было вовсе: включение VPN оборвало канал, и два
 * звонка на ПК не пришли. На Android то же ловит `AndroidNetworkWatch` — колбэком системы.
 *
 * ── ПОЧЕМУ МАРШРУТ, А НЕ СПИСОК ИНТЕРФЕЙСОВ ───────────────────────────────
 *
 * Первая редакция сравнивала все интерфейсы и рвала канал на любом — в том числе на
 * виртуальных адаптерах Docker и Hyper-V, через которые к серверу не ходит ничего.
 * Спрашиваем то, что важно каналу: **через какой интерфейс Windows сейчас пошлёт пакет на
 * сервер** и какие у него адреса. Включили VPN любого вида — маршрут ушёл в тоннель, это
 * смена; включился Docker — маршрут прежний, канал не трогаем. Маршрута нет — сети нет.
 */
class DesktopNetworkWatch(private val host: String) : NetworkWatch {

    private val _state = MutableStateFlow(NetworkState.UNKNOWN)
    override val state: StateFlow<NetworkState> = _state

    private val _switched = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val switched: SharedFlow<Unit> = _switched

    private val helper: IpHelper? = runCatching { Native.load("iphlpapi", IpHelper::class.java) }.getOrNull()

    @Volatile
    private var last: String? = null

    /** Последний адрес сервера: DNS может не ответить как раз в момент смены сети. */
    @Volatile
    private var target: Inet4Address? = null

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
        val lib = helper ?: return
        runCatching { InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull() }
            .getOrNull()?.let { target = it }
        val address = target ?: FALLBACK
        // DWORD в сетевом порядке байтов: первый октет — младший байт числа.
        val b = address.address
        val destination = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
        val index = IntByReference()
        val routed = lib.GetBestInterface(destination, index) == 0
        val now = if (routed) {
            val nic = runCatching { NetworkInterface.getByIndex(index.value) }.getOrNull()
            val addresses = nic?.inetAddresses?.toList()?.map { it.hostAddress }?.sorted()?.joinToString(",").orEmpty()
            (nic?.name ?: index.value.toString()) + "=" + addresses
        } else {
            ""
        }
        val previous = last
        last = now
        _state.value = if (routed) NetworkState.AVAILABLE else NetworkState.LOST
        if (previous == null || previous == now) return
        if (!routed) {
            Journal.note(LogCode.NET_CHANNEL, "сети нет: маршрута до сервера нет")
        } else {
            // Имя интерфейса — в журнал: «сеть сменилась» без него не отличить VPN от
            // переключения Wi-Fi. Адресов не пишем: имени достаточно.
            Journal.note(
                LogCode.NET_CHANNEL, "сеть сменилась",
                "к серверу через" to now.substringBefore('='),
                "было" to previous.substringBefore('=').ifEmpty { "—" },
            )
            _switched.tryEmit(Unit)
        }
    }

    private companion object {
        const val POLL_MS = 2_000L

        /** Куда спрашивать маршрут, пока адреса сервера не узнали: маршрут в интернет. */
        val FALLBACK: Inet4Address = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)) as Inet4Address
    }
}
