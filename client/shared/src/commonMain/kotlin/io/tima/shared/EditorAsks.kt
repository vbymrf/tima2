package io.tima.shared

import androidx.compose.runtime.Composable
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.LinkCards
import io.tima.domain.chat.OutMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Вопрос перед вставкой виджета редактором (Решения §10.4): ссылка трёх видов, облако с видом
 * файла, место вручную, контакт вручную. Общий для переписки и личной страницы.
 *
 * @param ask `link`, `cloud`, `place`, `contact`.
 * @param cardImage картинку карточки — перекодировать и загрузить; `null` — карточка без картинки
 *   (у записи страницы файлов нет).
 */
@Composable
internal fun EditorAskSheet(
    ask: String,
    scope: CoroutineScope,
    cards: LinkCards,
    cardImage: (suspend (ByteArray) -> OutMedia?)?,
    put: (ComposedWidget) -> Unit,
    onNotice: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val cw = Tima.words.chat
    val ew = Tima.words.editor
    val (title, fields, options) = when (ask) {
        "link" -> Triple(cw.linkAsk, listOf(ew.linkAddress, ew.linkTitle), listOf("plain", "button", "card").map { it to ew.linkMode(it) })
        "cloud" -> Triple(cw.linkPlace, listOf(ew.linkAddress, ew.linkTitle), listOf("file", "document", "audio", "video").map { it to cw.cloudKind(it) })
        "place" -> Triple(cw.attachItem("place"), listOf(cw.placeAsk), emptyList())
        else -> Triple(cw.attachItem("contact"), listOf(cw.contact, "+7"), emptyList())
    }
    val cardFailed = ew.cardFailed
    val placeAsk = cw.placeAsk
    io.tima.feature.chat.AskSheet(
        title = title,
        fields = fields,
        ok = cw.ok,
        cancel = cw.cancel,
        options = options,
        onCancel = onClose,
        onDone = { v ->
            val url = v[0].trim().let { if (it.isEmpty() || "://" in it) it else "https://$it" }
            when (ask) {
                "link" -> if (url.isNotEmpty()) {
                    onClose()
                    val mode = v.getOrNull(2) ?: "plain"
                    if (mode != "card") {
                        put(ComposedWidget.Link(url, v[1], mode))
                    } else {
                        // Карточку собирает отправитель (Решения §4): страница — сюда, картинка перекодируется и уходит в тело.
                        scope.launch {
                            val card = cards.card(url)
                            if (card == null) {
                                onNotice(cardFailed)
                                put(ComposedWidget.Link(url, v[1], "button"))
                                return@launch
                            }
                            val image = card.image?.let { bytes -> cardImage?.invoke(bytes) }
                            put(ComposedWidget.Link(url, v[1].ifBlank { card.title }, "card", card.description, image))
                        }
                    }
                }
                "cloud" -> if (url.isNotEmpty()) {
                    onClose()
                    put(ComposedWidget.Cloud(url, v.getOrNull(2) ?: "file", v[1]))
                }
                "place" -> io.tima.core.media.PlaceText.parse(v[0])?.let { (lat, lon) ->
                    onClose()
                    put(ComposedWidget.Place(lat, lon))
                } ?: onNotice(placeAsk)
                else -> if (v[0].isNotBlank() || v[1].isNotBlank()) {
                    onClose()
                    put(ComposedWidget.Contact(v[0], v[1]))
                }
            }
        },
    )
}
