package io.tima.feature.chat

import io.tima.domain.chat.CallHistory
import io.tima.domain.chat.CallLog
import io.tima.domain.chat.CallRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Состояние вкладки «Звонки» — Ж2.
 *
 * ── ЧИТАЕТСЯ КАК ПЕРЕПИСКА, НАПОЛНЯЕТСЯ ИНАЧЕ ───────────────────────────────
 *
 * Список приходит **потоком из базы**, как у [ChatsStore] и [BookStore]: экран показывает
 * что есть, ничего не ожидая. Это одинаково для всего приложения, и журнал шаблона не
 * изобретает.
 *
 * А вот наполняется он по-другому, и это стоит знать. Переписка приезжает кадрами
 * событий и расшифровывается — сервер её прочитать не может вовсе. Журнал звонков сервер
 * знает сам, поэтому здесь не приёмник, а **страница**: сходили, записали, показали.
 * Потерянная переписка — потеря навсегда, потерянный журнал — один запрос.
 *
 * ── ОБНОВЛЕНИЕ ТОЛЬКО В ДВУХ МЕСТАХ ─────────────────────────────────────────
 *
 * При открытии вкладки и сразу после конца разговора. Второе важнее первого: на журнал
 * смотрят **сразу после звонка**, и строка, появляющаяся через минуту, выглядит потерей.
 *
 * Писать строку самим по ходу звонка было бы быстрее, но завело бы второго писателя: у
 * телефона и сервера разные часы, и после первого же обновления времена прыгнули бы.
 * Один запрос на звонок дешевле этого — их на звонок и так несколько.
 */
class CallsStore(
    private val log: CallLog,
    private val history: CallHistory,
    private val scope: CoroutineScope,
    /** Кто я: от этого зависит каждое слово строки и счётчик пропущенных. */
    private val me: String,
    /** Сколько строк держим на устройстве — настройка «Память и трафик». */
    private val keepRows: () -> Int = { KEEP_ROWS_DEFAULT },
    /** Сколько суток держим. Ноль — не ограничивать сроком. */
    private val keepDays: () -> Int = { KEEP_DAYS_DEFAULT },
    private val now: () -> Long,
) {
    private val _state = MutableStateFlow(CallsState())
    val state: StateFlow<CallsState> = _state.asStateFlow()

    init {
        scope.launch {
            log.page(keepRows()).collect { rows -> _state.value = _state.value.copy(records = rows) }
        }
        scope.launch {
            log.missed(me).collect { count -> _state.value = _state.value.copy(missed = count) }
        }
    }

    /** Открыли вкладку: сходить за свежим и погасить счётчик. */
    fun opened() {
        refresh()
        scope.launch { log.markSeen() }
    }

    /** Разговор кончился — строка о нём должна появиться сейчас, а не при следующем заходе. */
    fun callEnded() = refresh()

    private fun refresh() {
        scope.launch {
            val page = history.page(limit = PAGE)
            if (page == null) {
                // До сервера не дошли. Местное **не трогаем**: пустой ответ и отсутствие
                // ответа — разные вещи, и спутать их значит стереть журнал при первом же
                // походе в метро.
                _state.value = _state.value.copy(offline = true)
                return@launch
            }
            _state.value = _state.value.copy(offline = false)
            log.remember(page.records)
            // Уборка идёт после записи, а не до: иначе свежая страница пришла бы в базу,
            // где только что освободили место, и следующий проход убирал бы её же.
            val days = keepDays()
            log.prune(
                olderThanMs = if (days > 0) now() - days * DAY_MS else 0,
                keepRows = keepRows(),
            )
        }
    }

    /**
     * Долистали до низа — попросить ещё страницу.
     *
     * Продолжение идёт от самой старой известной строки. Сервер отбирает строго, поэтому
     * та же строка второй раз не придёт.
     */
    fun more() {
        val oldest = _state.value.records.minOfOrNull { it.createdAt } ?: return
        scope.launch {
            val page = history.page(beforeMs = oldest, limit = PAGE) ?: return@launch
            if (page.records.isNotEmpty()) log.remember(page.records)
        }
    }

    private companion object {
        const val PAGE = 30
        const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * Сколько журнала держит телефон по умолчанию.
         *
         * **Это не повторение серверного срока — на сервере его больше нет.** Строка
         * звонка это одни метаданные, а они не удаляются никогда. Здесь решается другое:
         * сколько места отдать копии того, что всегда можно доспросить. Тысяча строк при
         * нашей частоте — это годы.
         */
        const val KEEP_ROWS_DEFAULT = 1000
        const val KEEP_DAYS_DEFAULT = 365
    }
}

/**
 * Что видит вкладка «Звонки».
 *
 * @param offline до сервера не дошли. Не «звонков нет»: пустой список при этом означает
 *   лишь то, что журнал до этого телефона ещё не доезжал, и сказать об этом надо словами.
 */
data class CallsState(
    val records: List<CallRecord> = emptyList(),
    val missed: Int = 0,
    val offline: Boolean = false,
)
