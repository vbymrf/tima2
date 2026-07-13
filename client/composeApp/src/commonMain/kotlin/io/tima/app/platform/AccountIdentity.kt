package io.tima.app.platform

/** Ключи из recovery-фразы (ADR-0010). Секрет/публичная часть личности + backup-ключ — base64url. */
data class IdentityKeys(
    val phrase: List<String>,
    val secretB64: String,
    val pubB64: String,
    val backupB64: String, // симметричный ключ бэкапа «сообщений себе» (этап 4)
)

/** Новая случайная фраза + выведенный из неё ключ личности. */
expect fun newIdentity(): IdentityKeys

/** Ключ личности из введённой фразы; null — фраза неверна (слова/контрольная сумма). */
expect fun identityFromPhrase(words: List<String>): IdentityKeys?
