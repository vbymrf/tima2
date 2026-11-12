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
    fun увеличение_с_начала_звонка_не_выходит_за_край() {
        // Заказчик 2026-11-10: 1.3 — 12, но до края 11,5 % — стало 11; 1.4, 1.5, 1.6 — 14, 15, 18 внутри.
        assertEquals(11, CallLookKeys.shift(1.3f))
        assertEquals(14, CallLookKeys.shift(1.4f))
        assertEquals(15, CallLookKeys.shift(1.5f))
        assertEquals(18, CallLookKeys.shift(1.6f))
        for (z in CallLookKeys.ZOOMS.drop(1)) {
            for (hand in CallHand.entries) {
                val c = CallLookKeys.preset(z, hand)
                assertFalse(c.beyond, "увеличение $z, рука $hand: вышло за край")
                assertEquals(0 to 0, c.overPercent)
            }
        }
    }

    @Test
    fun рука_и_направления_увеличения() {
        // Вниз всегда (на экране «−»), правая рука — влево («−»), левая — вправо, обе — по середине.
        assertEquals(-14 to -14, CallLookKeys.preset(1.4f, CallHand.Right).shiftPercent)
        assertEquals(14 to -14, CallLookKeys.preset(1.4f, CallHand.Left).shiftPercent)
        assertEquals(0 to -18, CallLookKeys.preset(1.6f, CallHand.Both).shiftPercent)
        assertEquals(CameraCrop.FULL, CallLookKeys.preset(1f, CallHand.Right))
        assertEquals(1.5f, CallLookKeys.zoom(mapOf(CallLookKeys.ZOOM to "1.5")))
        assertEquals(1f, CallLookKeys.zoom(mapOf(CallLookKeys.ZOOM to "2")), "ступени вне списка нет")
        assertEquals(CallHand.Left, CallLookKeys.hand(mapOf(CallLookKeys.HAND to "left")))
        assertEquals(CallHand.Right, CallLookKeys.hand(emptyMap()))
    }

    @Test
    fun в_зеркале_влево_это_вправо_у_уходящего() {
        // Своё окошко с передней камеры — зеркалом (заказчик 2026-11-10): «◀» на экране двигает уходящий кадр вправо.
        val sent = CameraCrop(2f)
        val next = sent.mirrored(true).moved(-1, 0).mirrored(true)
        assertTrue(next.x > 0f, "в зеркале «◀» сдвинула уходящий кадр влево")
        assertEquals(-12 to -14, CameraCrop(1.4f, 0.12f, 0.14f).mirrored(true).shiftPercent)
        assertEquals(sent, sent.mirrored(false))
    }

    @Test
    fun адаптация_по_умолчанию_выключена() {
        assertFalse(CallLookKeys.adapt(emptyMap()))
        assertTrue(CallLookKeys.adapt(mapOf(CallLookKeys.ADAPT to "1")))
    }

    @Test
    fun пк_увеличение_до_двух_и_не_за_край() {
        // Заказчик 2026-11-12: на ПК — кнопки тестера, «Не пускать» за край, ступени 1×–2×; выбор помнится.
        var c = CameraCrop.FULL
        repeat(15) { c = c.closerInside() }
        assertEquals(2f, c.zoom, 1e-4f)
        assertFalse(c.canCloserInside)
        repeat(100) { c = c.movedInside(1, -1) }
        assertFalse(c.beyond, "ушло за край кадра")
        assertFalse(c.canMoveInside(1, 0))
        assertTrue(c.canMoveInside(-1, 0))
        assertEquals(0.25f, c.x, 1e-4f)
        val saved = mapOf(CallLookKeys.PC_CROP to CallLookKeys.pcCropCode(c))
        assertEquals(c, CallLookKeys.pcCrop(saved))
        assertEquals(CameraCrop.FULL, CallLookKeys.pcCrop(emptyMap()))
        assertEquals(CameraCrop(2f, 0.25f, 0.25f), CallLookKeys.pcCrop(mapOf(CallLookKeys.PC_CROP to "3;0.9;0.9")), "чужое — в пределы")
    }
}
