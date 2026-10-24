package io.tima.domain.chat

/**
 * Шаги черновика редактора (заказчик 2026-10-24, 1#): пока черновик жив, каждое изменение — строкой; ушло
 * сообщение — шаги стёрты вместе с черновиком; «Сообщить о проблеме», не удалив черновик, — шаги в отчёте.
 *
 * **Только виды и числа** (ADR-0021: содержимое с устройства не уходит): «+виджет video», «кусков 4→5»,
 * «набор ×12», — ни текста, ни адресов, ни названий. Хранятся рядом с черновиком, тем же ключом покоя.
 */
object DraftSteps {

    /** Сколько последних шагов держать на черновик (В2#). */
    const val LIMIT = 200

    /** Ключ шагов в черновиках — к ключу переписки. */
    const val SUFFIX = "|editor.steps"

    /** Вид виджета — словом для шага: имя класса, строчными. */
    fun kindOf(w: ComposedWidget): String = w::class.simpleName.orEmpty().lowercase()

    /**
     * Что изменилось от [before] к [after] — словами без содержимого; ничего видимого — `null`. Виджеты по
     * видам («+video», «−link ×2»), число кусков, иначе — набор (сменилась длина текста) или правка (вид,
     * стиль, свойства).
     */
    fun step(before: ComposedDoc?, after: ComposedDoc?): String? {
        if (before == after) return null
        val was = before?.let(::kinds).orEmpty()
        val now = after?.let(::kinds).orEmpty()
        val parts = mutableListOf<String>()
        for (k in (was.keys + now.keys).sorted()) {
            val d = (now[k] ?: 0) - (was[k] ?: 0)
            if (d > 0) parts += "+$k" + if (d > 1) " ×$d" else ""
            if (d < 0) parts += "−$k" + if (d < -1) " ×${-d}" else ""
        }
        val itemsWas = before?.items?.size ?: 0
        val itemsNow = after?.items?.size ?: 0
        if (itemsWas != itemsNow) parts += "кусков $itemsWas→$itemsNow"
        if (parts.isNotEmpty()) return parts.joinToString(", ")
        return if (textLength(before) != textLength(after)) TYPING else "правка"
    }

    /** Добавить строку [line] к шагам [lines]: набор подряд — одной строкой со счётом; не больше [LIMIT]. */
    fun append(lines: List<String>, line: String): List<String> {
        val last = lines.lastOrNull()
        val merged = if (last != null && line.endsWith(" $TYPING") && sameTyping(last)) {
            val n = (Regex(" ×(\\d+)$").find(last)?.groupValues?.get(1)?.toInt() ?: 1) + 1
            lines.dropLast(1) + (line + " ×$n")
        } else {
            lines + line
        }
        return merged.takeLast(LIMIT)
    }

    /** Шаги всех неотправленных черновиков — для отчёта: «черновик 1», «черновик 2», без номеров переписок (В3#). */
    fun report(all: List<String>): String {
        val drafts = all.filter { it.isNotBlank() }
        if (drafts.isEmpty()) return ""
        return buildString {
            append("── Шаги черновиков редактора ──\n")
            drafts.forEachIndexed { i, steps ->
                append("черновик ${i + 1}:\n")
                append(steps.trimEnd()).append('\n')
            }
        }
    }

    /** Виджеты, которых в [before] не было, а в [after] есть (одинаковые считаются поштучно). */
    fun added(before: ComposedDoc?, after: ComposedDoc?): List<ComposedWidget> {
        val pool = before?.let(ComposedMedia::widgets).orEmpty().toMutableList()
        return after?.let(ComposedMedia::widgets).orEmpty().filter { !pool.remove(it) }
    }

    /** Слова шагов — их читает чинящий в отчёте, на язык человека они не переводятся (как тело отчёта). */
    const val OPENED = "открыт"
    const val CLOSED = "закрыт"
    fun copy(kind: String) = "возможная копия: $kind"
    fun sentCopy(kind: String) = "сообщение $kind"
    fun repeat(tag: String, skipped: Int) = "повтор нажатия $tag: лишних $skipped"

    /** Строка шага: [time] — часы, [what] — шаг. */
    fun line(time: String, what: String): String = "$time $what"

    private const val TYPING = "набор"

    private fun sameTyping(last: String) = last.endsWith(" $TYPING") || Regex(" $TYPING ×\\d+$").containsMatchIn(last)

    private fun kinds(doc: ComposedDoc): Map<String, Int> = ComposedMedia.widgets(doc).groupingBy(::kindOf).eachCount()

    private fun textLength(doc: ComposedDoc?): Int = doc?.items.orEmpty().sumOf { item ->
        when (item) {
            is ComposedItem.Text -> item.source.length
            is ComposedItem.Row -> item.cells.sumOf { it.text.length }
            is ComposedItem.ItemList -> item.items.sumOf { it.title.length + it.text.length }
            else -> 0
        }
    }
}

/**
 * Проскочившая копия (заказчик 2026-10-24, 3#): тот же виджет — того же вида и с тем же адресом — меньше чем
 * через [windowMs] после такого же. Не запрет — сигнал в журнал: человек мог вставить ролик дважды нарочно,
 * а копия значит, что у какого-то окна нет защиты от двойного нажатия.
 */
class CopyWatch(private val windowMs: Long = 2_000) {
    private var last: Pair<String, Long>? = null

    /** [w] вставлен в [atMs]; копия только что вставленного — `true`. */
    fun seen(w: ComposedWidget, atMs: Long): Boolean {
        val key = keyOf(w)
        val prev = last
        last = key to atMs
        return prev != null && prev.first == key && atMs - prev.second in 0 until windowMs
    }

    /** Ключ сравнения: у виджетов по ссылке — вид и адрес (описание видео разводится « (2)»), у прочих — сам виджет. */
    private fun keyOf(w: ComposedWidget): String = when (w) {
        is ComposedWidget.Video -> "video " + w.url
        is ComposedWidget.Audio -> "audio " + w.url
        is ComposedWidget.Stream -> "stream " + w.url
        is ComposedWidget.Link -> "link " + w.url
        is ComposedWidget.Cloud -> "cloud " + w.url
        else -> w.toString()
    }
}
