package io.tima.shared

import io.tima.core.network.NetworkState
import io.tima.core.network.NetworkWatch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Сколько ждать перед новым подъёмом канала — и можно ли не ждать вовсе (У17).
 *
 * ── ТРИ СЛУЧАЯ ──────────────────────────────────────────────────────────────
 *
 * ```
 * сети нет              →  не пробуем вовсе; ждём, пока появится
 * сеть появилась         →  пробуем СРАЗУ, не дожидаясь паузы
 * сеть есть, упал канал  →  пауза по LinkState, как и было
 * ```
 *
 * Первые два — ровно то, что ADR-0016 §4 обещал с июля и чего не было: «просыпаемся на
 * смену сети, а не по таймеру». Третий — третье состояние того же ADR: сеть есть, а
 * сервер недоступен. Его система не видит, и здесь по-прежнему решает `LinkState`.
 *
 * ── ПОТОЛОК ДАЖЕ ТАМ, ГДЕ «ЖДЁМ СЕТИ» ───────────────────────────────────────
 *
 * Пока сети нет, мы ждём события, а не таймера. Но ждём не бесконечно, а не дольше
 * [ceilingMs]. Прошивки бывают разные, и та, что однажды не пришлёт `onAvailable`,
 * оставила бы канал мёртвым навсегда. Раз в минуту без сети — это страховка, а не опрос:
 * одна попытка против тридцати, что были до У11.
 *
 * @return `true` — нас разбудила сеть (появилась или сменилась): счёт неудач надо
 *   сбросить, потому что прежние неудачи были про прежнюю сеть.
 */
internal suspend fun waitBeforeRetry(watch: NetworkWatch, pauseMs: Long, ceilingMs: Long): Boolean {
    val noNetwork = watch.state.value == NetworkState.LOST
    val limit = if (noNetwork) ceilingMs else pauseMs
    val woke = withTimeoutOrNull(limit) {
        merge(
            watch.switched,
            // `drop(1)`: нынешнее значение — не событие. Будит только ПЕРЕХОД в «есть».
            watch.state.drop(1).filter { it == NetworkState.AVAILABLE }.map { },
        ).first()
    }
    return woke != null
}

/**
 * Рвать ли живой канал прямо сейчас — У17.
 *
 * Канал может «жить», будучи мёртвым: после смены сети сокет остаётся привязан к
 * прежней и полуоткрыт. Закрывать его некому — пакета RST никто не шлёт. Это и стоило
 * двух суток на realme 2026-09-23, а до У17 это замечал только пинг, до 36 секунд.
 *
 * Возвращается, когда рвать пора: сеть сменилась либо пропала.
 */
internal suspend fun networkBrokeChannel(watch: NetworkWatch) {
    merge(
        watch.switched,
        // `drop(1)` и здесь: рвёт только ПЕРЕХОД в «нет». Канал, поднятый при устаревшем
        // «нет» (прошивка промолчала, потолок ожидания вышел, а сеть на деле есть),
        // иначе рвался бы в ту же секунду — и так по кругу.
        watch.state.drop(1).filter { it == NetworkState.LOST }.map { },
    ).first()
}

/** Канал разорван нами самими: сеть сменилась или пропала, и держать его незачем. */
internal class NetworkSwitched : Exception("сеть сменилась — канал поднимается заново")
