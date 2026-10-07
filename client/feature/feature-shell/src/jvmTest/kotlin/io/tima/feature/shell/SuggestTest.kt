package io.tima.feature.shell

import io.tima.core.ui.TimaColors
import io.tima.testui.capture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Предложить изменения» (заказчик 2026-10-07): текст и фото, без журнала. */
class SuggestTest {

    private class Sender(val outcome: SendOutcome = SendOutcome.Sent("Q4MZ")) : SuggestSender {
        var text: String? = null
        var photos: List<ProblemPhoto> = emptyList()
        override suspend fun send(text: String, photos: List<ProblemPhoto>): SendOutcome {
            this.text = text
            this.photos = photos
            return outcome
        }
    }

    @Test
    fun уходят_текст_и_фото() = runTest {
        val sender = Sender()
        val store = SuggestStore(sender, backgroundScope)
        store.changedText("  тёмная тема по расписанию  ")
        store.addPhoto(ProblemPhoto("image/jpeg", byteArrayOf(1, 2)))
        store.send()
        store.state.first { it.outcome != null }

        assertEquals("тёмная тема по расписанию", sender.text)
        assertEquals(1, sender.photos.size)
        assertEquals(SendOutcome.Sent("Q4MZ"), store.state.value.outcome)
    }

    @Test
    fun пустое_не_уходит() = runTest {
        val sender = Sender()
        val store = SuggestStore(sender, backgroundScope)
        assertNotNull(store.state.value.missing)
        store.send()
        assertNull(sender.text)
    }

    @Test
    fun отправленное_держится_до_повторного_входа() = runTest {
        val store = SuggestStore(Sender(SendOutcome.Queued), backgroundScope)
        store.changedText("кнопка крупнее")
        store.send()
        store.state.first { it.outcome != null }

        assertTrue(store.state.value.delivered)
        assertFalse(store.state.value.canSend)
        assertFalse(store.state.value.morePhotos)
    }

    @Test
    fun фото_не_больше_трёх() {
        val store = SuggestStore(Sender(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        repeat(5) { store.addPhoto(ProblemPhoto("image/jpeg", byteArrayOf(it.toByte()))) }
        assertEquals(MAX_PHOTOS, store.state.value.photos.size)
        store.addPhoto(null)
        assertFalse(store.state.value.photoRejected, "полный набор фото — выбор не открывается вовсе")
    }

    @Test
    fun экран_рисуется() {
        val shot = capture("предложить-изменения", 400, 700, dark = false) {
            SuggestScreen(state = SuggestState(text = "тёмная тема по расписанию"), onText = {}, onSend = {}, onAddPhoto = {})
        }
        assertTrue(shot.patchHas(TimaColors.light.navigation, y = 0 until 100), "кнопки «Отправить» нет наверху")
    }
}
