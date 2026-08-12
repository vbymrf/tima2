package io.tima.crypto

/**
 * Профили возможностей (ADR-0011 §8): формат блоков один и тот же в пузыре чата и
 * в публикации, но что из него можно показать — ограничивает контур. Таблица
 * шириной в 70% экрана телефона внутри пузыря — не гипотетическая, а реальная
 * проблема вёрстки; профиль делает её решение явным местом в коде, а не «как
 * получится» у каждого рендерера отдельно.
 *
 * Полного визуального рендера блоков в этой сборке нет (ADR: «основная стоимость —
 * не формат, а отрисовка блоков на всех платформах», среда без Compose-тулчейна не
 * даёт его проверить). Здесь — структурная часть профиля: чистая функция, которую
 * рендерер обязан будет учитывать, когда появится, вместо того чтобы каждой
 * платформе заново придумывать пороги.
 */
enum class ContentProfile(
    /** Глубина вложенности контейнеров, дальше которой сетка «упрощается» (ADR-0011 §8). */
    val maxContainerDepth: Int,
    /** Столбцов в таблице, дальше которых она размечается как горизонтально прокручиваемая. */
    val maxComfortableTableColumns: Int,
) {
    /** Пузырь личного или группового сообщения — подмножество. */
    BUBBLE(maxContainerDepth = 2, maxComfortableTableColumns = 3),

    /** Публикация в канале — полный набор. */
    PUBLICATION(maxContainerDepth = Int.MAX_VALUE, maxComfortableTableColumns = Int.MAX_VALUE),
}

/**
 * Итог применения профиля к одному блоку. Рендерер решает, что рисовать, по этим
 * признакам, а не по типу блока напрямую — иначе порог из ADR-0011 §8 пришлось бы
 * помнить в коде каждой платформы отдельно.
 */
data class BlockRenderHint(
    val block: MarkupBlock,
    val depth: Int,
    /** Таблица шире комфортной для профиля — рендерить с горизонтальной прокруткой. */
    val horizontalScroll: Boolean = false,
    /** Контейнер глубже предела профиля — рендерить как простую вертикальную последовательность. */
    val simplified: Boolean = false,
)

object ContentProfiles {

    /** Плоский список блоков документа с решением профиля по каждому. */
    fun apply(markup: Markup, profile: ContentProfile): List<BlockRenderHint> =
        markup.blocks.flatMap { resolve(it, profile, depth = 0) }

    private fun resolve(block: MarkupBlock, profile: ContentProfile, depth: Int): List<BlockRenderHint> {
        val hint = BlockRenderHint(
            block = block,
            depth = depth,
            horizontalScroll = block.type == BlockType.TABLE && columnsOf(block) > profile.maxComfortableTableColumns,
            simplified = block.type == BlockType.CONTAINER && depth >= profile.maxContainerDepth,
        )
        // Упрощённый контейнер не разворачивает детей глубже: сложная сетка внутри
        // него всё равно недоступна профилю, а рекурсия туда добавила бы только
        // блоки, которые рендерер профиля не должен видеть по определению.
        if (hint.simplified) return listOf(hint)
        val children = block.children.flatMap { resolve(it, profile, depth + 1) }
        return listOf(hint) + children
    }

    /** Столбцов в таблице — по строке (ROW) с наибольшим числом ячеек (CELL). */
    private fun columnsOf(table: MarkupBlock): Int =
        table.children.filter { it.type == BlockType.ROW }
            .maxOfOrNull { row -> row.children.count { it.type == BlockType.CELL } } ?: 0
}
