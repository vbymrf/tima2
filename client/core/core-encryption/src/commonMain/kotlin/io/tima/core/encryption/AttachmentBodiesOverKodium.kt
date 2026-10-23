package io.tima.core.encryption

import io.tima.crypto.BlockType
import io.tima.crypto.Markup
import io.tima.crypto.MarkupBlock
import io.tima.crypto.MarkupWidget
import io.tima.crypto.MessageContent
import io.tima.crypto.MessageContentCodec
import io.tima.crypto.MessageSerializer
import io.tima.crypto.proto.MediaRef
import io.tima.domain.chat.AttachmentBodies
import io.tima.domain.chat.OutMedia
import okio.ByteString.Companion.toByteString

/**
 * Тела вложений (Решения §2–§6) — тем же конвейером `zstd(protobuf(MessageBody))`, что и текст.
 *
 * **Вне пузыря** (фото без текста, файл) — без разметки: узел пуст, файлы в `media`. **Карточки**
 * (картинка в группе, контакт, место, ссылка) — разметкой: ряд из одной ячейки во всю ширину,
 * виджет в ней (ADR-0044: виджеты живут в ячейках).
 */
object AttachmentBodiesOverKodium : AttachmentBodies {

    /** Файл для тела: сообщения и правки, заменяющей фото (ADR-0052). */
    internal fun ref(m: OutMedia) = MediaRef(
        media_id = m.mediaId,
        media_key = m.key.toByteString(),
        mime = m.mime,
        size_bytes = m.size,
        width = m.width,
        height = m.height,
        name = m.name,
        duration_ms = m.durationMs.toInt(),
        voice = m.voice,
        wave = (m.wave ?: ByteArray(0)).toByteString(),
    )

    private fun encode(content: MessageContent): ByteArray = MessageSerializer.encodeBody(MessageContentCodec.toBody(content))

    override fun album(photos: List<OutMedia>): ByteArray = encode(MessageContent(nodes = listOf(""), media = photos.map(::ref)))

    override fun file(file: OutMedia): ByteArray = encode(MessageContent(nodes = listOf(""), media = listOf(ref(file))))

    override fun voice(voice: OutMedia): ByteArray = encode(MessageContent(nodes = listOf(""), media = listOf(ref(voice))))

    private fun card(vararg widgets: MarkupBlock, media: List<MediaRef> = emptyList()): ByteArray = encode(
        MessageContent(
            nodes = listOf(""),
            markup = Markup(
                n = listOf(1),
                blocks = widgets.map { w ->
                    MarkupBlock(
                        type = BlockType.FLEX,
                        children = listOf(MarkupBlock(type = BlockType.CELL, style = mapOf("width" to "100"), children = listOf(w))),
                    )
                },
            ),
            media = media,
        ),
    )

    override fun inlineImages(images: List<OutMedia>): ByteArray = card(
        *images.indices.map { MarkupBlock(type = BlockType.IMAGE, widget = MarkupWidget(media = it)) }.toTypedArray(),
        media = images.map(::ref),
    )

    override fun contact(name: String, phone: String, userId: String): ByteArray =
        card(MarkupBlock(type = BlockType.CONTACT, widget = MarkupWidget(name = name, phone = phone, userId = userId)))

    override fun place(lat: Double, lon: Double, label: String): ByteArray =
        card(MarkupBlock(type = BlockType.MAP, widget = MarkupWidget(lat = lat, lon = lon, label = label)))

    override fun link(url: String, title: String, cloudKind: String): ByteArray =
        if (cloudKind.isNotEmpty()) {
            card(MarkupBlock(type = BlockType.CLOUD, widget = MarkupWidget(url = url, kind = cloudKind, title = title)))
        } else {
            card(MarkupBlock(type = BlockType.LINK, widget = MarkupWidget(url = url, mode = "plain", title = title)))
        }
}
