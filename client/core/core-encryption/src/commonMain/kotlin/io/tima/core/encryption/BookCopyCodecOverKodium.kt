package io.tima.core.encryption

import io.kodium.ratchet.HKDF
import io.kodium.Kodium
import io.tima.domain.chat.BookCopy
import io.tima.domain.chat.BookCopyCodec
import io.tima.domain.chat.CopyContact
import io.tima.domain.chat.CopySection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Копия книги под ключом служебной группы (ПЛАН-РАЗДЕЛОВ Р2а).
 *
 * **Ключ копии выводится из ключа группы, а не берётся им же.** Ключ группы заведён для
 * сообщений; закрывать им и книгу значило бы использовать один ключ в двух назначениях —
 * та ошибка, от которой HKDF с меткой и защищает: метка «копия книги» даёт другой ключ из
 * того же материала, и ни один из двух не открывает чужое.
 *
 * Внутри — JSON. Порядок записей закреплён (номера, потом идентификаторы разделов), чтобы
 * одна и та же книга давала один и тот же байтовый снимок: так расхождения видны сравнением.
 */
object BookCopyCodecOverKodium : BookCopyCodec {

    private const val LABEL = "tima:account-store:book:v1"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun seal(key: ByteArray, copy: BookCopy): ByteArray? = runCatching {
        val plain = json.encodeToString(Wire.serializer(), Wire.of(copy)).encodeToByteArray()
        Kodium.encryptSymmetric(copyKey(key), plain).getOrThrow()
    }.getOrNull()

    override fun open(key: ByteArray, sealed: ByteArray): BookCopy? = runCatching {
        val plain = Kodium.decryptSymmetric(copyKey(key), sealed).getOrThrow()
        json.decodeFromString(Wire.serializer(), plain.decodeToString()).toCopy()
    }.getOrNull()

    private fun copyKey(groupKey: ByteArray): ByteArray =
        HKDF.deriveSecrets(salt = null, ikm = groupKey, info = LABEL.encodeToByteArray(), length = 32)

    // ── проводной вид: короткие имена, потому что блоб идёт по сети ─────────────

    @Serializable
    private data class Wire(val r: Long, val d: String, val c: List<WireContact>, val s: List<WireSection>) {
        fun toCopy() = BookCopy(
            revision = r, device = d,
            contacts = c.map { CopyContact(it.p, it.n, it.s, it.m, it.h, it.t, it.d) },
            sections = s.map { CopySection(it.i, it.n, it.c, it.p, it.x, it.t, it.d) },
        )

        companion object {
            fun of(copy: BookCopy) = Wire(
                r = copy.revision, d = copy.device,
                c = copy.contacts.sortedBy { it.phone }.map { WireContact(it.phone, it.nameOwn, it.sectionId, it.manual, it.hidden, it.updatedAt, it.device) },
                s = copy.sections.sortedBy { it.id }.map { WireSection(it.id, it.name, it.icon, it.place, it.deleted, it.updatedAt, it.device) },
            )
        }
    }

    @Serializable
    private data class WireContact(val p: String, val n: String?, val s: String, val m: Boolean, val h: Boolean, val t: Long, val d: String)

    @Serializable
    private data class WireSection(val i: String, val n: String, val c: Int, val p: Int, val x: Boolean, val t: Long, val d: String)
}
