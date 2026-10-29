package io.tima.core.encryption

import io.kodium.Kodium
import io.kodium.ratchet.HKDF
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ключ копии из своего источника (план КК, КК8; решение заказчика 2026-10-29 Р4): ключ личности аккаунта,
 * номер копии, её открытый ключ и подпись открытого ключа ключом личности.
 *
 * Устройство заворачивает в копию только под этот ключ. Источники — фраза этого устройства или запись в книге
 * аккаунта (служебная группа: её ключ есть только у своих заверенных устройств). Ответ сервера — подсказка
 * «пара могла смениться», решения по нему не принимаются: иначе взломанный сервер подсунул бы свою пару
 * «ключ личности + ключ копии» с сошедшейся подписью.
 */
class CopyAnchor(val identityPub: ByteArray, val epoch: Int, val pub: ByteArray, val sig: ByteArray) {

    /** Подпись открытого ключа копии ключом личности сходится. */
    fun valid(): Boolean = identityPub.size == 32 && pub.size == 32 && KeyCopy.verify(identityPub, epoch, pub, sig)

    /** Строка для настроек устройства: всё открытое, секрета здесь нет. */
    fun encode(): String = listOf(epoch.toString(), hex(identityPub), hex(pub), hex(sig)).joinToString("|")

    companion object {
        fun decode(text: String): CopyAnchor? = runCatching {
            val (e, i, p, s) = text.split("|")
            CopyAnchor(unhex(i), e.toInt(), unhex(p), unhex(s)).takeIf { it.valid() }
        }.getOrNull()

        private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * Запись «ключ копии» в книге аккаунта (КК8): под ключом служебной группы, как книга контактов и отметки
 * «просмотрено до». Своя метка производного ключа — новая запись, прежние данные она не трогает.
 */
object CopyAnchorCodec {

    private const val LABEL = "tima:account-store:keycopy:v1"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun seal(groupKey: ByteArray, anchor: CopyAnchor): ByteArray? = runCatching {
        val wire = Wire(e = anchor.epoch, i = anchor.identityPub.toList(), p = anchor.pub.toList(), s = anchor.sig.toList())
        Kodium.encryptSymmetric(key(groupKey), json.encodeToString(Wire.serializer(), wire).encodeToByteArray()).getOrThrow()
    }.getOrNull()

    /** `null` — не тот ключ, испорчено или подпись не сходится. */
    fun open(groupKey: ByteArray, sealed: ByteArray): CopyAnchor? = runCatching {
        val plain = Kodium.decryptSymmetric(key(groupKey), sealed).getOrThrow()
        val wire = json.decodeFromString(Wire.serializer(), plain.decodeToString())
        CopyAnchor(wire.i.toByteArray(), wire.e, wire.p.toByteArray(), wire.s.toByteArray()).takeIf { it.valid() }
    }.getOrNull()

    private fun key(groupKey: ByteArray): ByteArray =
        HKDF.deriveSecrets(salt = null, ikm = groupKey, info = LABEL.encodeToByteArray(), length = 32)

    @Serializable
    private data class Wire(val e: Int, val i: List<Byte>, val p: List<Byte>, val s: List<Byte>)
}
