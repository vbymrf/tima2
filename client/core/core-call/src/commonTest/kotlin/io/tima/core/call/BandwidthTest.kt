package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Правила полосы — ПЛАН-(ПЗ)-ПОЛОСЫ-ЗВОНКА.md, решения заказчика 2026-10-15. */
class BandwidthTest {

    @Test
    fun среднее_не_раньше_чем_набралось_окно() {
        val w = BandWindow()
        w.add(0, 100.0)
        w.add(3_000, 100.0)
        assertNull(w.average(3_000))
        w.add(6_000, 400.0)
        assertEquals(200.0, w.average(6_000))
        // Старое выпадает из окна: остаются 3, 6, 9 и 12 с.
        w.add(9_000, 400.0)
        w.add(12_000, 400.0)
        assertEquals(325.0, w.average(12_000))
    }

    @Test
    fun получатель_сообщает_вниз_сразу_вверх_не_чаще_10_с() {
        val t = ReceiveTeller(ceilingKbps = 800)
        // Приём с запасом — ничего не говорим.
        assertNull(t.next(0, 1_200.0, serverPaused = false, incoming = null))
        // Ниже потолка со звуком (850) — сказать сразу, с округлением до 50.
        assertEquals(620 / 50 * 50, t.next(1_000, 620.0, false, null)!!.kbps)
        // Колебание меньше 20 % — молчим.
        assertNull(t.next(2_000, 560.0, false, null))
        // Упало на 20 % и больше — сразу.
        assertEquals(450, t.next(3_000, 470.0, false, null)!!.kbps)
        // Выросло на 30 %, но 10 с не прошло — молчим; прошло — говорим.
        assertNull(t.next(5_000, 700.0, false, null))
        assertEquals(700, t.next(13_000, 700.0, false, null)!!.kbps)
        // Приём с запасом над потолком — ограничение снято.
        assertNull(t.next(16_000, 1_100.0, false, null))
        assertEquals(null, t.next(24_000, 1_100.0, false, null)!!.kbps)
    }

    @Test
    fun пауза_сервера_шаг_вниз_даже_без_оценки() {
        val t = ReceiveTeller(ceilingKbps = 800)
        val c = t.next(0, estimate = null, serverPaused = true, incoming = 900.0)
        assertNotNull(c)
        // Без оценки: 80 % от потолка со звуком (850 → 680 → 650).
        assertEquals(650, c.kbps)
        // Пауза держится — повторно не говорим, пока она не началась заново.
        assertNull(t.next(3_000, null, true, 900.0))
        assertNull(t.next(6_000, null, false, 900.0))
        assertEquals(500, t.next(9_000, null, true, 900.0)!!.kbps)
    }

    @Test
    fun отправитель_понижает_сразу_повышает_ступенями() {
        val s = SendLimit(ceilingKbps = 800)
        assertNull(s.next(0, null))
        assertEquals(400, s.next(1_000, 450))
        assertNull(s.next(2_000, 450))
        // Снято — +25 % раз в 10 с до потолка.
        assertNull(s.next(5_000, null))
        assertEquals(500, s.next(11_000, null))
        assertNull(s.next(15_000, null))
        assertEquals(625, s.next(21_000, null))
        assertEquals(781, s.next(31_000, null))
        assertEquals(800, s.next(41_000, null))
        assertNull(s.next(51_000, null))
    }

    @Test
    fun отправитель_не_ниже_50_и_не_выше_потолка() {
        val s = SendLimit(ceilingKbps = 800)
        assertEquals(50, s.next(0, 60))
        assertEquals(800, s.next(1_000, 5_000))
    }

    @Test
    fun своё_видео_выключается_ниже_150_и_включается_от_250() {
        fun band(video: Double?, all: Double? = null, arriving: Boolean = true, measurable: Boolean = true) =
            IncomingBand(video, all, arriving, measurable)
        assertEquals(OwnVideoGuard.Decision.Pause, OwnVideoGuard.decide(false, band(140.0)))
        assertNull(OwnVideoGuard.decide(false, band(160.0)))
        // Мерить нельзя — решение не меняется.
        assertNull(OwnVideoGuard.decide(false, band(10.0, measurable = false)))
        // Видео ни у кого нет — по одному звуку не выключаем.
        assertNull(OwnVideoGuard.decide(false, band(0.0, all = 40.0, arriving = false)))
        // Выключено нами: 200 — мало, 250 — включаем.
        assertNull(OwnVideoGuard.decide(true, band(200.0)))
        assertEquals(OwnVideoGuard.Decision.Resume, OwnVideoGuard.decide(true, band(250.0)))
        // Видео нет, звук меньше 250 — оснований для включения нет.
        assertNull(OwnVideoGuard.decide(true, band(0.0, all = 100.0, arriving = false)))
        assertEquals(OwnVideoGuard.Decision.Resume, OwnVideoGuard.decide(true, band(0.0, all = 260.0, arriving = false)))
    }

    @Test
    fun повторная_подача_через_15_30_60_и_дальше_раз_в_минуту() {
        val r = Resubscribe()
        r.paused("a", 0)
        assertEquals(emptyList(), r.due(14_000))
        assertEquals(1, r.due(15_000).single().attempt)
        assertEquals(emptyList(), r.due(29_000))
        assertEquals(2, r.due(30_000).single().attempt)
        assertEquals(emptyList(), r.due(59_000))
        assertEquals(3, r.due(60_000).single().attempt)
        assertEquals(emptyList(), r.due(119_000))
        assertEquals(4, r.due(120_000).single().attempt)
        assertEquals(5, r.due(180_000).single().attempt)
        r.resumed("a")
        assertEquals(emptyList(), r.due(500_000))
    }

    @Test
    fun оценка_в_журнал_при_смене_больше_30_и_в_паузе_раз_в_15_с() {
        val n = EstimateNotes()
        assertEquals(800.0, n.next(0, 800.0, paused = false)!!.kbps)
        assertNull(n.next(3_000, 900.0, false))
        assertEquals(1_100.0, n.next(6_000, 1_100.0, false)!!.kbps)
        // Пауза началась — пишем; в паузе раз в 15 с.
        assertNotNull(n.next(9_000, 1_100.0, true))
        assertNull(n.next(12_000, 1_100.0, true))
        assertNotNull(n.next(24_000, 1_100.0, true))
        // Повторная подача — всегда.
        assertNotNull(n.next(25_000, 1_100.0, true, forced = true))
        // Оценки нет — один раз.
        assertNotNull(n.next(28_000, null, true))
        assertNull(n.next(31_000, null, true))
    }
}
