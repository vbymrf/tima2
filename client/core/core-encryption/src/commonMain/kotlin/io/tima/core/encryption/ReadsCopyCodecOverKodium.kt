package io.tima.core.encryption

import io.kodium.Kodium
import io.kodium.ratchet.HKDF
import io.tima.domain.chat.ReadsCopy
import io.tima.domain.chat.ReadsCopyCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Отметки «просмотрено до» под ключом служебной группы (ПЛАН-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ9).
 *
 * Тот же приём, что у копии книги ([BookCopyCodecOverKodium]): ключ отметок **выводится**
 * из ключа группы своей меткой. Одна метка на одно назначение — ключ книги не открывает
 * отметки, ключ отметок не открывает книгу, и ни один не открывает сообщения группы.
 *
 * Внутри — JSON, переписки по порядку: одни и те же отметки дают один и тот же снимок.
 */
object ReadsCopyCodecOverKodium : ReadsCopyCodec {

    private const val LABEL = "tima:account-store:reads:v1"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun seal(key: ByteArray, copy: ReadsCopy): ByteArray? = runCatching {
        val wire = Wire(r = copy.revision, d = copy.device, m = copy.marks.entries.sortedBy { it.key }.map { WireMark(it.key, it.value) })
        Kodium.encryptSymmetric(marksKey(key), json.encodeToString(Wire.serializer(), wire).encodeToByteArray()).getOrThrow()
    }.getOrNull()

    override fun open(key: ByteArray, sealed: ByteArray): ReadsCopy? = runCatching {
        val plain = Kodium.decryptSymmetric(marksKey(key), sealed).getOrThrow()
        val wire = json.decodeFromString(Wire.serializer(), plain.decodeToString())
        ReadsCopy(revision = wire.r, device = wire.d, marks = wire.m.associate { it.c to it.u })
    }.getOrNull()

    private fun marksKey(groupKey: ByteArray): ByteArray =
        HKDF.deriveSecrets(salt = null, ikm = groupKey, info = LABEL.encodeToByteArray(), length = 32)

    @Serializable
    private data class Wire(val r: Long, val d: String, val m: List<WireMark>)

    /** Переписка и «просмотрено до». */
    @Serializable
    private data class WireMark(val c: String, val u: Long)
}
