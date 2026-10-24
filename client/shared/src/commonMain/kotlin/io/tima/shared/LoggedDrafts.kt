package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.domain.chat.Drafts

/**
 * Черновики с записью неудач (ПЛАН-(ЖС) §4, `DRAFT`): черновик закрыт ключом покоя (Решения §9), и
 * его потеря должна быть видна. Неудача не роняет переписку — черновика просто нет.
 */
class LoggedDrafts(private val inner: Drafts) : Drafts {
    override fun load(chatId: String): String? = try {
        inner.load(chatId)
    } catch (e: Throwable) {
        Journal.trouble(LogCode.DRAFT, "черновик не открылся", "переписка" to chatId.take(12), "причина" to (e.message ?: e::class.simpleName))
        null
    }

    override fun withSuffix(suffix: String): List<String> = try {
        inner.withSuffix(suffix)
    } catch (e: Throwable) {
        Journal.trouble(LogCode.DRAFT, "шаги черновиков не открылись", "причина" to (e.message ?: e::class.simpleName))
        emptyList()
    }

    override fun save(chatId: String, text: String) {
        try {
            inner.save(chatId, text)
        } catch (e: Throwable) {
            Journal.trouble(LogCode.DRAFT, "черновик не сохранился", "переписка" to chatId.take(12), "причина" to (e.message ?: e::class.simpleName))
        }
    }
}
