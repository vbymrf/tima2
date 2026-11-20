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

/** Вкладки страницы канала (ПЛАН-(ВС) 16): «Подписчики» видят владелец и модератор, подписчик — нет. */
class ChannelTabsTest {
    @Test
    fun подписчики_у_владельца_и_модератора() {
        assertTrue(PageTab.Members in pageTabs(io.tima.domain.chat.GroupRole.Owner, hasMembers = true))
        assertTrue(PageTab.Members in pageTabs(io.tima.domain.chat.GroupRole.Moderator, hasMembers = true))
        assertTrue(pageTabs(io.tima.domain.chat.GroupRole.Member, hasMembers = true).isEmpty(), "подписчику вкладок нет")
    }
}
