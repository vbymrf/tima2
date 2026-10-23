package io.tima.feature.chat

import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.RichMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Голосовые подряд (ПЛАН-(ВМ) Р40): следующее — сразу новее; не голосовое — стоп. */
class VoiceNextTest {

    private fun voice(id: String, at: Long) = ChatLine(
        dedupKey = id, chatId = "c", display = MessageDisplay.SENT, outgoing = false, atMs = at, localId = at,
        rich = RichContent(listOf(RichBlock.Voice(0)), listOf(RichMedia(id, ByteArray(32), "audio/ogg", 0, 0, voice = true, durationMs = 1000))),
    )

    private fun text(at: Long) = ChatLine(
        dedupKey = "t$at", chatId = "c", display = MessageDisplay.SENT, outgoing = false, atMs = at, localId = at, text = "привет",
    )

    @Test
    fun следующее_голосовое_до_первого_не_голосового() {
        // Новые первыми, как их отдаёт база: v3 — самое новое.
        val lines = listOf(voice("v4", 5), text(4), voice("v2", 3), voice("v1", 2))
        assertEquals("v2", nextVoiceAfter(lines, "v1")?.mediaId)
        assertNull(nextVoiceAfter(lines, "v2"), "дальше текст — подряд кончилось")
        assertNull(nextVoiceAfter(lines, "v4"), "самое новое — дальше нечего")
        assertNull(nextVoiceAfter(lines, "нет-такого"))
    }
}
