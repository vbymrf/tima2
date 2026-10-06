package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.HistoryKeys
import io.tima.core.network.DeviceKeysResult
import io.tima.core.network.HistoryApi
import io.tima.core.network.KeysApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ответ на просьбу о ключах личной переписки (`recovery.msg_request`) — своё устройство или
 * собеседник отдают ключи сообщений устройству, которому их не завернули (заказчик 2026-10-06:
 * «сообщение недоступно, запросить» и тот же запрос сам по себе).
 *
 * До этого сервер рассылал просьбу, а клиент её не разбирал вовсе: история на ПК по QR шла
 * другим путём — телефон отдавал её сам. Сообщения, отправленные и полученные, пока устройство
 * было не заверено, на нём так и оставались «недоступны».
 *
 * **Отдаём только заверенному** — проверка своя, как у отправителя (`DeviceTrustGate`), а не
 * слово сервера; ключ — из проверенного списка, и он обязан совпасть с названным в просьбе
 * (Р57). Заверить устройство без фразы нельзя, поэтому вор SIM так ключей не получит — тот
 * довод, которым в своё время закрыли ИУ4.
 *
 * **Предел — [LIMIT] последних сообщений переписки.** Обёртка — около двухсот байт; 500 —
 * около сотни килобайт с одного помощника, и это месяцы обычной переписки. Старше — только
 * по второй просьбе, которую человек сделает сам. Повтор той же просьбы в течение
 * [AGAIN_MS] не обрабатывается: одну и ту же переписку перезаворачивали бы зря.
 */
class ChatKeyHelper(
    private val history: HistoryApi,
    private val keys: KeysApi,
    private val trustGate: DeviceTrustGate,
    private val myUserId: String,
    private val myDeviceId: String,
    private val identity: () -> DeviceIdentity,
    /** Собеседник личной переписки; `null` — не знаем, и отдавать некому. */
    private val peerOf: suspend (String) -> String?,
    private val now: () -> Long = { msNow() },
) {
    private val lock = Mutex()
    private val answered = HashMap<String, Long>()

    /** Своя область: ответ — страницы истории и запросы, и канал событий их не ждёт. */
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** Ответить в фоне — из обработчика канала. */
    fun later(chatId: String, requesterDevice: String, requesterEncPub: ByteArray, own: Boolean) {
        scope.launch { runCatching { answer(chatId, requesterDevice, requesterEncPub, own) } }
    }

    /** @return сколько ключей принял сервер; 0 — не отдали. */
    suspend fun answer(chatId: String, requesterDevice: String, requesterEncPub: ByteArray, own: Boolean): Int {
        if (requesterDevice == myDeviceId) return 0
        val key = "$chatId|$requesterDevice"
        val fresh = lock.withLock {
            val at = answered[key]
            if (at != null && now() - at < AGAIN_MS) false else true.also { answered[key] = now() }
        }
        if (!fresh) return 0

        val user = if (own) myUserId else peerOf(chatId) ?: return said(chatId, "собеседник переписки неизвестен")
        val listed = keys.devicesOf(user) as? DeviceKeysResult.Devices ?: return said(chatId, "список устройств не получен")
        val device = trustGate.admit(user, listed).firstOrNull { it.deviceId == requesterDevice }
            ?: return said(chatId, "просящее устройство не заверено — ключи не отдаю")
        if (!device.encryptionPub.contentEquals(requesterEncPub)) return said(chatId, "ключ в просьбе не совпал с заверенным")
        val pub = device.wrapPub()

        val out = mutableListOf<HistoryApi.Key>()
        var failed = 0
        var before = 0L
        while (out.size + failed < LIMIT) {
            val page = history.page(chatId, before) ?: break
            if (page.isEmpty()) break
            for (item in page) {
                if (out.size + failed >= LIMIT) break
                val r = HistoryKeys.rewrap(item.envelope, item.wrapEphemeral, myDeviceId, identity(), pub)
                if (r == null) failed++ else out += HistoryApi.Key(r.messageId, r.ephemeralPub, r.wrapped)
            }
            if (page.size < HistoryApi.PAGE) break
            before = page.last().messageId
        }
        var saved = 0
        for (part in out.chunked(CHUNK)) saved += history.provide(chatId, requesterDevice, part) ?: 0
        Journal.note(
            LogCode.DEVICE_TRUST, "ключи переписки отданы по просьбе",
            "переписка" to chatId.take(8), "устройство" to requesterDevice.take(8),
            "своё" to own, "ключей" to saved, "не перезавёрнуто" to failed,
        )
        return saved
    }

    private fun said(chatId: String, why: String): Int {
        Journal.note(LogCode.DEVICE_TRUST, "просьба о ключах переписки не исполнена", "переписка" to chatId.take(8), "почему" to why)
        return 0
    }

    companion object {
        /** Сколько последних сообщений переписки перезаворачивается на одну просьбу. */
        const val LIMIT = 500

        /** Та же просьба раньше этого срока не обрабатывается. */
        const val AGAIN_MS = 10 * 60_000L

        private const val CHUNK = 500
    }
}
