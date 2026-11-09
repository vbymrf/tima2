package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Режим адаптации изображения (ПЛАН-(ЗН) ЗН5): ступени, сдвиг, край кадра и поворот камеры. */
class CameraCropTest {

    @Test
    fun ступени_через_десятую_до_трёх_и_обратно() {
        // Заказчик 2026-11-10: шаг мельче — от 1× до 3× двадцать нажатий.
        val steps = generateSequence(CameraCrop.FULL) { it.closer().takeIf { next -> next != it } }.map { it.zoom }.toList()
        assertEquals(21, steps.size, steps.toString())
        assertEquals(1.1f, steps[1], 0.0001f)
        assertEquals(3f, steps.last(), 0.0001f, "ответ заказчика 2: до 3×")
        assertFalse(CameraCrop(3f).canCloser)
        assertEquals(CameraCrop.FULL, CameraCrop(1.1f).farther(), "с 1,1× без сдвига «−» возвращает весь кадр")
        assertEquals("1.3", CameraCrop.label(steps[3]))
        assertEquals("2", CameraCrop.label(steps[10]))
    }

    @Test
    fun сдвиг_и_при_весь_кадр_и_за_край() {
        // Вариант «а» заказчика 2026-11-10: сдвиг не упирается в край кадра — за ним поле; можно и при 1×.
        val one = CameraCrop.FULL.moved(1, 0)
        assertEquals(0.05f, one.x, 0.0001f)
        assertFalse(one.full)
        var c = CameraCrop(2f)
        repeat(40) { c = c.moved(1, 0) }
        assertEquals(CameraCrop.MAX_SHIFT, c.x, 0.0001f, "середина видимой части доходит до края кадра")
        assertFalse(c.canMove(1, 0))
        assertTrue(c.beyond)
        repeat(40) { c = c.moved(0, -1) }
        assertEquals(-CameraCrop.MAX_SHIFT, c.y, 0.0001f)
        assertEquals(1.9f, c.farther().zoom, 0.0001f)
        assertEquals(c.x, c.farther().x, 0.0001f, "«−» сдвиг не теряет")
    }

    @Test
    fun цифры_сдвига_и_за_краем() {
        // При 2× до края — 25 % кадра; сдвиг на 30 % вправо — 5 % за краем. Вверх — плюс.
        val c = CameraCrop(2f, x = 0.3f, y = -0.25f)
        assertEquals(30 to 25, c.shiftPercent)
        assertEquals(5 to 0, c.overPercent)
        assertEquals("+11", CameraCrop.signed(11))
        assertEquals("\u221225", CameraCrop.signed(-25))
        assertEquals("0", CameraCrop.signed(0))
    }

    @Test
    fun за_краем_видимая_часть_ложится_на_своё_место() {
        // 2× и середина у правого края: видна правая четверть кадра, и она занимает левую половину выхода.
        val cut = CameraCrop(2f, x = 0.5f).cut(640, 480)
        assertEquals(CameraCrop.Region(480, 120, 160, 240), cut.src)
        assertEquals(CameraCrop.Region(0, 0, 320, 480), cut.dst)
    }

    @Test
    fun обрезка_посередине_и_со_сдвигом() {
        val r = CameraCrop(2f).region(640, 480)
        assertEquals(CameraCrop.Region(160, 120, 320, 240), r)
        val right = CameraCrop(2f, x = 0.25f).region(640, 480)
        assertEquals(320, right.left, "сдвиг вправо до края — правая половина")
    }

    @Test
    fun поворот_камеры_переводит_сдвиг_в_оси_буфера() {
        // Телефон снимает боком: буфер 640×480 показывается повёрнутым на 90 по часовой. Сдвиг
        // вниз на экране — вправо по буферу, сдвиг вправо на экране — вверх по буферу.
        val down = CameraCrop(2f, y = 0.25f).region(640, 480, rotation = 90)
        assertEquals(320, down.left)
        val right = CameraCrop(2f, x = 0.25f).region(640, 480, rotation = 90)
        assertEquals(0, right.top)
        val right270 = CameraCrop(2f, x = 0.25f).region(640, 480, rotation = 270)
        assertEquals(240, right270.top)
    }

    @Test
    fun вид_по_умолчанию_новый_адаптация_выключена() {
        assertTrue(CallLookKeys.fresh(emptyMap()), "ответ заказчика 5: по умолчанию новый")
        assertFalse(CallLookKeys.fresh(mapOf(CallLookKeys.LOOK to "old")))
        assertFalse(CallLookKeys.adapt(emptyMap()))
        assertTrue(CallLookKeys.adapt(mapOf(CallLookKeys.ADAPT to "1")))
    }
}
