package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.network.ChannelPing

/**
 * Экономичный режим живого канала — настройка устройства (заказчик 2026-10-06: «в настройках
 * „Уведомления“, по умолчанию 90 секунд, не включено; логирование, если включено»).
 *
 * Перекличка реже — меньше пробуждений телефона, но и слепое окно длиннее: звонок, пришедший в
 * тихо оборвавшийся канал, пропадёт. Ради проверки «рвётся ли на мобильной» включённый режим
 * пишет в журнал каждое открытие и обрыв канала с сетью и временем жизни (`Receiver`).
 */
object ChannelEconomy {
    /** Ключи настроек — латиницей: это имена того, что приложение кладёт на диск. */
    const val KEY_ON = "channel.economy.on"
    const val KEY_SECONDS = "channel.economy.seconds"

    const val DEFAULT_SECONDS = 90
    const val MIN_SECONDS = 20
    const val MAX_SECONDS = 600
    const val STEP_SECONDS = 10

    data class Choice(val on: Boolean, val seconds: Int)

    fun read(saved: Map<String, String>): Choice = Choice(
        on = saved[KEY_ON] == "1",
        seconds = saved[KEY_SECONDS]?.toIntOrNull()?.coerceIn(MIN_SECONDS, MAX_SECONDS) ?: DEFAULT_SECONDS,
    )

    /** Применить к каналу; смена — строкой в журнал. */
    fun apply(saved: Map<String, String>) {
        val choice = read(saved)
        val seconds = if (choice.on) choice.seconds else 0
        if (seconds == ChannelPing.economySeconds) return
        ChannelPing.economySeconds = seconds
        Journal.note(
            LogCode.NET_CHANNEL,
            if (choice.on) "экономичный режим включён" else "экономичный режим выключен",
            "перекличка" to "${ChannelPing.intervalMs() / 1000} с",
        )
    }
}
