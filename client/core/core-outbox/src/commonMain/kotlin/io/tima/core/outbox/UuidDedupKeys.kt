@file:OptIn(ExperimentalUuidApi::class)

package io.tima.core.outbox

import io.tima.domain.chat.DedupKeys
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Ключи идемпотентности — UUID v4.
 *
 * **Почему случайный, а не счётчик.** Счётчик на устройстве после переустановки
 * начинается заново, и второе сообщение с ключом `1` сервер отбросит как повтор
 * первого — того, что отправили до переустановки. Случайный ключ такого не умеет по
 * построению.
 *
 * Живёт в `core-outbox`, а не в `core-model`: `core-model` не имеет права видеть
 * ничего, кроме `kotlin.*` и своих типов, а порт объявлен в `domain-chat`.
 */
object UuidDedupKeys : DedupKeys {
    override fun newKey(): String = Uuid.random().toString()
}
