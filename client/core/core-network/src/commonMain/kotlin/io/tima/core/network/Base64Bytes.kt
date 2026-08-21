@file:OptIn(ExperimentalEncodingApi::class)

package io.tima.core.network

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Base64 из стандартной библиотеки — общий для всех платформ.
 *
 * @return `null` на негодном входе. Исключение здесь было бы неудобно: вход **всегда**
 *   недоверенный, и отказ — обычный путь, а не поломка.
 */
internal fun decodeBase64(text: String): ByteArray? =
    runCatching { Base64.decode(text) }.getOrNull()

internal fun encodeBase64(bytes: ByteArray): String = Base64.encode(bytes)

/**
 * base64url без выравнивания — то, что разбирает сервер (`base64.RawURLEncoding`).
 *
 * Отдельная функция, а не флаг у общей: обычный base64 с `+`, `/` и `=` сервер не
 * примет, ответит `400 bad_keys`, и выглядеть это будет как «наши ключи не годятся».
 */
internal fun encodeBase64Url(bytes: ByteArray): String =
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
