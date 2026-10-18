package io.tima.core.encryption

import io.tima.crypto.MessageContent
import io.tima.crypto.MessageContentCodec
import io.tima.crypto.MessageEdits
import io.tima.crypto.MessageReplies
import io.tima.crypto.MarkupRef
import io.tima.domain.chat.ReplyTarget
import io.tima.crypto.MarkupRules
import io.tima.crypto.MessageSerializer
import io.tima.crypto.QuickMarkdown
import io.tima.domain.chat.DecodedLine
import io.tima.domain.chat.EditBody
import io.tima.domain.chat.MessageBodyCodec

/**
 * Упаковка тела сообщения — переходник к порту `domain-chat`.
 *
 * `zstd(protobuf(MessageBody))`, **один кодек на провод и на диск**. В v1 тело
 * склеивалось разделителем `\n\0`, и разметка через такую склейку не проходила вовсе
 * (Plan.md §3.4); здесь тот же вызов служит и отправке, и записи в базу — разойтись
 * им негде.
 *
 * Отправитель кладёт в тело **оба** представления текста: узлы и плоскую склейку.
 * Клиент, не знающий про узлы, прочитает плоский текст и покажет сообщение без
 * оформления — это хуже, чем с оформлением, и несравнимо лучше, чем пустое сообщение.
 */
object TextBodyCodec : MessageBodyCodec {

    override fun encodeText(text: String): ByteArray =
        MessageSerializer.encodeBody(MessageContentCodec.toBody(MessageContent.text(text)))

    /**
     * Набранный текст с быстрым оформлением (ПЛАН-(РС) РС2). Разбор не прошёл строгую проверку
     * (предел разметки и прочее) — уходит текстом как есть: лучше без оформления, чем никак.
     */
    override fun encodeTyped(text: String): ByteArray {
        val parsed = QuickMarkdown.parse(text)
        val content = if (MarkupRules.check(parsed, strict = true).isEmpty()) parsed else MessageContent.text(text)
        return MessageSerializer.encodeBody(MessageContentCodec.toBody(content))
    }

    override fun decodeLine(body: ByteArray): DecodedLine? = CodecWatch.timed(
        CodecWatch.RENDER_SLOW_MS,
        { decode(body).getOrNull()?.let { DecodedLine(it.displayText(), RichMapper.from(it)) } },
    ) { line, ms -> CodecWatch.slowRender?.invoke(line?.rich?.blocks?.size ?: 0, ms) }

    /** Исходное с правками (ADR-0045). Нечитаемая правка пропускается — исходное важнее. */
    override fun decodeEdited(body: ByteArray, edits: List<ByteArray>): DecodedLine? =
        edited(body, edits)?.let { DecodedLine(it.displayText(), RichMapper.from(it)) }

    override fun editSource(body: ByteArray, edits: List<ByteArray>): String? =
        edited(body, edits)?.let { QuickMarkdown.source(MessageReplies.detach(it)) }

    /**
     * Набранное с блоком ответа первым (ADR-0044 `reply`). Не прошло строгую проверку — ответ к
     * тексту как есть: лучше без оформления, чем без ответа.
     */
    override fun encodeReply(text: String, marks: Boolean, reply: ReplyTarget): ByteArray {
        val ref = MessageReplies.Reply(MarkupRef(kind = reply.kind, container = reply.chatId, message = reply.messageId), reply.userId)
        val typed = if (marks) QuickMarkdown.parse(text) else QuickMarkdown.plain(text)
        val withReply = MessageReplies.attach(typed, ref).takeIf { MarkupRules.check(it, strict = true).isEmpty() }
            ?: MessageReplies.attach(MessageContent.text(text), ref)
        return MessageSerializer.encodeBody(MessageContentCodec.toBody(withReply))
    }

    /**
     * Правка считается от того, что человек видит: исходное с прежними правками. У сообщения без
     * оформления знаки остаются текстом — строение (абзац без разметки) правка не меняет.
     */
    override fun encodeEdit(body: ByteArray, edits: List<ByteArray>, typed: String, marks: Boolean): EditBody {
        // Ответ правкой не меняется: блок ответа узлов не занимает, разница — по тексту без него.
        val current = edited(body, edits)?.let(MessageReplies::detach) ?: return EditBody.Unsupported
        val next = if (marks && current.hasMarkup) QuickMarkdown.parse(typed) else QuickMarkdown.plain(typed)
        if (MarkupRules.check(next, strict = true).isNotEmpty()) return EditBody.Structure
        val changes = MessageEdits.diff(current, next) ?: return EditBody.Structure
        if (changes.isEmpty()) return EditBody.Same
        return EditBody.Changes(MessageEdits.encode(changes))
    }

    private fun edited(body: ByteArray, edits: List<ByteArray>): MessageContent? {
        val original = decode(body).getOrNull() ?: return null
        return MessageEdits.apply(original, edits.mapNotNull { MessageEdits.decode(it).getOrNull() })
    }

    /**
     * Обратный ход — для входящих.
     *
     * @return содержимое либо неудача. Именно `Result`, а не исключение: нечитаемое
     *   сообщение — обычное состояние входящей машины ([io.tima.core.outbox.Inbox]),
     *   а не поломка.
     */
    /**
     * Текст для показа на экране.
     *
     * Берётся плоская склейка узлов: клиент, не знающий про узлы, покажет сообщение без
     * оформления — это хуже, чем с оформлением, и несравнимо лучше, чем пустое место.
     * Разметка приедет вместе с редактором.
     */
    override fun decodeText(body: ByteArray): String? = decode(body).getOrNull()?.let { c ->
        // Без текста, с файлами — метка для списка: «Фото», «Файл: имя» скажет экран (Решения §12).
        if (c.plainText().isBlank() && c.media.isNotEmpty()) {
            val photos = c.media.count { it.mime.startsWith("image/") && it.name.isEmpty() }
            if (photos == c.media.size) {
                io.tima.domain.chat.MediaSummary.of(io.tima.domain.chat.MediaSummary.Kind.PHOTO, photos)
            } else {
                val file = c.media.first { it.name.isNotEmpty() || !it.mime.startsWith("image/") }
                io.tima.domain.chat.MediaSummary.of(io.tima.domain.chat.MediaSummary.Kind.FILE, c.media.size, file.name)
            }
        } else {
            c.displayText().ifBlank { c.markup?.blocks?.let(::cardSummary) ?: "" }
        }
    }

    /** Карточка без текста — контакт, место, ссылка: первая встреченная в разметке (Решения §12). */
    private fun cardSummary(blocks: List<io.tima.crypto.MarkupBlock>): String? {
        for (b in blocks) {
            val w = b.widget
            val found = when (b.type) {
                io.tima.crypto.BlockType.CONTACT -> io.tima.domain.chat.MediaSummary.of(io.tima.domain.chat.MediaSummary.Kind.CONTACT, 1, w?.name.orEmpty())
                io.tima.crypto.BlockType.MAP -> io.tima.domain.chat.MediaSummary.of(io.tima.domain.chat.MediaSummary.Kind.PLACE, 1, w?.label.orEmpty())
                io.tima.crypto.BlockType.LINK, io.tima.crypto.BlockType.CLOUD -> io.tima.domain.chat.MediaSummary.of(
                    io.tima.domain.chat.MediaSummary.Kind.LINK,
                    1,
                    w?.title?.ifBlank { null } ?: w?.url.orEmpty().substringAfter("://").substringBefore('/').removePrefix("www."),
                )
                else -> cardSummary(b.children)
            }
            if (found != null) return found
        }
        return null
    }

    fun decode(payload: ByteArray): Result<MessageContent> =
        MessageSerializer.decodeBody(payload).map { MessageContentCodec.fromBody(it) }
}
