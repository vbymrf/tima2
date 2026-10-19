package io.tima.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Разметка v2 (ADR-0048, ADR-0049): блоки идут линейно, в порядке документа; блок с текстом называет
 * свои узлы сам — `"n":3`, у цитаты из нескольких строк `"n":[3,4]` (номер — место узла в теле, с
 * единицы). Отрезки оформления — внутри своего блока, коротко: `"e":[["b",7,6],…]`. Свойства блока —
 * полными именами, как в v1.
 *
 * Внутри клиента модель одна — [Markup] (v1): v2 — только её запись. Поэтому разбор, проверка
 * ([MarkupRules]), показ и правка по узлам от версии не зависят. Ссылку на несуществующий узел
 * ловит [MarkupRules], а не чтение: ошибка остаётся у своего блока.
 *
 * Не выражается в v2 (отрезок без своего блока, узел не из тела) — пишется v1: она по-прежнему
 * принимается.
 */
object MarkupV2 {

    const val VERSION = 2

    private val json = Json { ignoreUnknownKeys = true }

    /** Вид отрезка — коротко (ADR-0048 §2). */
    private val SHORT = mapOf(
        EntityType.BOLD to "b",
        EntityType.ITALIC to "i",
        EntityType.UNDERLINE to "u",
        EntityType.STRIKETHROUGH to "s",
        EntityType.HIGHLIGHT to "h",
        EntityType.CODE to "c",
        EntityType.LINK to "l",
        EntityType.MENTION to "m",
        EntityType.HASHTAG to "t",
    )
    private val LONG = SHORT.entries.associate { (k, v) -> v to k }

    // ── Запись ───────────────────────────────────────────────────────────────

    /**
     * Разметка тела: v2, а не выражается — v1 ([Markup.encode]). [nodes] — тексты узлов: границы
     * отрезков многострочного блока считаются по ним.
     */
    fun encodeBody(markup: Markup, nodes: List<String>): String {
        if (markup == Markup.EMPTY) return ""
        return encode(markup, nodes) ?: Markup.encode(markup)
    }

    /** Запись v2; `null` — не выражается. */
    fun encode(markup: Markup, nodes: List<String>): String? {
        // Номер узла в v2 — его место; свои идентификаторы (не 1, 2, 3…) сохраняет только v1.
        if (markup.n.isNotEmpty() && markup.n != (1..nodes.size).toList()) return null
        val byNode = markup.entities.groupBy { it.nodeId }
        var used = 0

        fun block(b: MarkupBlock): JsonObject? {
            // Номер узла в v2 — его место в теле.
            val places = b.nodeIds.map { id -> markup.indexOf(id).takeIf { it in nodes.indices } ?: return null }
            val spans = mutableListOf<JsonElement>()
            var base = 0
            b.nodeIds.forEachIndexed { k, id ->
                for (e in byNode[id].orEmpty()) {
                    spans.add(span(e, base) ?: return null)
                    used++
                }
                base += nodes[places[k]].length + 1
            }
            val children = b.children.map { block(it) ?: return null }
            return buildJsonObject {
                if (b.type != BlockType.PARAGRAPH) put("type", b.type.wire)
                when (places.size) {
                    0 -> Unit
                    1 -> put("n", places.single() + 1)
                    else -> put("n", JsonArray(places.map { JsonPrimitive(it + 1) }))
                }
                if (b.level != 0) put("level", b.level)
                if (b.style.isNotEmpty()) put("style", JsonObject(b.style.mapValues { JsonPrimitive(it.value) }))
                b.widget?.let { put("widget", json.encodeToJsonElement(MarkupWidget.serializer(), it)) }
                if (children.isNotEmpty()) put("children", JsonArray(children))
                if (spans.isNotEmpty()) put("e", JsonArray(spans))
            }
        }

        val blocks = markup.blocks.map { block(it) ?: return null }
        // Каждый отрезок — при узле своего блока.
        if (used != markup.entities.size) return null
        return buildJsonObject {
            put("v", VERSION)
            put("blocks", JsonArray(blocks))
            markup.styles?.let { put("styles", json.encodeToJsonElement(MarkupStyles.serializer(), it)) }
        }.toString()
    }

    /** Отрезок: `[вид, начало, длина]`, у ссылки — адрес, у упоминания — ник и человек, у метки — слово. */
    private fun span(e: MarkupEntity, base: Int): JsonArray? {
        val code = SHORT[e.type] ?: return null
        return buildJsonArray {
            add(JsonPrimitive(code))
            add(JsonPrimitive(base + e.start))
            add(JsonPrimitive(e.length))
            when (e.type) {
                EntityType.LINK -> {
                    if (e.attribute.isNotEmpty() || e.userId.isNotEmpty()) return null
                    if (e.url.isNotEmpty()) add(JsonPrimitive(e.url))
                }
                EntityType.MENTION -> {
                    if (e.url.isNotEmpty()) return null
                    if (e.attribute.isNotEmpty() || e.userId.isNotEmpty()) add(JsonPrimitive(e.attribute))
                    if (e.userId.isNotEmpty()) add(JsonPrimitive(e.userId))
                }
                EntityType.HASHTAG -> {
                    if (e.url.isNotEmpty() || e.userId.isNotEmpty()) return null
                    if (e.attribute.isNotEmpty()) add(JsonPrimitive(e.attribute))
                }
                else -> if (e.url.isNotEmpty() || e.userId.isNotEmpty() || e.attribute.isNotEmpty()) return null
            }
        }
    }

    // ── Чтение ───────────────────────────────────────────────────────────────

    /**
     * Разметка тела любой версии: `"v":2` — v2, иначе v1. Испорченная — `null`: сообщение
     * показывается без оформления (ADR-0011).
     */
    fun decodeBody(raw: String, nodes: List<String>): Markup? {
        if (raw.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val version = root["v"]?.jsonPrimitive?.intOrNull ?: return Markup.decode(raw)
        if (version != VERSION) return null
        return runCatching { decode(root, nodes) }.getOrNull()
    }

    private fun decode(root: JsonObject, nodes: List<String>): Markup {
        val entities = mutableListOf<MarkupEntity>()
        var referenced = false

        fun block(o: JsonObject): MarkupBlock {
            val type = o["type"]?.jsonPrimitive?.contentOrNull?.let(BlockType::fromWire) ?: BlockType.PARAGRAPH
            val ids = when (val n = o["n"]) {
                null -> emptyList()
                is JsonArray -> n.map { it.jsonPrimitive.intOrNull ?: error("номер узла не число") }
                else -> listOf(n.jsonPrimitive.intOrNull ?: error("номер узла не число"))
            }
            if (ids.isNotEmpty()) referenced = true
            // Границы отрезка — по узлам блока, сложенным через перевод строки. Узла нет — его
            // поймает проверка разметки, отрезки в нём не читаются.
            val starts = mutableListOf<Int>()
            var base = 0
            for (id in ids) {
                starts += base
                base += (nodes.getOrNull(id - 1)?.length ?: 0) + 1
            }
            for (s in o["e"]?.jsonArray.orEmpty()) {
                val a = s.jsonArray
                val kind = LONG[a[0].jsonPrimitive.content] ?: EntityType.UNKNOWN
                val start = a[1].jsonPrimitive.intOrNull ?: error("нет начала")
                val length = a[2].jsonPrimitive.intOrNull ?: error("нет длины")
                val k = starts.indexOfLast { it <= start }
                if (k < 0) continue
                val extra1 = a.getOrNull(3)?.jsonPrimitive?.contentOrNull.orEmpty()
                val extra2 = a.getOrNull(4)?.jsonPrimitive?.contentOrNull.orEmpty()
                entities += MarkupEntity(
                    type = kind,
                    nodeId = ids[k],
                    start = start - starts[k],
                    length = length,
                    url = if (kind == EntityType.LINK) extra1 else "",
                    attribute = if (kind == EntityType.MENTION || kind == EntityType.HASHTAG) extra1 else "",
                    userId = if (kind == EntityType.MENTION) extra2 else "",
                )
            }
            val children = o["children"]?.jsonArray.orEmpty().map { block(it.jsonObject) }
            return MarkupBlock(
                type = type,
                nodeIds = ids,
                children = children,
                level = o["level"]?.jsonPrimitive?.intOrNull ?: 0,
                style = o["style"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content },
                widget = o["widget"]?.let { json.decodeFromJsonElement(MarkupWidget.serializer(), it) },
            )
        }

        val blocks = root["blocks"]?.jsonArray.orEmpty().map { block(it.jsonObject) }
        // Сообщение без текста (один виджет): узлов нет, а тело отдаёт один пустой — так читается
        // тело без узлов с самого начала (MessageContentCodec.fromBody).
        val none = !referenced && nodes.size == 1 && nodes[0].isEmpty()
        return Markup(
            n = if (none) emptyList() else (1..nodes.size).toList(),
            // Порядок — как записан: по блокам, внутри блока — как у пишущего.
            entities = entities,
            blocks = blocks,
            styles = root["styles"]?.let { json.decodeFromJsonElement(MarkupStyles.serializer(), it) },
        )
    }
}
