package io.tima.domain.chat

/**
 * Плашки сообщения (заказчик 2026-10-24, «2в»): текстовый кусок редактора со стилем при отправке
 * делится на блоки — абзац, заголовок, цитата, код, разделитель, — и каждый нёс копию стиля, так что у
 * получателя кусок вставал отдельными плашками с зазорами, а в редакторе был один. Соседние текстовые
 * блоки с одинаковым стилем рисуются **одной плашкой**: фон, поля и разделитель — у неё, цвет, размер и
 * выравнивание — тексту внутри. Строение тела не меняется — это вид.
 */
object RichPlates {

    sealed interface Piece {
        /** Блок как есть — своим видом. */
        data class One(val block: RichBlock) : Piece

        /** Несколько блоков под общим [style]; у самих блоков стиль снят. */
        data class Plate(val style: BlockStyle, val blocks: List<RichBlock>) : Piece
    }

    fun of(blocks: List<RichBlock>): List<Piece> {
        val out = mutableListOf<Piece>()
        var style: BlockStyle? = null
        val run = mutableListOf<RichBlock>()
        fun flush() {
            val s = style
            when {
                run.isEmpty() -> Unit
                s == null || run.count { it != RichBlock.Divider } < 2 -> run.forEach { out += Piece.One(it) }
                else -> out += Piece.Plate(s, run.map(::plain))
            }
            run.clear()
            style = null
        }
        for ((i, b) in blocks.withIndex()) {
            val st = styleOf(b)
            when {
                st != null && st != BlockStyle.NONE && st == style -> run += b
                // Разделитель внутри куска стиля не несёт: он — часть плашки, если за ним тот же стиль.
                b == RichBlock.Divider && style != null && styleOf(blocks.getOrNull(i + 1)) == style -> run += b
                st != null && st != BlockStyle.NONE -> {
                    flush()
                    style = st
                    run += b
                }
                else -> {
                    flush()
                    out += Piece.One(b)
                }
            }
        }
        flush()
        return out
    }

    /** Стиль текстового блока; не текстовый — `null`: такие блоки плашки не делят. */
    fun styleOf(b: RichBlock?): BlockStyle? = when (b) {
        is RichBlock.Paragraph -> b.style
        is RichBlock.Heading -> b.style
        is RichBlock.Quote -> b.style
        is RichBlock.CodeBlock -> b.style
        else -> null
    }

    private fun plain(b: RichBlock): RichBlock = when (b) {
        is RichBlock.Paragraph -> b.copy(style = BlockStyle.NONE)
        is RichBlock.Heading -> b.copy(style = BlockStyle.NONE)
        is RichBlock.Quote -> b.copy(style = BlockStyle.NONE)
        is RichBlock.CodeBlock -> b.copy(style = BlockStyle.NONE)
        else -> b
    }
}
