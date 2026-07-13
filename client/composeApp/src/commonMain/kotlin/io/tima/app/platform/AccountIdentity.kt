package io.tima.app.platform

/** Ключ личности из recovery-фразы (ADR-0010 §этап 3). Секрет и публичная часть — base64url. */
data class IdentityKeys(
    val phrase: List<String>,
    val secretB64: String,
    val pubB64: String,
)

/** Новая случайная фраза + выведенный из неё ключ личности. */
expect fun newIdentity(): IdentityKeys

/** Ключ личности из введённой фразы; null — фраза неверна (слова/контрольная сумма). */
expect fun identityFromPhrase(words: List<String>): IdentityKeys?
