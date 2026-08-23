package io.tima.core.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

internal fun JsonObject.bool(name: String): Boolean? =
    runCatching { this[name]?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull()

/** Массив или `null`: сервер мог ответить чем угодно, и падать на этом незачем. */
internal fun JsonElement.jsonArrayOrNull(): List<JsonElement>? =
    runCatching { (this as JsonArray).toList() }.getOrNull()

internal fun JsonElement.jsonObjectOrNull(): JsonObject? =
    runCatching { this as JsonObject }.getOrNull()

/** Целое поле. `null` — поля нет или это не число: придумывать здесь ноль нельзя. */
internal fun JsonObject.int(name: String): Int? =
    runCatching { this[name]?.jsonPrimitive?.content?.toInt() }.getOrNull()
