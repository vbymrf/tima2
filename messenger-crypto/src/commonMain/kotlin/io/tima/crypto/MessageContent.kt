package io.tima.crypto

import io.tima.crypto.proto.MediaRef
import io.tima.crypto.proto.MessageBody

/**
 * Содержимое сообщения в терминах ADR-0011: массив узлов + разметка.
 *
 * Служит мостом между продуктовой моделью и wire-форматом. Знать про поля protobuf
 * и про переходную совместимость должно одно место, а не каждый вызывающий.
 */
data class MessageContent(
    /** Текст узлами. Обычное сообщение — один узел. */
    val nodes: List<String>,
    /** Разметка; null у сообщения без оформления, а это большинство. */
    val markup: Markup? = null,
    val media: List<MediaRef> = emptyList(),
) {
    /** Весь текст одной строкой — склейка узлов как есть. */
    fun plainText(): String = nodes.joinToString("")

    /**
     * Текст для показа без оформления — список чатов, поиск, копирование, старые клиенты.
     *
     * У сообщения с разметкой узлы — это абзацы, заголовки, пункты, ячейки (ADR-0044 §2): склеенные
     * встык, «Заголовок» и абзац слиплись бы в одно слово. Поэтому между узлами — перевод строки.
     * Без разметки узел один, и это тот же текст.
     */
    fun displayText(): String = if (hasMarkup) nodes.filter { it.isNotEmpty() }.joinToString("\n") else plainText()

    val hasMarkup: Boolean get() = markup != null && markup != Markup.EMPTY

    /**
     * Текст для строки в списке чатов и для поиска — первый непустой узел (Решения §12). В
     * уведомление он не идёт никогда (ADR-0029 §5). `null` — текста нет: одни виджеты.
     */
    fun firstText(): String? = nodes.firstOrNull { it.isNotBlank() }?.trim()

    companion object {
        /** Обычное сообщение: один узел, никакой разметки. */
        fun text(text: String, media: List<MediaRef> = emptyList()) =
            MessageContent(nodes = listOf(text), media = media)
    }
}

/**
 * Перевод содержимого в wire-формат и обратно.
 *
 * # Переходная совместимость
 *
 * Пока в ходу клиенты, не знающие про узлы, отправитель кладёт в тело ОБА
 * представления: плоский `text` (склейка узлов) и `nodes` + `markup`. Старый клиент
 * прочитает `text` и покажет сообщение без оформления — это хуже, чем с оформлением,
 * но несравнимо лучше, чем пустое сообщение.
 *
 * Дублирование стоит примерно длины текста; после перехода поле `text` перестанет
 * заполняться отдельным решением, а не молча.
 */
object MessageContentCodec {

    /** Содержимое → тело protobuf, с плоским текстом для старых клиентов. */
    fun toBody(content: MessageContent): MessageBody = MessageBody(
        text = content.displayText(),
        // Сущности старого формата не восстанавливаем: смещения в UTF-16 — ровно то,
        // от чего уходим, и городить их обратно ради переходного периода значило бы
        // тащить их ошибки в новый код.
        entities = emptyList(),
        media = content.media,
        nodes = content.nodes,
        // Разметка — v2 (ADR-0048); не выражается — v1.
        markup = content.markup?.let { MarkupV2.encodeBody(it, content.nodes) }.orEmpty(),
    )

    /**
     * Тело protobuf → содержимое.
     *
     * Узлы есть — читаем их. Нет — сообщение от клиента старого формата: заворачиваем
     * плоский текст в один узел, чтобы дальше по коду был ровно один путь.
     */
    fun fromBody(body: MessageBody): MessageContent {
        val nodes = if (body.nodes.isNotEmpty()) body.nodes else listOf(body.text)
        return MessageContent(
            nodes = nodes,
            // Любая версия разметки: v2 (ADR-0048) и прежняя v1.
            markup = MarkupV2.decodeBody(body.markup, nodes),
            media = body.media,
        )
    }
}
