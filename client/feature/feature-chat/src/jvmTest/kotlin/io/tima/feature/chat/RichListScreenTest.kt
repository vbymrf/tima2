package io.tima.feature.chat

import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import io.tima.domain.chat.Bullet
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.RichText
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Список — контейнер (ADR-0049): под заголовком пункта его блоки и вложенные пункты; свёрнутый
 * показывает только заголовки со стрелкой; без отступа — вложенное с начала строки. Снимки — в
 * `build/снимки`.
 */
class RichListScreenTest {

    private fun list(closed: Boolean, flat: Boolean) = RichContent(
        listOf(
            RichBlock.Bullets(
                listOf(
                    Bullet(
                        RichText("Заглавие"),
                        children = listOf(
                            Bullet(RichText("Подборка"), blocks = listOf(RichBlock.Emoji("🎒"))),
                            Bullet(RichText("Код"), blocks = listOf(RichBlock.CodeBlock("код без форматирования"))),
                        ),
                    ),
                    Bullet(RichText("Второй")),
                ),
                closed = closed,
                flat = flat,
            ),
        ),
    )

    private fun shot(name: String, content: RichContent) = capture(name, 300, 260, dark = false) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(300.dp)) { RichBody(content) }
    }

    /** Заказчик 2026-10-20: у пункта свой стиль — фон, цвет, выравнивание; соседний — без. */
    @Test
    fun стиль_пункта() {
        val styled = RichContent(listOf(RichBlock.Bullets(listOf(
            Bullet(RichText("Заглавие"), style = io.tima.domain.chat.BlockStyle(background = "amber-soft", color = "red", align = "center")),
            Bullet(RichText("Второй")),
        ))))
        val plain = RichContent(listOf(RichBlock.Bullets(listOf(Bullet(RichText("Заглавие")), Bullet(RichText("Второй"))))))
        assertTrue(shot("список-стиль-пункта", styled).difference(shot("список-без-стиля", plain)) > 0.0)
    }

    @Test
    fun свёрнутый_развёрнутый_и_без_отступа() {
        val open = shot("список-развёрнут", list(closed = false, flat = false))
        val closed = shot("список-свёрнут", list(closed = true, flat = false))
        val flat = shot("список-без-отступа", list(closed = false, flat = true))
        assertTrue(open.difference(closed) > 0.0, "свёрнутый не скрыл содержимое пункта")
        assertTrue(open.difference(flat) > 0.0, "без отступа нарисован так же, как с отступом")
    }
}
