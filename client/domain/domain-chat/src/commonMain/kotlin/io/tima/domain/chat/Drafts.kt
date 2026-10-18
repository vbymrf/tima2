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
}
