package io.tima.shared

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * «Не беспокоить, в часы:» (заказчик 2026-10-01, «Настройки → Уведомления»).
 *
 * Что глушить — галочками:
 * - [messages] — личные и групповые: строки в шторке нет, звука нет;
 * - [calls] — входящий звонок приходит строкой в шторке без мелодии и без полного экрана,
 *   пропущенные — без строки.
 *
 * Число на значке и счётчики в приложении копятся как обычно: открыл приложение — всё видно.
 *
 * Время — местное, по часам устройства: «с 23 до 8» человек считает по своим часам, а не по
 * серверным. Конец позже начала — тот же день; раньше — через полночь (23:00–08:00).
 *
 * @param from начало, минуты от полуночи.
 * @param to конец, минуты от полуночи; не входит.
 */
data class QuietHours(
    val on: Boolean = false,
    val from: Int = DEFAULT_FROM,
    val to: Int = DEFAULT_TO,
    val calls: Boolean = true,
    val messages: Boolean = true,
) {

    /** Тихо ли в минуту [minute] от полуночи. Начало равно концу — тихо никогда. */
    fun covers(minute: Int): Boolean = when {
        !on || from == to -> false
        from < to -> minute in from until to
        else -> minute >= from || minute < to
    }

    /** Тихо ли сейчас по часам устройства. */
    fun now(): Boolean {
        val local = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return covers(local.hour * 60 + local.minute)
    }

    /** Глушить ли сейчас звонки: входящий без мелодии, пропущенные без строки. */
    fun callsMuted(): Boolean = calls && now()

    /** Глушить ли сейчас сообщения — личные и групповые. */
    fun messagesMuted(): Boolean = messages && now()

    companion object {
        const val KEY_ON = "notice.quiet.on"
        const val KEY_FROM = "notice.quiet.from"
        const val KEY_TO = "notice.quiet.to"
        const val KEY_CALLS = "notice.quiet.calls"
        const val KEY_MESSAGES = "notice.quiet.messages"

        /** 23:00 — 08:00: ночь. */
        const val DEFAULT_FROM = 23 * 60
        const val DEFAULT_TO = 8 * 60

        fun read(all: Map<String, String>): QuietHours = QuietHours(
            on = all[KEY_ON] == "1",
            from = all[KEY_FROM]?.toIntOrNull()?.coerceIn(0, 24 * 60 - 1) ?: DEFAULT_FROM,
            to = all[KEY_TO]?.toIntOrNull()?.coerceIn(0, 24 * 60 - 1) ?: DEFAULT_TO,
            // Нет ключа — глушится: «не беспокоить» без галочек ничего бы не делало.
            calls = all[KEY_CALLS] != "0",
            messages = all[KEY_MESSAGES] != "0",
        )
    }
}
