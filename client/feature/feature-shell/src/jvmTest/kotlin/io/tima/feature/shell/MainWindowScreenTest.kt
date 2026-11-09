package io.tima.feature.shell

import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/** «Главное окно» (ПЛАН-(ВО) ВО5): экран рисуется в обеих раскладках; снимки — `build/снимки`. */
class MainWindowScreenTest {

    @Test
    fun образец_по_умолчанию_и_переставленный() {
        for ((name, order, nav) in listOf(
            Triple("главное-окно-по-умолчанию", DEFAULT_LAYOUT, true),
            Triple("главное-окно-вкладки-внизу", listOf(Area.Sections, Area.Content, Area.Tabs, Area.Nav), false),
        )) {
            val shot = capture(name, 360, 900, dark = false) {
                MainWindowScreen(order = order, nav = nav, arrive = { it == "swipe" }, phone = true, onOrder = {}, onNav = {}, onArrive = { _, _ -> })
            }
            assertTrue(shot.pixels().any(), name)
        }
    }
}
