package io.tima.domain.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Подборка сообщений — указатели (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §11.2, ПЛАН-(УК)-УКАЗАТЕЛЕЙ.md): ссылки
 * на сообщения, а не копии.
 *
 * Своя у верхнего уровня — у сообщества; нет его — у группы или канала; у личной переписки — своя у
 * каждой (заказчик 2026-10-21: «каждая своей»), а редактор личной вставляет из всех личных разом.
 * Живёт на устройстве, на сервер не уходит. Не больше [MAX] ссылок на уровень. Добавляет меню
 * сообщения, вставляет редактор, смотрит и убирает вкладка «Указатели».
 */
class MessagePicks(private val store: PickStore, private val nowMs: () -> Long) {

    fun add(level: String, chatId: String, serverId: Long): PickResult {
        if (serverId <= 0) return PickResult.NotSent
        if (store.has(level, chatId, serverId)) return PickResult.Already
        if (store.count(level) >= MAX) return PickResult.Full
        store.add(level, chatId, serverId, nowMs())
        return PickResult.Added
    }

    /** Подборка уровня — новые первыми; вставляет редактор (Решения §11.2). Личная — все личные. */
    fun list(level: String): List<Pick> =
        if (level.startsWith(PERSONAL)) store.listLike("$PERSONAL%") else store.list(level)

    /** Все указатели устройства потоком — вкладка «Указатели». */
    fun pointers(): Flow<List<Pointer>> = store.pointers()

    fun remove(pointers: Collection<Pointer>) = pointers.forEach { store.remove(it.level, it.chatId, it.serverId) }

    /** Убрать сущность — все её указатели. */
    fun removeLevel(level: String) = store.removeLevel(level)

    fun moveTo(pointers: Collection<Pointer>, sectionId: String) =
        pointers.forEach { store.move(it.level, it.chatId, it.serverId, sectionId) }

    companion object {
        /** До 100 ссылок на уровень (заказчик 2026-10-20; прежде 20 — решения §11.2). */
        const val MAX = 100

        /** Подкладок за раз — до 10; «Списком» — до [MAX] (ADR-0050 §1). */
        const val UNDERLAY_MAX = 10

        /** Уровень личной переписки — `personal:<переписка>`. */
        const val PERSONAL = "personal:"

        /**
         * Чья подборка: сообщества, если группа в нём; иначе самой группы; у личной — своей переписки.
         */
        fun levelOf(chatId: String, group: Boolean, community: String? = null): String = when {
            !group -> PERSONAL + chatId
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

    /** Уровни по образцу SQL `LIKE` — новые первыми. */
    fun listLike(pattern: String): List<Pick> = emptyList()

    fun pointers(): Flow<List<Pointer>> = emptyFlow()

    fun remove(level: String, chatId: String, serverId: Long) {}

    fun removeLevel(level: String) {}

    fun move(level: String, chatId: String, serverId: Long, sectionId: String) {}
}

/** Ссылка в подборке: переписка и номер сообщения на сервере. */
data class Pick(val chatId: String, val serverId: Long)

/**
 * Указатель (ПЛАН-(УК)): ссылка в подборке уровня [level] — чья сущность, — с разделом [sectionId]
 * (пусто — «Общий») и временем добавления.
 */
data class Pointer(val level: String, val chatId: String, val serverId: Long, val atMs: Long, val sectionId: String = "") {
    val pick: Pick get() = Pick(chatId, serverId)
}

/**
 * Сообщения-цели, полученные с сервера по номеру (заказчик 2026-10-21: «храним»): ответ сервера как
 * есть, закрытый ключом устройства. `kind` — `frame` или `open`. Реализуется `core-database`.
 */
interface RefCache {
    fun get(chatId: String, serverId: Long): Pair<String, ByteArray>?
    fun put(chatId: String, serverId: Long, kind: String, body: ByteArray)
    fun drop(chatId: String, serverId: Long)
}
