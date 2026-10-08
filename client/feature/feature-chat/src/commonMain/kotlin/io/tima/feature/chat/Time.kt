package io.tima.feature.chat

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * Время сообщения — часы и минуты **местного** времени.
 *
 * `kotlinx-datetime`, а не `java.time`: последнего на iOS нет, и запрещён он
 * архитектурным правилом именно поэтому.
 *
 * Даты здесь нет. В чате её место — разделитель дня, в списке переписок — своя запись
 * («вчера», день недели, число); и то и другое приезжает вместе с историей. Показывать
 * дату в каждой строке значило бы повторять её двадцать раз подряд.
 *
 * Общая функция для чата и для списка: одно и то же время в двух местах обязано
 * выглядеть одинаково.
 */
internal fun time(atMs: Long): String {
    val local = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "$hour:$minute"
}

/**
 * День строки списка — «Сегодня», «Вчера» или число без года «08.10» (заказчик 2026-10-08):
 * нижней строкой в «Чатах» и «Звонках», под временем.
 */
internal fun day(atMs: Long, today: String, yesterday: String, nowMs: Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()): String {
    val zone = TimeZone.currentSystemDefault()
    val at = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(zone).date
    val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
    return when (at) {
        now -> today.replaceFirstChar { it.uppercase() }
        now.minus(1, kotlinx.datetime.DateTimeUnit.DAY) -> yesterday.replaceFirstChar { it.uppercase() }
        else -> at.dayOfMonth.toString().padStart(2, '0') + "." + at.monthNumber.toString().padStart(2, '0')
    }
}
