package io.tima.domain.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Закреплённое сообщение (doc_mig/ПЛАН-(ЗО)-ЗАКРЕПЛЕНИЯ-И-ОПИСАНИЯ.md, ЗО3): одно на личную переписку
 * и группу, сверху. Сервер знает номер открыто — «иначе никто не узнает» (заказчик 2026-10-18);
 * здесь — его снимок на этом устройстве: что прочитано при открытии, что пришло событием.
 *
 * Право решает сервер (личная — любой из двух, группа — модератор и выше); экран лишь не предлагает
 * кнопку тому, у кого права нет.
 */
class ChatPins(private val remote: RemotePins) {

    private val board = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Переписка → номер закреплённого (новейшего). Нет ключа — не знаем или ничего не закреплено. */
    val pinned: StateFlow<Map<String, Long>> = board.asStateFlow()

    private val groups = MutableStateFlow<Map<String, PinBoard>>(emptyMap())

    /** Переписка → закреплённые группой (ПЛАН-(СЦ) СЦ12): название и номера, новые первыми. */
    val boards: StateFlow<Map<String, PinBoard>> = groups.asStateFlow()

    /** Прочитать с сервера при открытии переписки. Без сети — остаётся прежнее. */
    suspend fun refresh(chatId: String, group: Boolean) {
        remote.list(chatId, group)?.let { applied(chatId, it) }
    }

    /** Закрепить: добавить к закреплённым (закрепили второе — одно становится группой). */
    suspend fun pin(chatId: String, group: Boolean, serverId: Long): PinStep = add(chatId, group, listOf(serverId), replace = false)

    /** Добавить или заменить все (СЦ12). */
    suspend fun add(chatId: String, group: Boolean, ids: List<Long>, replace: Boolean): PinStep =
        remote.add(chatId, group, ids, replace).also { step ->
            if (step != PinStep.Done) return@also
            // Снимок — сразу по ответу, без второго запроса; событие с сервера потом его подтвердит.
            val was = groups.value[chatId]
            val now = if (replace || was == null) ids else ids + was.ids.filter { it !in ids }
            applied(chatId, PinBoard(was?.title.orEmpty(), now))
        }

    /** Снять все. */
    suspend fun unpin(chatId: String, group: Boolean): PinStep =
        remote.clear(chatId, group).also { if (it == PinStep.Done) applied(chatId, PinBoard("", emptyList())) }

    /** Снять выбранные. */
    suspend fun remove(chatId: String, group: Boolean, ids: List<Long>): PinStep =
        remote.remove(chatId, group, ids).also { step ->
            if (step != PinStep.Done) return@also
            val was = groups.value[chatId] ?: return@also
            applied(chatId, was.copy(ids = was.ids.filter { it !in ids }))
        }

    /** Название группы закреплённых. */
    suspend fun rename(chatId: String, group: Boolean, title: String): PinStep =
        remote.rename(chatId, group, title).also { step ->
            if (step == PinStep.Done) applied(chatId, (groups.value[chatId] ?: PinBoard("", emptyList())).copy(title = title))
        }

    /** Событие `message.pinned` прежнего вида: номер или 0 — сняли. */
    fun applied(chatId: String, serverId: Long) {
        board.update { it + (chatId to serverId) }
        groups.update { all ->
            val was = all[chatId]
            all + (chatId to when {
                serverId == 0L -> PinBoard(was?.title.orEmpty(), emptyList())
                was == null -> PinBoard("", listOf(serverId))
                else -> was.copy(ids = listOf(serverId) + was.ids.filter { it != serverId })
            })
        }
    }

    /** Список с сервера или из события со списком (СЦ12). */
    fun applied(chatId: String, pins: PinBoard) {
        groups.update { it + (chatId to pins) }
        board.update { it + (chatId to (pins.ids.firstOrNull() ?: 0L)) }
    }

    /** Удалили у всех — закреплённое открепляется само: сервер снял его в самом удалении. */
    fun deleted(chatId: String, serverId: Long) {
        board.update { if (it[chatId] == serverId) it + (chatId to 0L) else it }
        groups.update { all -> all[chatId]?.let { b -> all + (chatId to b.copy(ids = b.ids - serverId)) } ?: all }
    }
}

/** Закреплённые группой (СЦ12): название (пусто — «Группа сообщений») и номера, новые первыми; до 20. */
data class PinBoard(val title: String, val ids: List<Long>) {
    companion object {
        const val MAX: Int = 20
    }
}

/** Ответ сервера на закрепление и снятие. */
sealed interface PinStep {
    data object Done : PinStep
    /** `not_moderator` — в группе закрепляет модератор и выше; `message_not_pinnable` — сообщения нет. */
    data class Refused(val code: String) : PinStep
    data object Offline : PinStep
}

/** Порт к серверу: `…/chats/{id}/pin` и `…/groups/{id}/pin`; список — `…/pins` (СЦ12). Реализуется `core-network`. */
interface RemotePins {
    /** Номер закреплённого, 0 — ничего; `null` — сеть или отказ. */
    suspend fun read(chatId: String, group: Boolean): Long?
    suspend fun set(chatId: String, group: Boolean, serverId: Long): PinStep
    suspend fun clear(chatId: String, group: Boolean): PinStep

    /** Список (СЦ12); по умолчанию — из одного закреплённого (старый сервер). */
    suspend fun list(chatId: String, group: Boolean): PinBoard? =
        read(chatId, group)?.let { PinBoard("", if (it > 0) listOf(it) else emptyList()) }

    suspend fun add(chatId: String, group: Boolean, ids: List<Long>, replace: Boolean): PinStep =
        ids.firstOrNull()?.let { set(chatId, group, it) } ?: PinStep.Done

    suspend fun remove(chatId: String, group: Boolean, ids: List<Long>): PinStep = clear(chatId, group)

    suspend fun rename(chatId: String, group: Boolean, title: String): PinStep = PinStep.Refused("old_server")
}
