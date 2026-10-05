package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.datetime.Clock
import io.tima.core.network.DeviceTokenApi
import io.tima.core.network.DeviceTokenResult
import io.tima.core.network.ServerClock
import io.tima.core.network.deviceTokenSigningBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.tima.domain.account.Session

/**
 * Живой токен доступа устройства.
 *
 * **Зачем он появился.** Токен живёт сутки, и до 2026-09-06 обновлять его было нечем:
 * через сутки после входа приложение упиралось в `401` на каждой ручке под токеном —
 * молча. Человек видел не «войдите снова», а просто неработающее приложение: сообщения не
 * уходят, список устройств пуст, лента не грузится. Поймано первым живым отчётом о
 * проблеме (ПЛАН-(Б)-ОТЛАДКИ.md §6).
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
        // Срок токена — четвёртый вопрос правила журнала: «в каком состоянии был вход».
        // Именно его не хватило 2026-09-06, когда 401 был виден, а причина — нет.
        val (code, words) = state(expires)
        val left = expires?.let { it - now() }
        if (code == LogCode.AUTH_OK) Journal.note(code, words, "осталось" to left?.let(::howLong))
        else Journal.trouble(code, words, "просрочено" to left?.let { howLong(-it) })
        val soon = expires == null || expires - now() < MARGIN
        return if (soon) renew() else false
    }

    /**
     * Состояние входа человеческими словами.
     *
     * «Токен истёк 40 минут назад» вместо «exp=1757169600»: отчёт читают, чтобы понять
     * причину, и перекладывать перевод чисел на читающего — значит терять половину смысла.
     */
    private fun state(expires: Long?): Pair<String, String> = when {
        access.isBlank() -> LogCode.AUTH_NONE to "токена нет: устройство не вошло"
        expires == null -> LogCode.AUTH_UNKNOWN to "срок прочитать не удалось — обновляю на всякий случай"
        expires <= now() -> LogCode.AUTH_EXPIRED to ("токен истёк " + howLong(now() - expires) + " назад — обновляю")
        expires - now() < MARGIN -> LogCode.AUTH_SOON to ("скоро истечёт — обновляю заранее")
        else -> LogCode.AUTH_OK to "токен жив"
    }

    /**
     * Состояние входа словами — для снимка в отчёте.
     *
     * Тот же текст, что уходит в журнал: два разных описания одного состояния однажды
     * разошлись бы, и читающий отчёт получил бы два ответа на один вопрос.
     */
    fun words(): String = state(expiresAt(access)).second

    /**
     * Устройство отключено от аккаунта (сервер: `device_revoked`). Дальше обновлять нечего —
     * экран предлагает войти снова (ПЛАН-(А)-ВЫХОДА-ИЗ-АККАУНТА.md, А3).
     */
    val revoked: StateFlow<Boolean> get() = _revoked
    private val _revoked = MutableStateFlow(false)

    /**
     * Обновление одно на всё приложение (А1, 2026-09-30).
     *
     * На `401` отвечают все ручки разом, и каждая просит новый токен. Без замка каждая
     * шла на сервер сама; теперь первая обновляет, остальные ждут её и берут результат.
     */
    private val lock = Mutex()
    private var lastSuccess = 0L
    private var lastFailure = 0L
    private var failures = 0

    /** Обновить сейчас. `false` — не вышло; прежний токен остаётся на месте. */
    suspend fun renew(): Boolean = lock.withLock {
        val at = now()
        // Только что обновили — токен уже свежий, второй раз за ним не ходим.
        if (at - lastSuccess < FRESH) return@withLock true
        if (_revoked.value) return@withLock false
        // После отказа — пауза с ростом: 30 с, 1 мин, 5 мин. Без неё отказ превращался в
        // шквал: 12 231 попытка за 17 минут на ПК со сбитыми часами (2026-09-30).
        val wait = pause(failures)
        if (failures > 0 && at - lastFailure < wait) {
            if (!waitTold) {
                waitTold = true
                Journal.trouble(LogCode.AUTH_RENEW_WAIT, "обновление после отказа выжидает", "пауза" to howLong(wait))
            }
            return@withLock false
        }
        waitTold = false
        val ok = attempt(retryStale = true)
        if (ok) {
            lastSuccess = now()
            failures = 0
        } else {
            lastFailure = now()
            failures++
        }
        ok
    }

    private var waitTold = false

    /**
     * Одна попытка. Метка времени — **время сервера** (А2): часы устройства могут
     * расходиться с ним дальше окна в две минуты, и тогда подпись по своим часам не
     * принимается никогда. `stale_signature` значит, что поправка устарела; её обновил сам
     * ответ с отказом (заголовок `Date`), поэтому переподписываем один раз.
     */
    private suspend fun attempt(retryStale: Boolean): Boolean {
        val issuedAt = ServerClock.now(now()) / 1000
        val signature = sign(deviceTokenSigningBytes(session.userId, session.deviceId, issuedAt))
        if (signature == null) {
            Journal.trouble(LogCode.AUTH_NO_KEY, "нечем подписать обновление: ключа устройства нет")
            return false
        }
        return when (val answer = api.renew(session.userId, session.deviceId, issuedAt, signature)) {
            is DeviceTokenResult.Renewed -> {
                access = answer.accessToken
                // Сохраняем сразу: незаписанный токен означает, что после перезапуска мы
                // снова придём сюда же — и так каждый запуск.
                remember(Session(session.userId, session.deviceId, answer.accessToken))
                Journal.note(LogCode.AUTH_RENEWED, "токен обновлён")
                true
            }

            DeviceTokenResult.Revoked -> {
                // Отзыв — это конец, а не заминка: у этого устройства доступа больше нет.
                // Экран предложит войти снова (А3); крутить обновление впустую незачем.
                Journal.trouble(LogCode.AUTH_REVOKED, "устройство отозвано — нужен новый вход")
                _revoked.value = true
                false
            }

            is DeviceTokenResult.NoConnection -> {
                Journal.trouble(LogCode.AUTH_RENEW_FAILED, "обновление не дошло до сервера")
                false
            }

            is DeviceTokenResult.Refused -> {
                Journal.trouble(
                    LogCode.AUTH_RENEW_FAILED,
                    "сервер не обновил токен",
                    "код" to answer.status,
                    "причина" to answer.code,
                )
                if (answer.code == STALE && retryStale) attempt(retryStale = false) else false
            }
        }
    }

    private fun howLong(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes < 1 -> "меньше минуты"
            minutes < 60 -> "$minutes мин"
            else -> (minutes / 60).toString() + " ч " + (minutes % 60) + " мин"
        }
    }

    private companion object {
        /** Сервер: метка подписи вне окна — часы разошлись. */
        const val STALE = "stale_signature"

        /** Столько после удачного обновления второе не нужно: токен и так свежий. */
        const val FRESH: Long = 10_000

        /** Пауза после N-го отказа подряд. */
        fun pause(failures: Int): Long = when (failures) {
            0 -> 0L
            1 -> 30_000L
            2 -> 60_000L
            else -> 300_000L
        }

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
