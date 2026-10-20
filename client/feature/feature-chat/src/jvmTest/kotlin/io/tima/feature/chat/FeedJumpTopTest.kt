package io.tima.feature.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Переход ставит сообщение верхом к верху видимой ленты (заказчик 2026-10-20: «переход идёт на низ
 * сообщения — сделай на верх»). Лента перевёрнута, как в переписке; поля сверху и снизу — тоже.
 */
class FeedJumpTopTest {

    private val pad = 16

    private fun jump(index: Int, cover: Int = 0): Float {
        val state = LazyListState()
        val tops = mutableMapOf<Int, Float>()
        val scene = ImageComposeScene(width = 300, height = 600, density = Density(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = state, reverseLayout = true, contentPadding = PaddingValues(vertical = pad.dp)) {
                items(30) { i ->
                    val h = when (i) { 10 -> 120; 20 -> 900; else -> 80 }
                    Box(Modifier.fillMaxWidth().height(h.dp).onGloballyPositioned { tops[i] = it.positionInRoot().y })
                }
            }
        }
        try {
            scene.render()
            runBlocking { state.scrollToItem(index) }
            scene.render()
            val delta = assertNotNull(topDelta(state, index, cover), "сообщение не на экране после прокрутки к нему")
            runBlocking { state.scrollBy(delta.toFloat()) }
            scene.render()
            return assertNotNull(tops[index])
        } finally {
            scene.close()
        }
    }

    @Test
    fun короткое_сообщение_встаёт_верхом_к_верху() = assertEquals(pad.toFloat(), jump(10), 1f)

    @Test
    fun высокое_сообщение_показывает_начало() = assertEquals(pad.toFloat(), jump(20), 1f, "выше экрана — виден его верх, а не низ")

    @Test
    fun то_что_лежит_поверх_ленты_сверху_учитывается() = assertEquals((pad + 50).toFloat(), jump(10, cover = 50), 1f)
}
