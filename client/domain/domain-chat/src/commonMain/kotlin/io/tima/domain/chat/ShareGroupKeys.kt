package io.tima.domain.chat

/**
 * Ответить на просьбу участника о недостающих версиях ключа.
 *
 * **Это встречная половина [RequestGroupKeys].** Без неё просьба уходит в пустоту: сервер
 * рассылает `recovery.gk_request` тем, у кого версии есть, и если никто не отвечает,
 * человек, попросивший ключ, ждёт вечно и никогда не узнает почему.
 *
 * **Согласия не спрашиваем, и это решение, а не упущение.** Просящий — участник группы, а
 * значит имеет право на её историю; спрашивать «поделиться ли» означало бы предлагать
 * человеку решать за группу то, что уже решено её составом. Отдаём молча и только то, что
 * просят, и только участнику — членство проверяет сервер на обеих сторонах.
 *
 * Отдаём **лишь имеющиеся** версии: чего у нас нет, того не выдумываем. Если у нас нет
 * ничего из запрошенного, просьба остаётся без ответа от нас — ответит другой помощник,
 * которому сервер её тоже прислал.
 */
class ShareGroupKeys(
    private val keys: GroupKeyBook,
    private val wrap: GroupKeyWrapForDevice,
    private val upload: GroupKeyShareUpload,
) {

    suspend fun share(
        groupId: String,
        requesterDevice: String,
        requesterEncryptionPub: ByteArray,
        versions: List<Int>,
    ): ShareStep {
        val wraps = mutableListOf<SharedVersion>()
        for (version in versions.distinct().sorted()) {
            val key = keys.key(groupId, version) ?: continue
            val wrap = wrap.wrap(requesterEncryptionPub, key) ?: continue
            wraps += SharedVersion(version, wrap.senderEphemeralPub, wrap.wrapped)
        }
        if (wraps.isEmpty()) return ShareStep.NothingToShare
        return upload.provide(groupId, requesterDevice, wraps)
    }
}

// ── порты ───────────────────────────────────────────────────────────────────

/** Порт обёртывания ключа под чужое устройство. Реализуется `core-encryption`. */
fun interface GroupKeyWrapForDevice {
    /** `null` — обернуть не удалось: испорченный открытый ключ. Тогда эту версию пропускаем. */
    fun wrap(recipientEncryptionPub: ByteArray, key: ByteArray): SharedKeyBytes?
}

/** Порт отправки обёрток. Реализуется `core-network`. */
interface GroupKeyShareUpload {
    suspend fun provide(groupId: String, requesterDevice: String, keys: List<SharedVersion>): ShareStep
}

class SharedKeyBytes(val senderEphemeralPub: ByteArray, val wrapped: ByteArray)

class SharedVersion(val gkVersion: Int, val senderEphemeralPub: ByteArray, val wrapped: ByteArray)

// ── исходы ──────────────────────────────────────────────────────────────────

sealed interface ShareStep {
    data class Shared(val versions: Int) : ShareStep

    /** Ни одной запрошенной версии у нас нет — отвечать нечем, и это нормально. */
    data object NothingToShare : ShareStep

    data class Offline(val retryAfterMs: Long) : ShareStep
    data class Refused(val reason: String) : ShareStep
}
