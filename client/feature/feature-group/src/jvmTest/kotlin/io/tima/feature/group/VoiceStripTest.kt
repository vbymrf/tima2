package io.tima.feature.group

import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/** Полоса аудиочата (ПЛАН-(ВС) ВС5, 2026-11-20): высотой строки списка, кнопки — круглыми значками. */
class VoiceStripTest {
    @Test
    fun микрофон_вкл_и_выкл_рисуются_по_разному() {
        val on = capture("полоса-аудиочата", 360, 120, dark = false) {
            VoiceStrip(title = "Вечерний разговор", allowMute = true, mic = true, onOpen = {}, onMic = {}, onLeave = {}, count = 4)
        }
        val off = capture("полоса-аудиочата-без-микрофона", 360, 120, dark = false) {
            VoiceStrip(title = "Вечерний разговор", allowMute = true, mic = false, onOpen = {}, onMic = {}, onLeave = {}, count = 4)
        }
        assertTrue(on.difference(off) > 0.0, "микрофон выключен, а полоса та же")
    }
}
