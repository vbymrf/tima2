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
    /** Весь текст одной строкой — для превью, поиска и старых клиентов. */
    fun plainText(): String = nodes.joinToString("")

    val hasMarkup: Boolean get() = markup != null && markup != Markup.EMPTY

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
        text = content.plainText(),
        // Сущности старого формата не восстанавливаем: смещения в UTF-16 — ровно то,
        // от чего уходим, и городить их обратно ради переходного периода значило бы
        // тащить их ошибки в новый код.
        entities = emptyList(),
        media = content.media,
        nodes = content.nodes,
        markup = content.markup?.let { Markup.encode(it) }.orEmpty(),
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
            markup = Markup.decode(body.markup),
            media = body.media,
        )
    }
}
