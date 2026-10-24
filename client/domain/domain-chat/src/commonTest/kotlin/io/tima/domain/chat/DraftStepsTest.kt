package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Шаги черновика и сигнал о проскочившей копии (заказчик 2026-10-24, 1#, 3#): виды и числа, без содержимого. */
class DraftStepsTest {

    private val text = ComposedDoc(listOf(ComposedItem.Text("привет")))
    private val video = ComposedWidget.Video("https://x.ru/секрет.mp4", "Отпуск")

    @Test
    fun шаг_виджет_куски_набор_без_содержимого() {
        val withVideo = text.copy(items = text.items + ComposedItem.Widget(video))
        val step = DraftSteps.step(text, withVideo)!!
        assertEquals("+video, кусков 1→2", step)
        assertFalse("секрет" in step || "Отпуск" in step || "привет" in step, "ни адреса, ни названия, ни текста")
        assertEquals("набор", DraftSteps.step(text, ComposedDoc(listOf(ComposedItem.Text("привет!")))))
        assertEquals("−video, кусков 2→1", DraftSteps.step(withVideo, text))
        assertNull(DraftSteps.step(text, text))
    }

    @Test
    fun набор_подряд_одной_строкой_и_не_больше_200() {
        var lines = DraftSteps.append(emptyList(), "10:00:01 набор")
        lines = DraftSteps.append(lines, "10:00:02 набор")
        lines = DraftSteps.append(lines, "10:00:03 набор")
        assertEquals(listOf("10:00:03 набор ×3"), lines)
        lines = DraftSteps.append(lines, "10:00:04 +video")
        lines = DraftSteps.append(lines, "10:00:05 набор")
        assertEquals(3, lines.size)
        var many = emptyList<String>()
        repeat(250) { many = DraftSteps.append(many, "10:00:00 +emoji $it") }
        assertEquals(DraftSteps.LIMIT, many.size)
        assertEquals("10:00:00 +emoji 249", many.last())
    }

    @Test
    fun отчёт_черновики_по_номерам() {
        val r = DraftSteps.report(listOf("10:00:01 +video", "", "10:01:00 набор ×3"))
        assertTrue("черновик 1:\n10:00:01 +video" in r)
        assertTrue("черновик 2:\n10:01:00 набор ×3" in r)
        assertEquals("", DraftSteps.report(emptyList()))
    }

    @Test
    fun копия_того_же_адреса_за_2_секунды() {
        val w = CopyWatch()
        assertFalse(w.seen(video, 1_000))
        assertTrue(w.seen(video.copy(description = "Отпуск (2)"), 1_400), "описание разведено — копия та же")
        assertFalse(w.seen(video, 5_000), "через 3,6 с — не копия")
        assertFalse(w.seen(ComposedWidget.Video("https://x.ru/другое.mp4", "Б"), 5_100))
    }
}
