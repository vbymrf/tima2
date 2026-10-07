package io.tima.core.secrets

import io.tima.domain.account.PinCheck
import io.tima.domain.account.PinPort
import io.tima.domain.account.PinRules

/**
 * Пин-код аккаунта на этом устройстве (ПЛАН-(ПН)-ПИН-КОДА).
 *
 * **В хранилище — только хеш** (Р8): соль, медленный хеш, счёт ошибок и конец паузы — одна запись
 * `pin.v1` в разделе аккаунта. Сам пин не хранится нигде; в памяти он живёт на время проверки.
 * Хранилище — то же защищённое, что у секрета устройства (Keystore, DPAPI): достать запись
 * можно, только вскрыв его, а вскрывший достанет и секрет устройства.
 *
 * **Счёт ошибок пишется сразу** (Р10): перезапуск приложения не обнуляет ни его, ни паузу.
 * Пауза считается по часам устройства (Р15).
 *
 * @param hash медленный хеш пина с солью — `core-encryption` (PBKDF2); здесь криптографии нет.
 * @param salt свежая случайная соль.
 */
class PinLock(
    private val vault: SecretVault,
    private val hash: (pin: ByteArray, salt: ByteArray) -> ByteArray,
    private val salt: () -> ByteArray,
    private val now: () -> Long,
) : PinPort {

    private class Record(val salt: ByteArray, val hash: ByteArray, val fails: Int, val pauseUntil: Long)

    override fun isOn(): Boolean = read() != null

    override fun set(pin: String) {
        require(PinRules.valid(pin)) { "пин — ${PinRules.LENGTH} цифры" }
        val s = salt()
        write(Record(s, hash(pin.encodeToByteArray(), s), fails = 0, pauseUntil = 0))
    }

    override fun remove() {
        vault.remove(ALIAS)
    }

    override fun check(pin: String): PinCheck {
        val r = read() ?: return PinCheck.Ok
        blocked(r)?.let { return it }
        if (PinRules.valid(pin) && hash(pin.encodeToByteArray(), r.salt).contentEquals(r.hash)) {
            if (r.fails != 0) write(Record(r.salt, r.hash, 0, 0))
            return PinCheck.Ok
        }
        val fails = r.fails + 1
        val pause = PinRules.pauseAfter(fails)
        write(Record(r.salt, r.hash, fails, if (pause > 0) now() + pause else 0))
        return when {
            fails >= PinRules.PHRASE_AFTER -> PinCheck.PhraseOnly
            pause > 0 -> PinCheck.Paused(now() + pause)
            else -> PinCheck.Wrong(PinRules.PAUSE_AFTER - fails)
        }
    }

    override fun state(): PinCheck {
        val r = read() ?: return PinCheck.Ok
        return blocked(r) ?: PinCheck.Ok
    }

    override fun phraseAccepted() {
        val r = read() ?: return
        write(Record(r.salt, r.hash, 0, 0))
    }

    private fun blocked(r: Record): PinCheck? = when {
        r.fails >= PinRules.PHRASE_AFTER -> PinCheck.PhraseOnly
        r.pauseUntil > now() -> PinCheck.Paused(r.pauseUntil)
        else -> null
    }

    private fun read(): Record? {
        val raw = vault.get(ALIAS)?.decodeToString() ?: return null
        val p = raw.split('|')
        if (p.size != 5 || p[0] != "1") return null
        return runCatching { Record(unhex(p[1]), unhex(p[2]), p[3].toInt(), p[4].toLong()) }.getOrNull()
    }

    private fun write(r: Record) {
        vault.put(ALIAS, listOf("1", hex(r.salt), hex(r.hash), r.fails.toString(), r.pauseUntil.toString()).joinToString("|").encodeToByteArray())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    internal companion object {
        /** Имя латиницей — то, что приложение кладёт на диск. */
        val ALIAS = SecretAlias("pin.v1")
    }
}
