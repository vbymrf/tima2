package io.tima.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Палитра сообщений: имя цвета → светлая тема, тёмная тема (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §13,
 * ADR-0044 §4). В разметке — только имена (`MarkupRules.PALETTE`); незнакомое имя — свойства нет.
 *
 * `*-soft` — подложки: полупрозрачные, ложатся на пузырь и не спорят с текстом. Яркие — для текста
 * и линий; в тёмной теме светлее, чтобы держать контраст.
 */
object MessagePalette {

    /** Цвет по имени в теме; незнакомое — `null`. */
    fun color(name: String, darkTheme: Boolean): Color? = (if (darkTheme) DarkMessagePalette else LightMessagePalette)[name]

    /** Все имена — для проверки, что таблица совпадает с `MarkupRules.PALETTE`. */
    val names: Set<String> get() = LightMessagePalette.keys
}

/** Цвет палитры в нынешней теме; `null` у пустого и незнакомого имени. */
@Composable
@ReadOnlyComposable
fun paletteColor(name: String?): Color? = name?.let { Tima.colors.messagePalette[it] }
