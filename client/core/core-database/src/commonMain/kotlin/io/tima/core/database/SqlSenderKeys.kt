package io.tima.core.database

/**
 * Ключи подписи устройств авторов на устройстве (план КК, КК13; заказчик 2026-10-29 «ключи подписи хранить»).
 *
 * Раньше приёмник держал их только в памяти, и после перезапуска их не было. Устройство, отозванное раньше,
 * чем мы сходили за его ключом, оставляло свои сообщения нечитаемыми навсегда (беда 2026-10-24).
 *
 * **Ключ закрепляется при первой встрече** — как ключ личности в доверии к устройствам: сервер мог бы
 * подложить под тот же номер устройства другой ключ и подписать им что угодно. Поэтому [put] не заменяет
 * записанное, а говорит, совпало ли.
 */
class SqlSenderKeys(db: TimaDatabase) {

    private val q = db.senderKeysQueries

    /** Записанный ключ устройства; `null` — не встречали. */
    fun get(deviceId: String): SenderKey? = q.senderKey(deviceId).executeAsOneOrNull()?.let {
        SenderKey(it.device_id, it.user_id, it.signing_pub, it.revoked_at, it.revoked_reason)
    }

    /**
     * Записать ключ, если его ещё нет, и отметить отзыв, если он случился.
     *
     * @return `false` — у устройства уже записан **другой** ключ: подмена, принимать нельзя.
     */
    fun put(key: SenderKey): Boolean {
        val before = get(key.deviceId)
        if (before != null && !before.signingPub.contentEquals(key.signingPub)) return false
        if (before == null) {
            q.putSenderKey(key.deviceId, key.userId, key.signingPub, key.revokedAtMs, key.revokedReason)
        } else if (key.revokedAtMs != 0L && before.revokedAtMs != key.revokedAtMs) {
            q.markSenderRevoked(key.revokedAtMs, key.revokedReason, key.deviceId)
        }
        return true
    }
}

/**
 * Ключ подписи устройства автора.
 *
 * @param revokedAtMs когда устройство отозвано, по часам сервера; 0 — действующее.
 * @param revokedReason причина отзыва (`disputed` — заявка «Аккаунт украден»).
 */
class SenderKey(
    val deviceId: String,
    val userId: String,
    val signingPub: ByteArray,
    val revokedAtMs: Long = 0,
    val revokedReason: String = "",
) {
    /**
     * Подпись этим ключом подтверждает автора (Р11, развилка 7а): у действующего устройства — да; у
     * отозванного — да, кроме отзыва по краже: тогда до отзыва писать мог и вор.
     */
    val confirms: Boolean get() = revokedAtMs == 0L || revokedReason !in THEFT

    companion object {
        /** Причины отзыва, при которых подпись не подтверждает автора (Р11). */
        val THEFT = setOf("disputed", "stolen", "compromised")
    }
}
