package io.tima.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Палитра сообщений: имя цвета → светлая тема, тёмная тема (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §13,
 * ADR-0044 §4). В разметке — только имена (`MarkupRules.PALETTE`); незнакомое имя — свойства нет.
 *
 * `*-soft` — подложки: полупрозрачные, ложатся на пузырь и не спорят с текстом. Яркие — для текста
 * и линий; в тёмной теме светлее, чтобы держать контраст.
 */
object MessagePalette {
    private val light = mapOf(
        "text" to Color(0xFF000000),
        "surface" to Color(0xFFFFFFFF),
        "accent" to Color(0xFF5E9A1F),
        "accent-soft" to Color(0x338AC44A),
        "amber" to Color(0xFFB37F00),
        "amber-soft" to Color(0x33FEBB02),
        "green" to Color(0xFF2E8A00),
        "green-soft" to Color(0x333BB300),
        "blue" to Color(0xFF1F63C2),
        "blue-soft" to Color(0x332F80ED),
        "red" to Color(0xFFC63A24),
        "red-soft" to Color(0x33E5533D),
        "grey" to Color(0xFF6B6B6B),
        "grey-soft" to Color(0x1F000000),
    )
    private val dark = mapOf(
        "text" to Color(0xFFFFFFFF),
        "surface" to Color(0xFF1E1E1E),
        "accent" to Color(0xFFA6D873),
        "accent-soft" to Color(0x4D8AC44A),
        "amber" to Color(0xFFFFCE4A),
        "amber-soft" to Color(0x4DFEBB02),
        "green" to Color(0xFF7AD94A),
        "green-soft" to Color(0x4D3BB300),
        "blue" to Color(0xFF7FB2F5),
        "blue-soft" to Color(0x4D2F80ED),
        "red" to Color(0xFFFF8A78),
        "red-soft" to Color(0x4DE5533D),
        "grey" to Color(0xFFB0B0B0),
        "grey-soft" to Color(0x29FFFFFF),
    )

    /** Цвет по имени в теме; незнакомое — `null`. */
    fun color(name: String, darkTheme: Boolean): Color? = (if (darkTheme) dark else light)[name]

    /** Все имена — для проверки, что таблица совпадает с `MarkupRules.PALETTE`. */
    val names: Set<String> get() = light.keys
}

/** Цвет палитры в нынешней теме; `null` у пустого и незнакомого имени. */
@Composable
@ReadOnlyComposable
fun paletteColor(name: String?): Color? =
    name?.let { MessagePalette.color(it, Tima.colors.surface.luminance() < 0.5f) }
