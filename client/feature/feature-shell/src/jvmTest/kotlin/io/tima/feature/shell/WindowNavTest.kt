package io.tima.feature.shell

import kotlin.test.Test
import kotlin.test.assertEquals

/** Панель перехода (ПЛАН-(ВО) ВО2, 2а): четыре окна — «Медиа» выключено, его место у «Страницы»; временных нет. */
class WindowNavTest {

    @Test
    fun окна_панели_перехода() {
        assertEquals(listOf(Window.Phone, Window.Social, Window.Activity, Window.Page), navWindows())
    }

    @Test
    fun метки_кнопок() {
        assertEquals("nav:phone", navTag(Window.Phone))
        assertEquals("nav:page", navTag(Window.Page))
    }
}
