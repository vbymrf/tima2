package io.tima.crypto

import io.tima.crypto.proto.MediaEdit
import io.tima.crypto.proto.MediaRef
import io.tima.crypto.proto.MessageBody
import io.tima.crypto.proto.NodeEdit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Новый текст одного узла исходного сообщения (ADR-0045 §4).
 *
 * @param index позиция узла в `nodes` исходного — не идентификатор: у сообщения без разметки
 *   идентификаторов нет, а узел есть.
 * @param entities инлайн-оформление узла целиком; прежние сущности узла им заменяются.
 */
data class NodeChange(val index: Int, val text: String, val entities: List<MarkupEntity> = emptyList())

/**
 * Новый файл на месте файла исходного (ADR-0052): фото, изменённое в просмотре, — целиком новый
 * номер, ключ и размеры. [index] — позиция в `media` исходного.
 */
data class MediaChange(val index: Int, val media: MediaRef)

/**
 * Правка сообщения — тело записи правки и её наложение на исходное (ADR-0045; Решения §8
 * doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md).
 *
 * Правка — новая запись, а не замена: внутри только изменённые узлы, строение исходного (блоки,
 * ряды, ячейки) на месте. Номер исходного едет открыто, под подписью (версия 3 конверта).
 */
object MessageEdits {

    /** Сколько правок принимает сообщение; четвёртую сервер не примет. */
    const val MAX_EDITS = 3

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val entitiesSerializer = ListSerializer(MarkupEntity.serializer())

    /** Тело правки: `zstd(protobuf(MessageBody{edits}))` — тот же конвейер, что у сообщения. */
    fun encode(changes: List<NodeChange>): ByteArray = MessageSerializer.encodeBody(body(changes))

    fun body(changes: List<NodeChange>): MessageBody = MessageBody(
        edits = changes.map {
            NodeEdit(
                index = it.index,
                text = it.text,
                entities = if (it.entities.isEmpty()) "" else json.encodeToString(entitiesSerializer, it.entities),
            )
        },
    )

    /** Тело правки, заменяющей файлы исходного (ADR-0052): узлы и строение не трогаются. */
    fun encodeMedia(changes: List<MediaChange>): ByteArray = MessageSerializer.encodeBody(
        MessageBody(media_edits = changes.map { MediaEdit(index = it.index, media = it.media) }),
    )

    /** Замены файлов из тела правки; без файла — пропускается. */
    fun mediaFrom(body: MessageBody): List<MediaChange> =
        body.media_edits.mapNotNull { e -> e.media?.let { MediaChange(e.index, it) } }

    /**
     * Исходное с заменёнными файлами, по порядку: вторая замена того же файла перекрывает первую.
     * Позиция вне исходного пропускается.
     */
    fun applyMedia(original: MessageContent, edits: List<List<MediaChange>>): MessageContent {
        val all = edits.flatten()
        if (all.isEmpty()) return original
        val media = original.media.toMutableList()
        for (change in all) if (change.index in media.indices) media[change.index] = change.media
        return original.copy(media = media)
    }

    /** Обратный ход. Испорченное оформление узла не губит правку: текст важнее. */
    fun decode(payload: ByteArray): Result<List<NodeChange>> = MessageSerializer.decodeBody(payload).map(::from)

    fun from(body: MessageBody): List<NodeChange> = body.edits.map {
        NodeChange(
            index = it.index,
            text = it.text,
            entities = if (it.entities.isBlank()) emptyList()
            else runCatching { json.decodeFromString(entitiesSerializer, it.entities) }.getOrDefault(emptyList()),
        )
    }

    /**
     * Исходное с правками, наложенными по порядку: вторая правка того же узла перекрывает первую.
     * Узел вне исходного пропускается — правку от чужого содержимого не показываем.
     */
    fun apply(original: MessageContent, edits: List<List<NodeChange>>): MessageContent {
        val nodes = original.nodes.toMutableList()
        var markup = original.markup?.takeIf { original.hasMarkup }
        for (change in edits.flatten()) {
            if (change.index !in nodes.indices) continue
            nodes[change.index] = change.text
            val m = markup ?: continue
            val id = m.nodeIdAt(change.index)
            if (id < 0) continue
            val fresh = change.entities
                .map { it.copy(nodeId = id) }
                .filter { it.start >= 0 && it.length >= 0 && it.start + it.length <= change.text.length }
            markup = m.copy(entities = m.entities.filter { it.nodeId != id } + fresh)
        }
        return original.copy(nodes = nodes, markup = markup ?: original.markup)
    }

    /**
     * Что изменилось между исходным и исправленным. `null` — строение разное: правка такого не
     * передаёт (Решения §8 «разметка на месте»), и клиент говорит это человеку. Пусто — ничего.
     */
    fun diff(original: MessageContent, edited: MessageContent): List<NodeChange>? {
        if (original.nodes.size != edited.nodes.size) return null
        val om = original.markup?.takeIf { original.hasMarkup }
        var em = edited.markup?.takeIf { edited.hasMarkup }
        if (om == null) {
            if (em != null) return null
            return original.nodes.indices.mapNotNull { i ->
                if (original.nodes[i] == edited.nodes[i]) null else NodeChange(i, edited.nodes[i])
            }
        }
        // Оформление сняли целиком с единственного абзаца — разбор вернул простой текст.
        // Строение то же: один абзац, один узел.
        if (em == null && om.n.size == 1 && om.blocks == listOf(MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = om.n))) {
            em = om.copy(entities = emptyList())
        }
        if (em == null || om.n != em.n || om.blocks != em.blocks || om.styles != em.styles) return null
        return original.nodes.indices.mapNotNull { i ->
            val id = om.nodeIdAt(i)
            val before = om.entities.filter { it.nodeId == id }
            val after = em.entities.filter { it.nodeId == id }
            if (original.nodes[i] == edited.nodes[i] && before == after) null
            else NodeChange(i, edited.nodes[i], after)
        }
    }
}
