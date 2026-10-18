package io.tima.feature.chat

import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.RichMedia
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Просмотр фото (заказчик 2026-10-18): «≡» слева, «✕» справа и стрелки — на сплошном фоне; у первого
 * фото нет «‹», у последнего нет «›»; «3 из 3» — какое открыто.
 */
class PhotoViewerTest {

    private fun photo(n: Int, outgoing: Boolean) = PhotoAt(
        media = RichMedia("m-$n", ByteArray(16), "image/jpeg", 1280, 960, size = 210_000L),
        line = ChatLine(dedupKey = "d-$n", chatId = "chat-1", display = MessageDisplay.SENT, text = null, outgoing = outgoing, atMs = n * 1000L, localId = n.toLong(), serverId = n.toLong()),
        from = if (outgoing) "Я" else "Аня",
    )

    private val photos = listOf(photo(1, true), photo(2, false), photo(3, true))

    @Test
    fun первое_и_последнее_фото_разные_кнопки() {
        val first = capture("фото-первое", 360, 640, dark = false) { PhotoViewer(photos, 0, PhotoActions(), onClose = {}) }
        val last = capture("фото-последнее", 360, 640, dark = false) { PhotoViewer(photos, 2, PhotoActions(), onClose = {}) }
        assertTrue(first.difference(last) > 0.0, "стрелки и счётчик не зависят от открытого фото")
    }

    @Test
    fun подпись_поверх_фото_видна() {
        val plain = capture("фото-тёмное", 360, 640, dark = true) { PhotoViewer(photos, 1, PhotoActions(), onClose = {}) }
        val said = capture("фото-подпись", 360, 640, dark = true) { PhotoViewer(photos, 1, PhotoActions(), onClose = {}, notice = "Фото переслано") }
        assertTrue(plain.difference(said) > 0.001)
    }
}
