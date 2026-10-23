package io.tima.crypto

/**
 * Проверка разметки редактора (ADR-0044 §5) — одна на отправку, приём и вкладку JSON редактора.
 *
 * **Строго — на отправке и в редакторе:** незнакомый тип, незнакомый ключ стиля, цвет не из
 * палитры — ошибка, отправить нельзя. Так JSON редактора не становится обходом правил.
 *
 * **Мягко — на приёме:** незнакомое от более новой версии клиента не ошибка — его покажут текстом
 * или без этого свойства. Ошибкой остаются нарушенные пределы и сломанная структура: такое
 * сообщение показывается без оформления (как испорченная разметка по ADR-0011).
 */
object MarkupRules {

    /** Текст — массив узлов, знаков UTF-16 (ADR-0019 §3, ADR-0044 §5). */
    const val MAX_NODE_CHARS = 4096

    /** Разметка JSON до сжатия, байт UTF-8. */
    const val MAX_MARKUP_BYTES = 32 * 1024

    /** Рядов и таблиц верхнего уровня в сообщении. */
    const val MAX_ROWS = 20

    const val MIN_CELLS = 1

    /** Столбцов в ряду (заказчик 2026-10-18: до 7; было 4). */
    const val MAX_CELLS = 7

    /** Строк в ряду (заказчик 2026-10-18). */
    const val MAX_LINES = 20

    /** Картинок в сообщении. */
    const val MAX_IMAGES = 10

    /** Строк подкладки до «Продолжить чтение». */
    const val MAX_UNDERLAY_LINES = 50
    /** Свой текст кнопки «Продолжить» (ADR-0050 §1). */
    const val MAX_MORE_TEXT = 40

    /** Вложенность списков: пункт, вложенный, вложенный во вложенный (§7 Решений: два вложения). */
    const val MAX_LIST_DEPTH = 3

    /** Меток у одного видео или аудио (ПЛАН-(ВМ) Р11). */
    const val MAX_MARKS = 30

    /** Виджетов «Видео», «Аудио», «Поток» и «Метки» в сообщении вместе (Р24). */
    const val MAX_MEDIA_WIDGETS = 30

    /** Текст одной метки. */
    const val MAX_MARK_TEXT = 200

    /**
     * Имена палитры. Значения для светлой и тёмной темы — у клиента (core-ui): у имени свой цвет
     * в каждой теме, поэтому в разметке кодов нет.
     */
    val PALETTE: Set<String> = setOf(
        "text", "surface", "accent", "accent-soft",
        "amber", "amber-soft", "green", "green-soft",
        "blue", "blue-soft", "red", "red-soft",
        "grey", "grey-soft",
        // Яркие для ссылок (заказчик 2026-10-21): жёлтый, сиреневый, розовый.
        "yellow", "lilac", "pink",
    )

    private val STEPS: Map<String, Set<String>> = mapOf(
        "size" to setOf("smaller", "normal", "larger"),
        "align" to setOf("start", "center", "end"),
        "valign" to setOf("top", "center", "bottom"),
        "padding" to setOf("none", "small", "normal", "large"),
        "divider" to setOf("hidden", "1", "2", "4"),
        "merge" to setOf("up", "left"),
        // Свойства списка (ADR-0049 §3): показать свёрнутым, без отступа.
        "fold" to setOf("open", "closed"),
        "indent" to setOf("normal", "flat"),
    )
    private val PALETTE_KEYS = setOf("background", "color", "divider_color")
    private val STYLE_KEYS = STEPS.keys + PALETTE_KEYS + "width"

    private val TEXT_BLOCKS = setOf(
        BlockType.PARAGRAPH, BlockType.HEADING, BlockType.QUOTE, BlockType.LIST, BlockType.CODE_BLOCK,
        BlockType.DIVIDER,
    )
    private val TOP_LEVEL = TEXT_BLOCKS + setOf(BlockType.FLEX, BlockType.TABLE, BlockType.REPLY, BlockType.UNDERLAY)
    private val CELL_WIDGETS = setOf(
        BlockType.IMAGE, BlockType.LINK, BlockType.CLOUD, BlockType.MAP, BlockType.CONTACT,
        BlockType.REPLY, BlockType.QUOTED, BlockType.EMOJI, BlockType.TABLE,
        BlockType.VIDEO, BlockType.AUDIO, BlockType.STREAM, BlockType.MARKS,
    )
    private val REF_KINDS = setOf("personal", "group", "channel")

    /** Нарушение: правило (латиницей, для журнала и тестов) и что не так — словами. */
    data class Problem(val rule: String, val detail: String)

    /**
     * Проверить содержимое.
     *
     * @param strict строго (отправка, редактор) или мягко (приём) — см. описание объекта.
     * @return пусто — всё в порядке.
     */
    fun check(content: MessageContent, strict: Boolean): List<Problem> {
        val out = mutableListOf<Problem>()
        val chars = content.nodes.sumOf { it.length }
        if (chars > MAX_NODE_CHARS) out += Problem("node-chars", "текста $chars знаков, больше $MAX_NODE_CHARS")
        val markup = content.markup ?: return out
        val bytes = MarkupV2.encodeBody(markup, content.nodes).encodeToByteArray().size
        if (bytes > MAX_MARKUP_BYTES) out += Problem("markup-bytes", "разметка $bytes байт, больше $MAX_MARKUP_BYTES")
        if (markup.n.isNotEmpty() && markup.n.size != content.nodes.size) {
            out += Problem("node-ids", "идентификаторов узлов ${markup.n.size}, узлов ${content.nodes.size}")
        }
        if (markup.n.toSet().size != markup.n.size) out += Problem("node-ids", "идентификаторы узлов повторяются")
        val ids = markup.n.toSet()
        val ctx = Ctx(ids, content.media.size, strict, out)

        var rows = 0
        // Подкладка — где угодно на верхнем уровне и в пункте списка, только не в ячейке ряда
        // (заказчик 2026-10-21; прежде — только в конце, правило `underlay-last`). Разметка та же — v2.
        for (block in markup.blocks) {
            if (block.type == BlockType.FLEX || block.type == BlockType.TABLE) rows++
            if (block.type == BlockType.UNKNOWN) {
                if (strict) out += Problem("unknown-type", "незнакомый тип блока")
                continue
            }
            if (block.type !in TOP_LEVEL) out += Problem("top-level", "«${block.type.wire}» не может стоять вне ячейки")
            checkBlock(block, ctx, depth = 0)
        }
        if (rows > MAX_ROWS) out += Problem("rows", "рядов и таблиц $rows, больше $MAX_ROWS")
        if (ctx.images > MAX_IMAGES) out += Problem("images", "картинок ${ctx.images}, больше $MAX_IMAGES")
        // Видео, звук и метки (ПЛАН-(ВМ) Р9–Р11, Р24): всего до 30, метки — к описанию видео или аудио
        // этого сообщения, к одному — одни.
        val media = ctx.mediaWidgets
        if (media > MAX_MEDIA_WIDGETS) out += Problem("media-widgets", "видео, аудио, потоков и меток $media, больше $MAX_MEDIA_WIDGETS")
        if (strict) {
            for (b in ctx.binds.toSet()) if (b !in ctx.descriptions) out += Problem("marks-bind", "меток к «$b» — такого видео или аудио нет")
            if (ctx.binds.toSet().size != ctx.binds.size) out += Problem("marks-bind", "к одному видео или аудио — одни метки")
        }
        for (e in markup.entities) {
            if (e.type == EntityType.UNKNOWN && strict) out += Problem("unknown-type", "незнакомый вид оформления")
            if (e.nodeId !in ids) out += Problem("node-ref", "оформление ссылается на узел ${e.nodeId}, его нет")
        }
        return out
    }

    private class Ctx(val ids: Set<Int>, val mediaCount: Int, val strict: Boolean, val out: MutableList<Problem>) {
        var images = 0

        /** Виджетов «Видео», «Аудио», «Поток», «Метки». */
        var mediaWidgets = 0

        /** Описания видео и аудио — к ним привязываются метки; у потока меток нет. */
        val descriptions = mutableListOf<String>()
        val binds = mutableListOf<String>()
    }

    private fun checkBlock(block: MarkupBlock, ctx: Ctx, depth: Int) {
        val out = ctx.out
        for (id in block.nodeIds) if (id !in ctx.ids) out += Problem("node-ref", "«${block.type.wire}» ссылается на узел $id, его нет")
        checkStyle(block, ctx)
        when (block.type) {
            BlockType.FLEX -> {
                // Однострочный ряд — ячейки прямо в нём; ряд из строк (2026-10-18) — строки `row` с ячейками.
                val lines = if (block.children.isNotEmpty() && block.children.all { it.type == BlockType.ROW }) {
                    block.children.map { it.children }
                } else {
                    listOf(block.children)
                }
                if (lines.size > MAX_LINES) out += Problem("lines", "в ряду ${lines.size} строк, можно до $MAX_LINES")
                val columns = lines.first().size
                if (lines.any { it.size != columns }) out += Problem("cells", "в строках ряда разное число ячеек")
                for (cells in lines) {
                    if (cells.size !in MIN_CELLS..MAX_CELLS) out += Problem("cells", "в ряду ${cells.size} ячеек, можно $MIN_CELLS–$MAX_CELLS")
                    if (cells.any { it.type != BlockType.CELL }) out += Problem("flex-children", "в ряду не только ячейки")
                    val widths = cells.map { it.style["width"]?.toIntOrNull() }
                    if (widths.any { it == null || it < 1 }) out += Problem("width", "у ячейки ряда нет ширины")
                    else if (widths.sumOf { it!! } != 100) out += Problem("width", "ширины ряда в сумме ${widths.sumOf { it!! }}, нужно 100")
                    for (cell in cells.filter { it.type == BlockType.CELL }) checkRowCell(cell, ctx)
                }
                if (lines.all { it.size == columns }) checkMerges(lines.flatten(), columns, out)
            }
            BlockType.TABLE -> {
                if (block.children.isEmpty()) out += Problem("table", "в таблице нет строк")
                for (row in block.children) {
                    if (row.type != BlockType.ROW) { out += Problem("table", "в таблице не строка"); continue }
                    for (cell in row.children) {
                        if (cell.type != BlockType.CELL) { out += Problem("table", "в строке таблицы не ячейка"); continue }
                        if (cell.nodeIds.size > 1) out += Problem("cell-node", "в ячейке таблицы больше одного узла")
                        if (cell.children.isNotEmpty()) out += Problem("table", "в ячейке таблицы виджеты: в ней только текст")
                        for (id in cell.nodeIds) if (id !in ctx.ids) out += Problem("node-ref", "ячейка ссылается на узел $id, его нет")
                        checkStyle(cell, ctx)
                    }
                }
            }
            BlockType.LIST -> {
                if (depth + 1 > MAX_LIST_DEPTH) out += Problem("list-depth", "вложенность списка больше $MAX_LIST_DEPTH")
                for (child in block.children) {
                    when (child.type) {
                        BlockType.LIST_ITEM -> {
                            for (id in child.nodeIds) if (id !in ctx.ids) out += Problem("node-ref", "пункт ссылается на узел $id, его нет")
                            if (child.nodeIds.size > 1) out += Problem("list-item", "у пункта больше одного узла-заголовка")
                            // Пункт — контейнер, как ячейка (ADR-0049 §2): блоки текста, виджеты ячейки, вложенный список;
                            // и подкладка (заказчик 2026-10-21) — в ячейке ряда её по-прежнему нет.
                            for (inner in child.children) when {
                                inner.type == BlockType.UNKNOWN -> if (ctx.strict) out += Problem("unknown-type", "незнакомый блок в пункте")
                                inner.type in TEXT_BLOCKS || inner.type in CELL_WIDGETS -> checkBlock(inner, ctx, depth + 1)
                                inner.type == BlockType.UNDERLAY -> checkWidget(inner, ctx)
                                else -> out += Problem("list-item", "«${inner.type.wire}» не кладётся в пункт списка")
                            }
                        }
                        BlockType.LIST -> checkBlock(child, ctx, depth + 1)
                        else -> out += Problem("list", "в списке «${child.type.wire}»")
                    }
                }
            }
            else -> checkWidget(block, ctx)
        }
    }

    /**
     * Слияние в ряду (2026-10-18): «с левой» не в первом столбце, «с верхней» не в первой строке, у
     * слитой ячейки нет своего содержимого, а слитое — прямоугольник.
     */
    private fun checkMerges(cells: List<MarkupBlock>, columns: Int, out: MutableList<Problem>) {
        if (columns <= 0) return
        val owner = IntArray(cells.size) { it }
        for (p in cells.indices) {
            val merge = cells[p].style["merge"] ?: continue
            val c = p % columns
            when {
                merge == "left" && c > 0 -> owner[p] = owner[p - 1]
                merge == "up" && p >= columns -> owner[p] = owner[p - columns]
                else -> out += Problem("merge", "слияние «$merge» у ячейки, которой не с чем сливаться")
            }
            if (cells[p].nodeIds.isNotEmpty() || cells[p].children.isNotEmpty()) out += Problem("merge", "у слитой ячейки своё содержимое")
        }
        for (o in owner.toSet()) {
            val places = owner.indices.filter { owner[it] == o }
            val lines = places.map { it / columns }
            val cols = places.map { it % columns }
            val area = (lines.max() - lines.min() + 1) * (cols.max() - cols.min() + 1)
            if (area != places.size) out += Problem("merge", "слитое в ряду не прямоугольник")
        }
    }

    private fun checkRowCell(cell: MarkupBlock, ctx: Ctx) {
        if (cell.nodeIds.size > 1) ctx.out += Problem("cell-node", "в ячейке ряда больше одного узла: ячейка — один узел")
        for (id in cell.nodeIds) if (id !in ctx.ids) ctx.out += Problem("node-ref", "ячейка ссылается на узел $id, его нет")
        for (w in cell.children) {
            if (w.type == BlockType.UNKNOWN) {
                if (ctx.strict) ctx.out += Problem("unknown-type", "незнакомый виджет")
                continue
            }
            // Ячейка — область со своими блоками (ADR-0048 §4): абзацы, заголовки, списки, цитаты, код.
            if (w.type !in CELL_WIDGETS && w.type !in TEXT_BLOCKS) ctx.out += Problem("cell-widget", "«${w.type.wire}» не кладётся в ячейку")
            checkBlock(w, ctx, depth = 0)
        }
    }

    private fun checkWidget(block: MarkupBlock, ctx: Ctx) {
        val w = block.widget
        val out = ctx.out
        fun need(ok: Boolean, what: String) { if (!ok) out += Problem("widget", "«${block.type.wire}»: $what") }
        when (block.type) {
            BlockType.IMAGE -> {
                ctx.images++
                need(w != null && w.media in 0 until ctx.mediaCount, "нет картинки в теле сообщения")
                need(w == null || w.share == 0 || w.share in 20..99, "доля ширины не из 20…99")
            }
            BlockType.LINK -> {
                need(w != null && w.url.isNotBlank(), "нет адреса")
                need(w == null || w.mode in setOf("plain", "button", "card"), "вид ссылки не из «plain», «button», «card»")
                if (w != null && w.media >= 0) need(w.media < ctx.mediaCount, "картинки карточки нет в теле")
            }
            BlockType.CLOUD -> {
                need(w != null && w.url.isNotBlank(), "нет адреса")
                need(w == null || w.kind.isEmpty() || w.kind in setOf("file", "document", "audio", "video"), "вид файла незнакомый")
            }
            BlockType.MAP -> need(
                w?.lat != null && w.lon != null && w.lat in -90.0..90.0 && w.lon in -180.0..180.0,
                "нет координат",
            )
            BlockType.CONTACT -> need(w != null && (w.name.isNotBlank() || w.userId.isNotBlank()), "нет ни имени, ни человека")
            BlockType.EMOJI -> need(w != null && w.text.isNotBlank(), "нет знака")
            BlockType.VIDEO, BlockType.AUDIO, BlockType.STREAM -> {
                need(w != null && (w.url.startsWith("https://") || w.url.startsWith("http://")), "нет адреса http(s)")
                // Описание обязательно (Р13, Р27); мягкий приём — пустое показывается без заглавия.
                if (ctx.strict) need(w != null && w.description.isNotBlank(), "нет описания")
                if (block.type == BlockType.VIDEO) {
                    need(w == null || w.look in setOf("", "picture", "frame"), "вид не «picture» и не «frame»")
                    if (w != null && w.media >= 0) need(w.media < ctx.mediaCount, "своей картинки нет в теле")
                }
                ctx.mediaWidgets++
                if (block.type != BlockType.STREAM) ctx.descriptions += w?.description.orEmpty()
            }
            BlockType.MARKS -> {
                need(w != null && w.bind.isNotBlank(), "не привязаны к видео или аудио")
                need(w != null && w.marks.size in 1..MAX_MARKS, "меток не 1–$MAX_MARKS")
                need(w == null || w.marks.all { it.at >= 0 && it.text.length <= MAX_MARK_TEXT }, "время меньше нуля или текст длиннее $MAX_MARK_TEXT")
                ctx.mediaWidgets++
                ctx.binds += w?.bind.orEmpty()
            }
            BlockType.REPLY, BlockType.QUOTED, BlockType.UNDERLAY -> {
                val ref = w?.ref
                need(ref != null && ref.kind in REF_KINDS && ref.container.isNotBlank() && ref.message > 0, "нет ссылки на сообщение")
                if (block.type == BlockType.QUOTED) need(w == null || w.cut.isEmpty() || w.cut in setOf("full", "cut"), "вид показа не «full» и не «cut»")
                if (block.type != BlockType.REPLY) {
                    // ADR-0050 §1: строк 0 (целиком) — MAX; «Продолжить» — из известных видов, свой текст короткий.
                    need(w == null || w.lines in 0..MAX_UNDERLAY_LINES, "строк не 0–$MAX_UNDERLAY_LINES")
                    need(w == null || w.more in setOf("", "default", "blank", "custom"), "вид «Продолжить» незнакомый")
                    need(w == null || w.moreText.length <= MAX_MORE_TEXT, "текст «Продолжить» длиннее $MAX_MORE_TEXT")
                }
            }
            else -> Unit // текстовые блоки: только узлы и стиль
        }
    }

    private fun checkStyle(block: MarkupBlock, ctx: Ctx) {
        for ((key, value) in block.style) {
            when {
                (key == "fold" || key == "indent") && block.type != BlockType.LIST -> ctx.out += Problem("style", "«$key» — свойство списка, а не «${block.type.wire}»")
                key in PALETTE_KEYS -> if (value !in PALETTE) ctx.out += Problem("palette", "«$key»: «$value» — не имя палитры")
                key in STEPS -> if (value !in STEPS.getValue(key)) ctx.out += Problem("step", "«$key»: «$value» — не из ступеней")
                key == "width" -> if (block.type != BlockType.CELL) ctx.out += Problem("style", "ширина у «${block.type.wire}», а не у ячейки")
                key !in STYLE_KEYS -> if (ctx.strict) ctx.out += Problem("style", "незнакомое свойство «$key»")
            }
        }
    }
}
