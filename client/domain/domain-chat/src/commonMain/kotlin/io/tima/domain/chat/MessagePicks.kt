package io.tima.domain.chat

/**
 * Подборка сообщений (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §11.2): ссылки на сообщения, а не копии.
 *
 * Своя у верхнего уровня — у сообщества; нет его — у группы или канала; у личной переписки —
 * только своя, общая для всех личных переписок. Живёт на устройстве, на сервер не уходит. Не больше
 * [MAX] ссылок на уровень. Вставляет из подборки редактор (ПЛАН-(РС) РС6); здесь — «Добавить» из
 * меню сообщения.
 */
class MessagePicks(private val store: PickStore, private val nowMs: () -> Long) {

    fun add(level: String, chatId: String, serverId: Long): PickResult {
        if (serverId <= 0) return PickResult.NotSent
        if (store.has(level, chatId, serverId)) return PickResult.Already
        if (store.count(level) >= MAX) return PickResult.Full
        store.add(level, chatId, serverId, nowMs())
        return PickResult.Added
    }

    /** Подборка уровня — новые первыми; вставляет редактор (Решения §11.2). */
    fun list(level: String): List<Pick> = store.list(level)

    companion object {
        /** Предложение решений §11.2: до 20 ссылок на уровень. */
        const val MAX = 20

        /** Уровень личной переписки: подборка своя, одна на все личные переписки. */
        const val SELF = "self"

        /**
         * Чья подборка: сообщества, если группа в нём; иначе самой группы; у личной — своя.
         */
        fun levelOf(chatId: String, group: Boolean, community: String? = null): String = when {
            !group -> SELF
            !community.isNullOrBlank() -> "community:$community"
            else -> "group:$chatId"
        }
    }
}

sealed interface PickResult {
    data object Added : PickResult
    data object Already : PickResult
    data object Full : PickResult
    data object NotSent : PickResult
}

/** Порт к базе устройства. Реализуется `core-database`. */
interface PickStore {
    fun has(level: String, chatId: String, serverId: Long): Boolean
    fun count(level: String): Int
    fun add(level: String, chatId: String, serverId: Long, atMs: Long)

    /** Новые первыми. */
    fun list(level: String): List<Pick> = emptyList()
}

/** Ссылка в подборке: переписка и номер сообщения на сервере. */
data class Pick(val chatId: String, val serverId: Long)
