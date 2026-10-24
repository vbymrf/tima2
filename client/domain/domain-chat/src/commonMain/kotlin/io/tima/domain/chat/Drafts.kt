package io.tima.domain.chat

/**
 * Черновик переписки (Решения §9): у каждой свой, на устройстве и **зашифрованным**, переживает
 * перезапуск. В очередь исходящих не попадает — это ещё не сообщение (ADR-0020). Реализует
 * `core-database` под ключом покоя.
 */
interface Drafts {
    /** Черновик переписки; нет — `null`. */
    fun load(chatId: String): String?

    /** Сохранить; пустой — убрать. */
    fun save(chatId: String, text: String)

    /** Все записи с ключом на [suffix] — шаги черновиков для отчёта о проблеме (2026-10-24); нет — пусто. */
    fun withSuffix(suffix: String): List<String> = emptyList()
}
