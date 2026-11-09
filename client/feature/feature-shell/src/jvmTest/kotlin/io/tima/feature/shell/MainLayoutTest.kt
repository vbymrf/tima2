package io.tima.feature.shell

import kotlin.test.Test
import kotlin.test.assertEquals

/** Раскладка главного окна (ПЛАН-(ВО) ВО4): запись, порча, перестановка. */
class MainLayoutTest {

    @Test
    fun запись_туда_и_обратно() {
        val areas = listOf(Area.Nav, Area.Content, Area.Sections, Area.Tabs)
        assertEquals(areas, layoutOf(layoutCode(areas)))
    }

    @Test
    fun испорченная_запись_по_умолчанию() {
        assertEquals(DEFAULT_LAYOUT, layoutOf(null))
        assertEquals(DEFAULT_LAYOUT, layoutOf("tabs,tabs,content,nav"), "повтор")
        assertEquals(DEFAULT_LAYOUT, layoutOf("tabs,content"), "не хватает")
    }

    @Test
    fun перестановка_на_шаг() {
        assertEquals(listOf(Area.Tabs, Area.Content, Area.Sections, Area.Nav), moved(DEFAULT_LAYOUT, Area.Sections, +1), "разделы под список")
        assertEquals(DEFAULT_LAYOUT, moved(DEFAULT_LAYOUT, Area.Tabs, -1), "выше первой — некуда")
    }
}
