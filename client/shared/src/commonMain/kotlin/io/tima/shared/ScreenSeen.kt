package io.tima.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Смотрит ли человек на приложение прямо сейчас: окно на экране, замка пин-кода нет, окно звонка не
 * открыто. Ставит [App] — одно место для всех, кто решает «это увидели» (заказчик 2026-10-20).
 * Вне приложения (снимки, проверки) — смотрит.
 */
internal val LocalLooking = compositionLocalOf { true }

/**
 * Сколько смотреть, чтобы считалось «увидел» (заказчик 2026-10-20: сначала 1 с, затем 5 с, итог — «лучше на 3 секунды»). Мелькнувшее
 * не считается: приложение могли открыть кнопкой звонка — на долю секунды видна переписка, сразу
 * следом окно звонка.
 */
internal const val SEEN_AFTER_MS = 3_000L

/**
 * Увидел ли человек то, что сейчас на экране ([keys] — что именно: переписка, вкладка): смотрит
 * без перерыва [SEEN_AFTER_MS]. Отвернулся — сразу нет; вернулся — отсчёт заново.
 *
 * Отсюда — и «прочитано», и снятие уведомлений: одно правило на оба, иначе они разойдутся.
 */
@Composable
internal fun rememberSeen(vararg keys: Any?): Boolean {
    val looking = LocalLooking.current
    var seen by remember(*keys) { mutableStateOf(false) }
    LaunchedEffect(looking, *keys) {
        if (!looking) {
            seen = false
            return@LaunchedEffect
        }
        delay(SEEN_AFTER_MS)
        seen = true
    }
    return seen && looking
}
