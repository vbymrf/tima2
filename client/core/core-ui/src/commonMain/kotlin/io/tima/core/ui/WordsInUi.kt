package io.tima.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import io.tima.core.words.RussianWords
import io.tima.core.words.Words

/**
 * Раздача словаря экранам (ПЛАН-ЯЗЫКА, Я-D).
 *
 * Сам словарь живёт в `core-words` и Compose не знает вовсе: им пользуется store, который
 * не рисует. Здесь — только доставка до того, кто рисует.
 */

/**
 * Раздача словаря. Умолчание — русский: приложение обязано говорить даже там, где язык
 * ещё не выбран.
 */
val LocalWords: ProvidableCompositionLocal<Words> = staticCompositionLocalOf { RussianWords }

/** Короткий доступ: `Tima.words.comments.thread`. */
val Tima.words: Words
    @Composable get() = LocalWords.current
