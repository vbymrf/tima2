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

    private val original = RichContent(
        listOf(
            RichBlock.Heading(1, io.tima.domain.chat.RichText("Поход")),
            RichBlock.Bullets(listOf(io.tima.domain.chat.Bullet(io.tima.domain.chat.RichText("вода")), io.tima.domain.chat.Bullet(io.tima.domain.chat.RichText("зонт")))),
        ),
    )

    private fun richShot(name: String, block: RichBlock, rich: RichContent?) = capture(name, 300, 200, dark = false) {
        CompositionLocalProvider(LocalQuoteLook provides { _, _, _ -> QuoteLook(name = "Аня", text = "Поход вода зонт", rich = rich) }) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(300.dp)) { RichBody(RichContent(listOf(block))) }
        }
    }

    /** Заказчик 2026-10-20: подкладка — исходное как есть, со всем оформлением, а не только текст. */
    @Test
    fun подкладка_показывает_исходное_с_оформлением() {
        val block = RichBlock.Underlay("group", "g", 1, author = false, lines = 0)
        val styled = richShot("подкладка-исходное", block, original)
        val plain = richShot("подкладка-текст", block, null)
        assertTrue(styled.difference(plain) > 0.0, "подкладка нарисовала только текст исходного")
        // В пузыре — во всю его ширину, на поля пузыря.
        capture("подкладка-в-пузыре", 340, 260, dark = false) {
            CompositionLocalProvider(LocalQuoteLook provides { _, _, _ -> QuoteLook(name = "Аня", text = "Поход", rich = original) }) {
                io.tima.core.ui.Bubble(my = false, author = "Борис") {
                    RichBody(RichContent(listOf(RichBlock.Paragraph(io.tima.domain.chat.RichText("Смотри:")), block.copy(tap = true), block)))
                }
            }
        }
    }

    /** Заказчик 2026-10-20: стрелка — с именем короткая перед зелёным именем, без имени — длинная. */
    @Test
    fun стрелка_с_именем_и_без() {
        val named = richShot("стрелка-имя", RichBlock.Quoted("group", "g", 1, author = true, lines = 1, more = "blank"), original)
        val bare = richShot("стрелка-без-имени", RichBlock.Quoted("group", "g", 1, author = false, lines = 1, more = "blank"), original)
        val none = richShot("стрелка-нет", RichBlock.Quoted("group", "g", 1, author = false, lines = 1), original)
        assertTrue(named.difference(bare) > 0.0)
        assertTrue(bare.difference(none) > 0.0, "длинной стрелки не видно")
    }
}
