package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * «Стиль» показывает только действующие кнопки (doc_mig/РЕДАКТОР-СВОЙСТВА-И-ВЛОЖЕНИЕ.md); у пункта
 * списка — свой стиль, как у ячейки ряда (заказчик 2026-10-20).
 */
class ComposedStyleApplicableTest {

    private val all = listOf("background", "color", "size", "align", "padding", "divider")

    @Test
    fun что_действует_на_какой_блок() {
        val doc = ComposedDoc(
            listOf(
                ComposedItem.Text("a"),
                ComposedItem.Row(listOf(ComposedCell(50), ComposedCell(50))),
                ComposedItem.ItemList(listOf(ComposedListItem(1, "x"))),
                ComposedItem.Widget(ComposedWidget.Emoji("🎉")),
                ComposedItem.Widget(ComposedWidget.Reply(ReplyTarget("group", "g", 1, "u"))),
                ComposedItem.Continuation(3),
            ),
        )
        assertEquals(all, ComposedStyle.applicable(doc, Spot(0)))
        assertEquals(all, ComposedStyle.applicable(doc, Spot(1, 0)))
        assertEquals(all, ComposedStyle.applicable(doc, Spot(2, 0)))
        assertEquals(listOf("background", "padding", "divider"), ComposedStyle.applicable(doc, Spot(3)))
        assertEquals(emptyList(), ComposedStyle.applicable(doc, Spot(4)), "ответ стиля не показывает")
        assertEquals(emptyList(), ComposedStyle.applicable(doc, Spot(5)))
    }

    @Test
    fun стиль_пункта_свой() {
        val doc = ComposedDoc(listOf(ComposedItem.ItemList(listOf(ComposedListItem(1, "x"), ComposedListItem(1, "y")), style = mapOf("color" to "red"))))
        val next = ComposedStyle.with(doc, Spot(0, 1), "background", "amber-soft")
        val list = next.items.single() as ComposedItem.ItemList
        assertEquals(mapOf("background" to "amber-soft"), list.items[1].style)
        assertEquals(emptyMap(), list.items[0].style, "соседний пункт не тронут")
        assertEquals(mapOf("color" to "red"), list.style, "стиль списка — свой")
        assertEquals(mapOf("color" to "red"), ComposedStyle.styleAt(next, Spot(0)), "весь список выбран — его стиль")
    }
}
