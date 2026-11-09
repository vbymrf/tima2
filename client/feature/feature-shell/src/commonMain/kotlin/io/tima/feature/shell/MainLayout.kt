package io.tima.feature.shell

import androidx.compose.runtime.compositionLocalOf

/**
 * Раскладка главного окна (ПЛАН-(ВО) ВО4, Р2): шапка всегда первая; вкладки, разделы и панель перехода ставятся
 * выше или ниже содержимого в любом порядке. Содержимое — неподвижная метка списка.
 */
enum class Area(val code: String) {
    Tabs("tabs"),
    Sections("sections"),
    Content("content"),
    Nav("nav"),
}

/** По умолчанию — как было: вкладки, разделы, содержимое, панель перехода внизу. */
val DEFAULT_LAYOUT: List<Area> = listOf(Area.Tabs, Area.Sections, Area.Content, Area.Nav)

/**
 * Раскладка из настройки — коды через запятую. Каждая область ровно один раз; что-то не так — по умолчанию:
 * испорченная запись не должна оставить окно без вкладок.
 */
fun layoutOf(stored: String?): List<Area> {
    val areas = stored.orEmpty().split(',').mapNotNull { code -> Area.entries.firstOrNull { it.code == code.trim() } }
    return if (areas.size == Area.entries.size && areas.toSet().size == Area.entries.size) areas else DEFAULT_LAYOUT
}

/** Запись раскладки в настройку. */
fun layoutCode(areas: List<Area>): String = areas.joinToString(",") { it.code }

/** Подвинуть область на шаг вверх (`-1`) или вниз (`+1`); содержимое — тоже метка, его двигать можно. */
fun moved(areas: List<Area>, area: Area, step: Int): List<Area> {
    val at = areas.indexOf(area)
    val to = at + step
    if (at < 0 || to !in areas.indices) return areas
    return areas.toMutableList().apply { add(to, removeAt(at)) }
}

/** Раскладка для каркаса окна. */
val LocalMainLayout = compositionLocalOf { DEFAULT_LAYOUT }
