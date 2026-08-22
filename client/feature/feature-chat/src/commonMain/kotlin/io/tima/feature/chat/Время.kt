package io.tima.feature.chat

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
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
internal fun время(atMs: Long): String {
    val местное = Instant.fromEpochMilliseconds(atMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val час = местное.hour.toString().padStart(2, '0')
    val минута = местное.minute.toString().padStart(2, '0')
    return "$час:$минута"
}
