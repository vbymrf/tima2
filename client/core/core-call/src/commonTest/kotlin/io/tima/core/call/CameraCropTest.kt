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
        assertEquals(CameraCrop.FULL, CameraCrop(1.1f, 0.01f, 0f).farther(), "с 1,1× «−» возвращает весь кадр без сдвига")
        assertEquals("1.3", CameraCrop.label(steps[3]))
        assertEquals("2", CameraCrop.label(steps[10]))
    }

    @Test
    fun на_весь_кадр_стрелки_погашены() {
        assertFalse(CameraCrop.FULL.canMove(1, 0))
        assertFalse(CameraCrop.FULL.canMove(0, -1))
        assertEquals(CameraCrop.FULL, CameraCrop.FULL.moved(1, 1))
    }

    @Test
    fun сдвиг_во_все_стороны_не_дальше_края() {
        // 2× — видна половина, середина ходит на четверть кадра в каждую сторону; шаг — 1/20 видимой части,
        // до края — десять нажатий (было два).
        var c = CameraCrop(2f).moved(1, 0)
        assertEquals(0.025f, c.x, 0.0001f)
        repeat(30) { c = c.moved(1, 0) }
        assertEquals(0.25f, c.x, 0.0001f)
        assertFalse(c.canMove(1, 0), "у правого края стрелка вправо горит")
        assertTrue(c.canMove(-1, 0))
        repeat(30) { c = c.moved(0, -1) }
        assertEquals(-0.25f, c.y, 0.0001f)
        // «−» сужает предел — сдвиг возвращается внутрь.
        val back = c.farther()
        assertTrue(back.x <= (1f - 1f / back.zoom) / 2f + 0.0001f, "после «−» видимая часть вышла за край")
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
