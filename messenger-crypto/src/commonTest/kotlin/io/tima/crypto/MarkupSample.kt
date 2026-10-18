package io.tima.crypto

import io.tima.crypto.proto.MediaRef

/**
 * Образцовое сообщение редактора (ADR-0044): заголовок, ряд 33 · 67 с картинкой и текстом,
 * таблица с объединённой ячейкой, абзац, подкладка; маркер в ячейке. Одно на проверку правил
 * ([MarkupRulesTest]) и на вектор разметки ([MarkupVectorTest]) — чтобы вектор замораживал то же,
 * что проверяют правила.
 */
internal object MarkupSample {

    private fun cell(width: Int, node: Int? = null, vararg widgets: MarkupBlock) = MarkupBlock(
        type = BlockType.CELL,
        nodeIds = listOfNotNull(node),
        children = widgets.toList(),
        style = mapOf("width" to width.toString()),
    )

    private fun image(index: Int) = MarkupBlock(type = BlockType.IMAGE, widget = MarkupWidget(media = index))

    private fun media(n: Int) = List(n) { MediaRef(media_id = "m$it") }

    fun content(): MessageContent {

        val nodes = listOf("Поход в субботу", "Сбор в 9:00 у входа", "Что", "Кто", "вода", "Анна", "Напоминаю маршрут")
        val markup = Markup(
            n = listOf(1, 2, 3, 4, 5, 6, 7),
            blocks = listOf(
                MarkupBlock(type = BlockType.HEADING, nodeIds = listOf(1), level = 1),
                MarkupBlock(
                    type = BlockType.FLEX,
                    style = mapOf("background" to "amber-soft", "divider" to "2"),
                    children = listOf(cell(33, null, image(0)), cell(67, 2)),
                ),
                MarkupBlock(
                    type = BlockType.TABLE,
                    children = listOf(
                        MarkupBlock(type = BlockType.ROW, children = listOf(
                            MarkupBlock(type = BlockType.CELL, nodeIds = listOf(3)),
                            MarkupBlock(type = BlockType.CELL, nodeIds = listOf(4)),
                        )),
                        MarkupBlock(type = BlockType.ROW, children = listOf(
                            MarkupBlock(type = BlockType.CELL, nodeIds = listOf(5)),
                            MarkupBlock(type = BlockType.CELL, nodeIds = listOf(6), style = mapOf("merge" to "up")),
                        )),
                    ),
                ),
                MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = listOf(7)),
                MarkupBlock(
                    type = BlockType.UNDERLAY,
                    widget = MarkupWidget(ref = MarkupRef("group", "g-1", 42), author = true, lines = 3),
                ),
            ),
            entities = listOf(MarkupEntity(type = EntityType.HIGHLIGHT, nodeId = 2, start = 0, length = 4)),
        )
        return MessageContent(nodes = nodes, markup = markup, media = media(1))
    }
}
