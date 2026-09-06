package io.tima.shared

import io.tima.core.diag.Journal
import kotlinx.datetime.Clock
import io.tima.core.network.DeviceTokenApi
import io.tima.core.network.DeviceTokenResult
import io.tima.core.network.deviceTokenSigningBytes
import io.tima.domain.account.Session

/**
 * Живой токен доступа устройства.
 *
 * **Зачем он появился.** Токен живёт сутки, и до 2026-09-06 обновлять его было нечем:
 * через сутки после входа приложение упиралось в `401` на каждой ручке под токеном —
 * молча. Человек видел не «войдите снова», а просто неработающее приложение: сообщения не
 * уходят, список устройств пуст, лента не грузится. Поймано первым живым отчётом о
 * проблеме (ПЛАН-ОТЛАДКИ.md §6).
 *
 * **Чем доказываем право на новый токен.** Ключом устройства — тем самым, которым
 * подтверждается привязка. Открытая часть у сервера с регистрации, закрытая лежит в
 * хранилище платформы. Refresh-токен здесь был бы вторым секретом с той же ролью: его
 * пришлось бы отдельно хранить и отдельно отзывать.
 *
 * **Обновление идёт двумя путями, и оба нужны.** По сроку — заранее, чтобы обычная работа
 * не спотыкалась; по `401` — на случай, когда сервер отверг токен раньше времени (часы
 * разошлись, ключ подписи сменился, токен отозван вместе с сессией).
 */
class DeviceTokens(
    private val api: DeviceTokenApi,
    private val session: Session,
    /** Подписать байты ключом ЭТОГО устройства. `null` — подписывать нечем. */
    private val sign: (ByteArray) -> ByteArray?,
    private val now: () -> Long,
    /** Куда положить новый токен, чтобы он пережил перезапуск. */
    private val remember: (Session) -> Unit,
) {
    /** Тот токен, которым сейчас подписываются запросы. */
    var access: String = session.accessToken
        private set

    /**
     * Обновить, если пора.
     *
     * «Пора» — это когда до конца осталось меньше [MARGIN] или когда срок прочитать не
     * удалось. Второе важнее первого: токен неизвестного возраста лучше обновить, чем
     * однажды обнаружить его мёртвым в середине отправки.
     */
    suspend fun renewIfStale(): Boolean {
        val expires = expiresAt(access)
        val soon = expires == null || expires - now() < MARGIN
        return if (soon) renew() else false
    }

    /** Обновить сейчас. `false` — не вышло; прежний токен остаётся на месте. */
    suspend fun renew(): Boolean {
        val issuedAt = now() / 1000
        val signature = sign(deviceTokenSigningBytes(session.userId, session.deviceId, issuedAt))
        if (signature == null) {
            Journal.trouble("вход", "нечем подписать обновление токена: ключа устройства нет")
            return false
        }
        return when (val answer = api.renew(session.userId, session.deviceId, issuedAt, signature)) {
            is DeviceTokenResult.Renewed -> {
                access = answer.accessToken
                // Сохраняем сразу: незаписанный токен означает, что после перезапуска мы
                // снова придём сюда же — и так каждый запуск.
                remember(Session(session.userId, session.deviceId, answer.accessToken))
                Journal.note("вход", "токен обновлён")
                true
            }

            DeviceTokenResult.Revoked -> {
                // Отзыв — это конец, а не заминка: у этого устройства доступа больше нет,
                // и повторять запрос бессмысленно. Человеку это скажет экран, когда
                // дойдёт до действия; здесь важно не крутить обновление впустую.
                Journal.trouble("вход", "устройство отозвано — обновлять нечего")
                false
            }

            is DeviceTokenResult.NoConnection -> {
                Journal.trouble("вход", "обновление токена не дошло до сервера")
                false
            }

            is DeviceTokenResult.Refused -> {
                Journal.trouble("вход", "сервер не обновил токен: " + answer.status + " " + answer.code)
                false
            }
        }
    }

    private companion object {
        /**
         * За сколько до конца обновляемся.
         *
         * Час при сутках жизни: приложение, открытое раз в день, успеет обновиться в
         * обычной работе, а не в момент, когда человек нажал «отправить».
         */
        const val MARGIN: Long = 60 * 60 * 1000
    }
}

/** Часы приложения. Отдельной функцией, чтобы проверки могли двигать время. */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * Когда истекает токен — по его же содержимому.
 *
 * JWT состоит из трёх частей через точку; вторая — это JSON с полем `exp` в секундах
 * эпохи. Разбор здесь нарочно грубый: **подпись мы не проверяем и проверять не должны** —
 * это дело сервера, а нам нужно одно число, чтобы решить, не пора ли обновиться. Не
 * разобралось — вернётся `null`, и вызывающий обновит на всякий случай.
 *
 * @return время истечения в миллисекундах эпохи или `null`.
 */
fun expiresAt(token: String): Long? {
    val payload = token.split('.').getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    val json = runCatching { decodeBase64UrlToText(payload) }.getOrNull() ?: return null
    val at = Regex("\"exp\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.getOrNull(1) ?: return null
    return at.toLongOrNull()?.times(1000)
}

/** base64url без выравнивания — тот вид, в котором JWT носит свои части. */
private fun decodeBase64UrlToText(value: String): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    var buffer = 0
    var bits = 0
    val bytes = ArrayList<Byte>(value.length * 3 / 4 + 3)
    for (symbol in value) {
        val index = alphabet.indexOf(symbol)
        if (index < 0) continue
        buffer = (buffer shl 6) or index
        bits += 6
        if (bits >= 8) {
            bits -= 8
            bytes.add(((buffer shr bits) and 0xFF).toByte())
        }
    }
    return bytes.toByteArray().decodeToString()
}
