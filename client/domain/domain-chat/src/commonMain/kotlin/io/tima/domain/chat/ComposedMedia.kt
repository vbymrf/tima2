package io.tima.domain.chat

/**
 * Видео, звук и метки в документе редактора (ПЛАН-(ВМ) §3.6–§3.8): описания — заглавие и привязка
 * меток (Р10), одинаковые описания разводятся « (2)», метки без своего видео убираются (Р9).
 */
object ComposedMedia {

    /** Все виджеты документа по порядку — в рядах и пунктах списков тоже. */
    fun widgets(doc: ComposedDoc): List<ComposedWidget> = doc.items.flatMap { item ->
        when (item) {
            is ComposedItem.Widget -> listOf(item.widget)
            is ComposedItem.Row -> item.cells.flatMap { it.widgets }
            is ComposedItem.ItemList -> item.items.flatMap { it.widgets }
            else -> emptyList()
        }
    }

    /** Описания видео и аудио — к ним привязываются метки; у потока меток нет. */
    fun bindable(doc: ComposedDoc): List<String> = widgets(doc).mapNotNull {
        when (it) {
            is ComposedWidget.Video -> it.description
            is ComposedWidget.Audio -> it.description
            else -> null
        }
    }.filter { it.isNotBlank() }

    /** Сколько видео, аудио, потоков и меток в документе — предел 30 (Р24). */
    fun count(doc: ComposedDoc): Int = widgets(doc).count {
        it is ComposedWidget.Video || it is ComposedWidget.Audio || it is ComposedWidget.Stream || it is ComposedWidget.Marks
    }

    /** Видео или аудио с описанием [description]. */
    fun target(doc: ComposedDoc, description: String): ComposedWidget? = widgets(doc).firstOrNull {
        (it is ComposedWidget.Video && it.description == description) || (it is ComposedWidget.Audio && it.description == description)
    }

    /** Метки к [description]; нет — `null`. */
    fun marksOf(doc: ComposedDoc, description: String): ComposedWidget.Marks? =
        widgets(doc).firstOrNull { it is ComposedWidget.Marks && it.bind == description } as? ComposedWidget.Marks

    /** Описание, которого в документе ещё нет: занято — « (2)», « (3)»… (Р27). */
    fun unique(doc: ComposedDoc, description: String, taken: Collection<String> = emptyList()): String {
        val used = (bindable(doc) + widgets(doc).mapNotNull { (it as? ComposedWidget.Stream)?.description } + taken).toSet()
        val base = description.trim()
        if (base !in used) return base
        var n = 2
        while ("$base ($n)" in used) n++
        return "$base ($n)"
    }

    /** Заменить первый виджет, подходящий под [match]; не нашлось — `null`. */
    fun replace(doc: ComposedDoc, match: (ComposedWidget) -> Boolean, widget: ComposedWidget): ComposedDoc? {
        var done = false
        fun swap(w: ComposedWidget) = if (!done && match(w)) widget.also { done = true } else w
        val items = doc.items.map { item ->
            when (item) {
                is ComposedItem.Widget -> item.copy(widget = swap(item.widget))
                is ComposedItem.Row -> item.copy(cells = item.cells.map { c -> c.copy(widgets = c.widgets.map(::swap)) })
                is ComposedItem.ItemList -> item.copy(items = item.items.map { p -> p.copy(widgets = p.widgets.map(::swap)) })
                else -> item
            }
        }
        return if (done) doc.copy(items = items) else null
    }

    /** Убрать метки, чьего видео или аудио в документе нет (Р9), и метки по порядку времени. */
    fun prune(doc: ComposedDoc): ComposedDoc {
        val targets = bindable(doc).toSet()
        fun keep(w: ComposedWidget) = w !is ComposedWidget.Marks || w.bind in targets
        fun tidy(w: ComposedWidget) = if (w is ComposedWidget.Marks) w.copy(marks = w.marks.sortedBy { it.atMs }) else w
        val items = doc.items.mapNotNull { item ->
            when (item) {
                is ComposedItem.Widget -> if (keep(item.widget)) item.copy(widget = tidy(item.widget)) else null
                is ComposedItem.Row -> item.copy(cells = item.cells.map { c -> c.copy(widgets = c.widgets.filter(::keep).map(::tidy)) })
                is ComposedItem.ItemList -> item.copy(items = item.items.map { p -> p.copy(widgets = p.widgets.filter(::keep).map(::tidy)) })
                else -> item
            }
        }
        return if (items == doc.items) doc else doc.copy(items = items)
    }

    /**
     * Ссылки из вставленного списка (Р27): делятся по пробелам и переводам строки, по `,` и `;` —
     * только если за ними начинается `http://` или `https://` (адрес законно может их содержать).
     */
    fun splitLinks(text: String): List<String> {
        val parts = text.split(Regex("\\s+")).flatMap { it.split(Regex("[,;](?=https?://)")) }
        return parts.map { it.trim().trimEnd(',', ';') }.filter { it.startsWith("http://") || it.startsWith("https://") }
    }
}
