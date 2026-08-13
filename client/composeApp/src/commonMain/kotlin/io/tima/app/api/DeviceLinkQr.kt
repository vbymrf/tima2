@file:OptIn(ExperimentalEncodingApi::class)

package io.tima.app.api

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * QR-payload привязки нового устройства (key-lifecycle.md §2). Формат зеркалит
 * `qrPayload` в `server/internal/api/device_link.go` (linkStart) — расхождение
 * означает, что подтверждающее устройство не сможет разобрать QR, показанный
 * новым устройством.
 *
 * Значения — session_id (UUID) и base64url без паддинга: ни один из символов
 * ('-', '_', цифро-буквенные, дефисы UUID) не требует URL-кодирования, поэтому
 * разбор — простой сплит по '&'/'=', без java.net.URI и без URLDecoder.
 */
data class DeviceLinkQr(
    val sessionId: String,
    val secret: String,
    val encryptionPubB64: String,
    val signingPubB64: String,
    val deviceName: String,
)

private const val QR_PREFIX = "tima://link/v1?"
private val b64url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/**
 * Разбирает QR на стороне подтверждающего устройства (после скана камерой).
 * null — не наш формат или не хватает обязательных полей: показывать «этот QR не
 * от TIMA» вместо падения на середине разбора.
 */
fun parseDeviceLinkQr(payload: String): DeviceLinkQr? {
    val raw = payload.trim()
    if (!raw.startsWith(QR_PREFIX)) return null
    val params = raw.removePrefix(QR_PREFIX)
        .split('&')
        .filter { it.isNotEmpty() }
        .mapNotNull { entry ->
            val i = entry.indexOf('=')
            if (i <= 0) null else entry.substring(0, i) to entry.substring(i + 1)
        }
        .toMap()
    val sessionId = params["session_id"] ?: return null
    val secret = params["secret"] ?: return null
    val encB64 = params["encryption_key"] ?: return null
    val sigB64 = params["signing_key"] ?: return null
    val nameB64 = params["name"] ?: return null
    val name = try {
        b64url.decode(nameB64).decodeToString()
    } catch (_: Throwable) {
        return null
    }
    return DeviceLinkQr(sessionId, secret, encB64, sigB64, name)
}
