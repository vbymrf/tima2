package io.tima.core.encryption

import io.kodium.ratchet.HKDF
import io.kodium.Kodium
import io.tima.domain.chat.BookCopy
import io.tima.domain.chat.BookCopyCodec
import io.tima.domain.chat.BookKey
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
            contacts = c.map {
                CopyContact(
                    // Ключа могло не быть: блоб снят устройством до Л0. Тогда он
                    // считается из номера — ровно так же, как считало то устройство,
                    // когда номер и был ключом.
                    id = it.i ?: BookKey.ofPhone(it.p.orEmpty()),
                    phone = it.p.orEmpty(),
                    userId = it.u,
                    nameOwn = it.n, sectionId = it.s, manual = it.m,
                    // `h` — прежнее «убран»; до Л3 списка не было, было одно надгробие.
                    list = if (it.l != 0) it.l else if (it.h) 1 else 0,
                    known = it.k,
                    updatedAt = it.t, device = it.d,
                )
            },
            sections = s.map { CopySection(it.i, it.n, it.c, it.p, it.x, it.t, it.d) },
        )

        companion object {
            fun of(copy: BookCopy) = Wire(
                r = copy.revision, d = copy.device,
                c = copy.contacts.sortedBy { it.id }.map {
                    WireContact(
                        i = it.id, p = it.phone.ifBlank { null }, u = it.userId,
                        n = it.nameOwn, s = it.sectionId, m = it.manual,
                        l = it.list, k = it.known, t = it.updatedAt, d = it.device,
                    )
                },
                s = copy.sections.sortedBy { it.id }.map { WireSection(it.id, it.name, it.icon, it.place, it.deleted, it.updatedAt, it.device) },
            )
        }
    }

    /**
     * Строка контакта на проводе.
     *
     * Поля, появившиеся в Л0 и Л3, — со значениями по умолчанию, а `h` оставлен ради
     * блобов, снятых до них: другое устройство человека могло не обновиться, и его копия
     * приедет старой. Разбирается она тут, а не отдельной миграцией: блоб приходит по
     * сети, и его версия известна только в момент разбора.
     */
    @Serializable
    private data class WireContact(
        val i: String? = null,
        val p: String? = null,
        val u: String? = null,
        val n: String?,
        val s: String,
        val m: Boolean,
        val h: Boolean = false,
        val l: Int = 0,
        val k: Boolean = false,
        val t: Long,
        val d: String,
    )

    @Serializable
    private data class WireSection(val i: String, val n: String, val c: Int, val p: Int, val x: Boolean, val t: Long, val d: String)
}
