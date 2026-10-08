package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.network.EventStreamProtocol
import io.tima.core.network.StateRow
import io.tima.core.network.StatesOverHttp
import io.tima.domain.chat.Settings
import io.tima.feature.chat.ChatReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * «Доставлено», «прочитано», «печатает», «в сети» — только личные переписки
 * (ПЛАН-(ОП)-ОТМЕТОК-И-ПРИСУТСТВИЯ).
 *
 * ── ЧТО ПРИХОДИТ ────────────────────────────────────────────────────────────
 *
 * Сервер даёт сигнал `state.poke {rev}`; здесь забирается лента после своего номера, и каждая
 * строка заменяет прежнюю правду: отметки переписки (хранятся — переживают перезапуск),
 * «печатает» и «в сети» (живут в памяти: через минуту они ложь).
 *
 * ── ЧТО УХОДИТ ──────────────────────────────────────────────────────────────
 *
 * Свои кадры живым каналом — [frames]: «печатаю», «на экране», «смотрю переписку».
 * Канала нет — кадры теряются, и при подъёме канала текущее «на экране» и «смотрю»
 * повторяются заново ([top] с −1). «Прочитано» — запросом, оно обязано дойти.
 */
class LiveStates(
    private val api: StatesOverHttp,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val now: () -> Long = { msNow() },
) {
    /** Свои кадры живому каналу. Старые вытесняются: устаревшее «печатает» хуже потерянного. */
    val frames: Channel<String> = Channel(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _receipts = MutableStateFlow<Map<String, ChatReceipt>>(emptyMap())

    /** Отметки своих сообщений по переписке. */
    val receipts: StateFlow<Map<String, ChatReceipt>> = _receipts.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Кто печатает: переписка → до какого момента (свои часы). */
    val typing: StateFlow<Map<String, Long>> = _typing.asStateFlow()

    private val _presence = MutableStateFlow<Map<String, Seen>>(emptyMap())

    /** «В сети» собеседников, чью переписку смотрю. */
    val presence: StateFlow<Map<String, Seen>> = _presence.asStateFlow()

    /** «В сети» до [untilMs] (свои часы) или был(а) в [lastSeenMs]. */
    data class Seen(val online: Boolean, val untilMs: Long, val lastSeenMs: Long) {
        fun onlineAt(nowMs: Long): Boolean = online && untilMs > nowMs
    }

    private val protocol = EventStreamProtocol()
    private val lock = Mutex()
    private var rev: Long = -1
    private var visible = false
    private var visibleJob: Job? = null
    private var watched: String? = null
    private var watchJob: Job? = null
    private var typingTo: Pair<String, String>? = null
    private var typingSentAt = 0L
    private var typingStop: Job? = null

    init {
        scope.launch {
            val saved = runCatching { settings.all().first() }.getOrDefault(emptyMap())
            rev = saved[REV]?.toLongOrNull() ?: 0
            _receipts.value = saved.filterKeys { it.startsWith(RECEIPT) }.mapNotNull { (k, v) ->
                val (d, r) = v.split(':').mapNotNull { it.toLongOrNull() }.takeIf { it.size == 2 } ?: return@mapNotNull null
                k.removePrefix(RECEIPT) to ChatReceipt(d, r)
            }.toMap()
        }
    }

    /**
     * Сигнал ленты или подъём канала (−1): забрать изменившееся после своего номера. На подъёме
     * — сперва повторить «на экране» и «смотрю»: прежнее соединение унесло их с собой.
     */
    suspend fun top(signal: Long) {
        if (signal < 0) {
            if (visible) frames.trySend(protocol.presenceFrame(true))
            watched?.let { frames.trySend(protocol.watchFrame(it, true)) }
        }
        lock.withLock {
            if (signal in 0..rev) return
            val page = api.page(maxOf(rev, 0)) ?: return
            val shift = if (page.nowMs > 0) now() - page.nowMs else 0
            for (row in page.rows) apply(row, shift)
            if (page.rev > rev) {
                rev = page.rev
                runCatching { settings.put(REV, rev.toString()) }
            }
        }
    }

    private suspend fun apply(row: StateRow, shift: Long) {
        when (row) {
            is StateRow.Receipt -> {
                val was = _receipts.value[row.chatId]
                val fresh = ChatReceipt(
                    deliveredMs = maxOf(was?.deliveredMs ?: 0, row.deliveredMs),
                    readMs = maxOf(was?.readMs ?: 0, row.readMs),
                )
                if (fresh != was) {
                    _receipts.value = _receipts.value + (row.chatId to fresh)
                    runCatching { settings.put(RECEIPT + row.chatId, "${fresh.deliveredMs}:${fresh.readMs}") }
                    Journal.note(LogCode.STATES, "отметки переписки", "чат" to row.chatId.take(8), "доставлено" to fresh.deliveredMs, "прочитано" to fresh.readMs)
                }
            }
            is StateRow.Typing -> {
                val until = if (row.untilMs > 0) row.untilMs + shift else 0
                _typing.value = if (until > now()) _typing.value + (row.chatId to until) else _typing.value - row.chatId
            }
            is StateRow.Presence -> {
                _presence.value = _presence.value + (row.userId to Seen(row.online, row.untilMs + shift, row.lastSeenMs))
            }
        }
    }

    /** Приложение на экране или свёрнуто. На экране — повтор раз в 30 с: иначе сервер решит, что ушёл. */
    fun visible(on: Boolean) {
        if (on == visible) return
        visible = on
        visibleJob?.cancel()
        frames.trySend(protocol.presenceFrame(on))
        if (on) {
            visibleJob = scope.launch {
                while (isActive) {
                    delay(PRESENCE_EVERY_MS)
                    frames.trySend(protocol.presenceFrame(true))
                }
            }
        }
    }

    /** Открыта личная переписка с [userId]; `null` — закрыта. Пока открыта — подтверждать раз в минуту. */
    fun watch(userId: String?) {
        if (userId == watched) return
        watched?.let { frames.trySend(protocol.watchFrame(it, false)) }
        watchJob?.cancel()
        watched = userId
        if (userId == null) return
        frames.trySend(protocol.watchFrame(userId, true))
        watchJob = scope.launch {
            while (isActive) {
                delay(WATCH_EVERY_MS)
                frames.trySend(protocol.watchFrame(userId, true))
            }
        }
    }

    /** Набирают текст в личной переписке [chatId] с [to]: «печатаю» не чаще раза в 5 с; 5 с тишины — «перестал». */
    fun typed(chatId: String, to: String) {
        val at = now()
        if (typingTo != (chatId to to) || at - typingSentAt >= TYPING_EVERY_MS) {
            typingTo?.takeIf { it != (chatId to to) }?.let { (c, t) -> frames.trySend(protocol.typingFrame(c, t, false)) }
            typingTo = chatId to to
            typingSentAt = at
            frames.trySend(protocol.typingFrame(chatId, to, true))
        }
        typingStop?.cancel()
        typingStop = scope.launch {
            delay(TYPING_EVERY_MS)
            stopTyping()
        }
    }

    /** Отправили, стёрли или ушли из переписки — «перестал». */
    fun stopTyping() {
        typingStop?.cancel()
        val (chatId, to) = typingTo ?: return
        typingTo = null
        typingSentAt = 0
        frames.trySend(protocol.typingFrame(chatId, to, false))
    }

    private val readSent = mutableMapOf<String, Long>()

    /** Прочитал сообщения собеседника в [chatId], написанные до [upToMs]. Повтор того же не шлётся. */
    fun read(chatId: String, upToMs: Long) {
        if (upToMs <= 0 || upToMs <= (readSent[chatId] ?: 0)) return
        readSent[chatId] = upToMs
        scope.launch {
            if (!api.read(chatId, upToMs)) {
                // Не дошло — пусть следующий показ переписки попробует снова.
                readSent.remove(chatId)
                Journal.trouble(LogCode.STATES, "отметка «прочитано» не дошла", "чат" to chatId.take(8))
            }
        }
    }

    private companion object {
        const val REV = "states.rev"
        const val RECEIPT = "states.receipt."
        const val PRESENCE_EVERY_MS = 30_000L
        const val WATCH_EVERY_MS = 60_000L
        const val TYPING_EVERY_MS = 5_000L
    }
}
