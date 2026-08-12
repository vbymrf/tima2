package io.tima.crypto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Разметка сообщения (ADR-0011).
 *
 * # Почему идентификаторы, а не позиции
 *
 * Соблазнительно считать номер узла в массиве его идентификатором — тогда ничего
 * лишнего хранить не надо. Так делать нельзя: вставка узла в середину сдвигает все
 * последующие номера, и каждая ссылка из блока начинает указывать не туда, хотя сама
 * разметка не менялась. Порядок узлов даёт массив, идентичность — список [n].
 *
 * # Почему разметки обычно нет
 *
 * У сообщения без оформления [Markup] отсутствует целиком — а это подавляющее
 * большинство сообщений. Нет разметки → некому ссылаться на узлы → идентификаторы не
 * нужны и не занимают места.
 *
 * # Формат
 *
 * Один формат с читаемыми ключами. Сокращённого с однобуквенными ключами нет
 * намеренно: тело сжимается zstd до шифрования, а повторяющиеся ключи — идеальный
 * корм для словарного сжатия. Второй формат стоил бы таблицы соответствий, синхронной
 * между клиентом, сервером и редактором, и класса ошибок, где они разъехались.
 */
@Serializable
data class Markup(
    /** Версия раскладки разметки; растёт при несовместимых изменениях. */
    val version: Int = 1,
    /**
     * Идентификаторы узлов, параллельно массиву `nodes`: `n[i]` — идентификатор
     * узла `nodes[i]`. Значения не переиспользуются в пределах документа.
     */
    val n: List<Int> = emptyList(),
    /** Инлайн-сущности: жирный, ссылка, упоминание и прочее. */
    val entities: List<MarkupEntity> = emptyList(),
    /** Блоки: заголовки, абзацы, списки, контейнеры, таблицы. */
    val blocks: List<MarkupBlock> = emptyList(),
    /** Стили: глобальные для документа и по типам сущностей. */
    val styles: MarkupStyles? = null,
) {
    /** Идентификатор узла по его позиции; -1, если разметка о нём не знает. */
    fun nodeIdAt(index: Int): Int = n.getOrElse(index) { -1 }

    /** Позиция узла по идентификатору; -1, если такого нет. */
    fun indexOf(nodeId: Int): Int = n.indexOf(nodeId)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true // новые поля не должны ломать старых читателей
            encodeDefaults = false // пустые списки в тело не пишем
        }

        val EMPTY = Markup()

        fun encode(markup: Markup): String =
            if (markup == EMPTY) "" else json.encodeToString(serializer(), markup)

        /**
         * Разбор разметки. Испорченная или незнакомая разметка — НЕ повод потерять
         * сообщение: возвращаем null, текст показывается без оформления.
         */
        fun decode(raw: String): Markup? =
            if (raw.isBlank()) null else runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()

        /**
         * Раздаёт узлам идентификаторы: 1, 2, 3… Годится для нового документа;
         * при правке существующего новые узлы обязаны получать номера ПОСЛЕ
         * максимального использованного, иначе идентификатор укажет на чужой узел.
         */
        fun idsFor(nodeCount: Int): List<Int> = (1..nodeCount).toList()

        /** Следующий свободный идентификатор — для узлов, добавленных при правке. */
        fun nextId(markup: Markup): Int = (markup.n.maxOrNull() ?: 0) + 1
    }
}

/**
 * Инлайн-сущность внутри узла. Границы — в символах ЭТОГО узла, не всего текста.
 * [type] — закрытый каталог ([EntityType], ADR-0011 «Что осталось за рамками»).
 */
@Serializable
data class MarkupEntity(
    val type: EntityType,
    @SerialName("node") val nodeId: Int,
    val start: Int = 0,
    val length: Int = 0,
    val url: String = "",
    @SerialName("user_id") val userId: String = "",
    val attribute: String = "",
)

/**
 * Блок документа. Ссылается на узлы по идентификаторам; [children] позволяет
 * вложенность (контейнер → абзац → узлы). [type] — закрытый каталог ([BlockType]).
 */
@Serializable
data class MarkupBlock(
    val type: BlockType,
    @SerialName("nodes") val nodeIds: List<Int> = emptyList(),
    val children: List<MarkupBlock> = emptyList(),
    /** Уровень заголовка, номер колонки и подобное — по типу блока. */
    val level: Int = 0,
    /** Переопределение стиля именно для этого блока. */
    val style: Map<String, String> = emptyMap(),
)

/**
 * Стили документа. Уровень чата и сообщества сюда НЕ входит — они живут в настройках
 * и применяются при отрисовке (ADR-0011 §9): иначе каждое сообщение таскало бы с
 * собой копию оформления чата, которая устаревала бы при первой же его смене.
 */
@Serializable
data class MarkupStyles(
    /** Общие для всего документа. */
    val global: Map<String, String> = emptyMap(),
    /** По типам сущностей: все цитаты, все ссылки и так далее. */
    @SerialName("by_type") val byType: Map<String, Map<String, String>> = emptyMap(),
)
