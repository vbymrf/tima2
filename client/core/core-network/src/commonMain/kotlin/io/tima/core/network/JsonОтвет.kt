package io.tima.core.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Разбор ответа сервера — три помощника, общие для всех ручек.
 *
 * Лежат отдельно, потому что нужны каждому классу-ручке, а копия на класс однажды
 * разойдётся: «код ошибки» и «поля нет» — это решения, а не техника, и решаться они
 * обязаны одинаково. Отказ разбора здесь **не исключение**: сервер может ответить чем
 * угодно, включая страницу прокси, и `null` в этом месте честнее падения.
 */
internal suspend fun HttpResponse.jsonBody(): JsonObject? =
    runCatching { Json.parseToJsonElement(bodyAsText()) as JsonObject }.getOrNull()

/** Код ошибки сервера. Тела нет или он не назван — так и говорим, а не молчим. */
internal fun JsonObject?.codeOf(): String = this?.str("code") ?: "без кода"

internal fun JsonObject.str(name: String): String? =
    runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()
