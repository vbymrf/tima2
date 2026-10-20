package io.tima.feature.chat

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Подборка и подкладка (ADR-0050 §1): сокращённое — карточкой, целиком — текстом; «Нажимается
 * целиком» — зелёная полоса; «Продолжить» — зелёная кнопка. Снимки — в `build/снимки`.
 */
class RichRefScreenTest {

    private val text = "Сбор в 9:00 у входа в парк. Возьмите воду, зонт и хорошее настроение — идём до озера и обратно."

    private fun shot(name: String, block: RichBlock) = capture(name, 300, 160, dark = false) {
        CompositionLocalProvider(LocalQuoteLook provides { _, _, _ -> QuoteLook(name = "Аня", text = text) }) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(300.dp)) { RichBody(RichContent(listOf(block))) }
        }
    }

    @Test
    fun вид_по_свойствам() {
        val short = shot("ссылка-сокращённо", RichBlock.Quoted("group", "g", 1, author = true, lines = 1))
        val full = shot("ссылка-целиком", RichBlock.Quoted("group", "g", 1, author = true, lines = 0))
        val tap = shot("ссылка-нажимается", RichBlock.Quoted("group", "g", 1, author = true, lines = 1, tap = true))
        val more = shot("ссылка-продолжить", RichBlock.Underlay("group", "g", 1, author = false, lines = 2, more = "default"))
        assertTrue(short.difference(full) > 0.0, "сокращённое и целиком выглядят одинаково")
        assertTrue(short.difference(tap) > 0.0, "нет зелёной полосы у нажимаемого")
        assertTrue(full.difference(more) > 0.0)
    }
}
