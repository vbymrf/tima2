package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.HistoryKeys
import io.tima.core.network.HistoryApi

/**
 * Передача истории личных переписок своему новому устройству (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ИУ2).
 *
 * Запускается на доверенном устройстве, когда оно заверило новое по QR: при привязке или
 * заверением уже подключённого (Р32). Для каждой своей личной переписки берёт с сервера
 * историю под себя, перезаворачивает ключ каждого сообщения под новое устройство и отдаёт
 * сервер. Новое устройство получает событие и забирает историю само.
 *
 * Видно ровно то, что видит это устройство: сервер отдаёт сообщения, для которых у него
 * есть обёртка, — то есть за срок хранения обёрток (90 дней). Старше — дело копии под
 * ключом из фразы (ИУ6).
 */
class HistoryHandover(
    private val history: HistoryApi,
    private val myDeviceId: String,
    private val identity: DeviceIdentity,
    /**
     * Копия ключей и её секрет на этом телефоне (модель Matrix, М4, Р46). Есть — история
     * берётся из копии целиком, за весь срок хранения сообщений; нет — прежним путём, из
     * своих обёрток за 90 дней.
     */
    private val keyCopy: io.tima.core.network.KeyCopyApi? = null,
    private val copy: DeviceIdentity? = null,
) {

    /** @return сколько ключей принял сервер. */
    suspend fun handOver(deviceId: String, encryptionPub: ByteArray): Int {
        if (deviceId == myDeviceId) return 0
        val chats = history.personalChats() ?: run {
            Journal.trouble(LogCode.DEVICE_TRUST, "история новому устройству: список переписок не получен")
            return 0
        }
        var total = 0
        var failed = 0
        for (chat in chats) {
            val keys = mutableListOf<HistoryApi.Key>()
            var before = 0L
            val fromCopy = if (keyCopy != null && copy != null) {
                while (true) {
                    val page = keyCopy.page(chat.chatId, before) ?: break
                    if (page.isEmpty()) break
                    for (item in page) {
                        val r = io.tima.core.encryption.KeyCopy.rewrapFor(item.envelope, item.wrapEphemeral, copy, encryptionPub)
                        if (r == null) failed++ else keys += HistoryApi.Key(r.messageId, r.ephemeralPub, r.wrapped)
                    }
                    if (page.size < io.tima.core.network.KeyCopyApi.PAGE) break
                    before = page.last().messageId
                }
                keys.isNotEmpty()
            } else {
                false
            }
            // Копии нет или она пуста — прежним путём: свои обёртки за срок их хранения.
            if (!fromCopy) {
                before = 0L
                while (true) {
                    val page = history.page(chat.chatId, before) ?: break
                    if (page.isEmpty()) break
                    for (item in page) {
                        val r = HistoryKeys.rewrap(item.envelope, item.wrapEphemeral, myDeviceId, identity, encryptionPub)
                        if (r == null) failed++ else keys += HistoryApi.Key(r.messageId, r.ephemeralPub, r.wrapped)
                    }
                    if (page.size < HistoryApi.PAGE) break
                    before = page.last().messageId
                }
            }
            // Одной передачей на переписку, а не на страницу: каждая передача будит новое
            // устройство, и оно забирает переписку целиком.
            for (part in keys.chunked(CHUNK)) total += history.provide(chat.chatId, deviceId, part) ?: 0
        }
        Journal.note(
            LogCode.DEVICE_TRUST, "история передана новому устройству",
            "устройство" to deviceId.take(8), "переписок" to chats.size, "ключей" to total, "не перезавёрнуто" to failed,
        )
        return total
    }

    private companion object {
        /** Ключей в одной передаче: ~200 байт каждый, сервер принимает до 8 МБ. */
        const val CHUNK = 5000
    }
}
