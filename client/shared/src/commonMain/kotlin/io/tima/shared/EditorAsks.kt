package io.tima.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
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
    /** Карточки ссылок включены в «Память и трафик» (Решения §4); выключены — вида «карточка» нет. */
    cardsOn: Boolean = true,
    /** Цвет ссылки, выбранный в прошлый раз (имя палитры; пусто — цвет ссылок у читающего). */
    linkColor: String = "",
    /** Выбран цвет ссылки — запомнить до следующего раза (заказчик 2026-10-19). */
    onLinkColor: (String) -> Unit = {},
    /** Документ редактора — описание видео и аудио из «Облака» не должно совпасть с уже вставленным. */
    doc: io.tima.domain.chat.ComposedDoc? = null,
    /**
     * «Облако» видео или аудио → наш виджет и, если отмечено «Добавить метки», форма меток к нему без
     * числа (заказчик 2026-10-24). `null` — виджетов медиа здесь нет (личная страница): облако как было.
     */
    onMarks: ((bind: String) -> Unit)? = null,
) {
    var color by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(linkColor) }
    var withMarks by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val cw = Tima.words.chat
    val ew = Tima.words.editor
    val modes = if (cardsOn) listOf("plain", "button", "card") else listOf("plain", "button")
    val (title, fields, options) = when (ask) {
        "link" -> Triple(cw.linkAsk, listOf(ew.linkAddress, ew.linkTitle), modes.map { it to ew.linkMode(it) })
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
        extra = when {
            ask == "link" -> ({ now ->
                // Ушли с поля адреса, а по нему файл (по окончанию) — сказать об этом, без предупреждений
                // (заказчик 2026-10-24, 2а#): отправитель видит, что отправляет не страницу.
                val url = now.values.firstOrNull().orEmpty()
                if (0 in now.left && url.isNotBlank()) {
                    val full = if ("://" in url) url else "https://$url"
                    if (io.tima.domain.chat.FileKinds.isFile(full, null)) {
                        val kind = io.tima.domain.chat.FileKinds.ofName(io.tima.domain.chat.FileKinds.nameOf(full))
                        io.tima.core.ui.Tertiary(cw.linkFileIs(cw.fileKindName(kind.key)), modifier = androidx.compose.ui.Modifier.testTag(LINK_FILE_TAG))
                    }
                }
                io.tima.feature.chat.PaletteRow(color, "chat:ask.color", only = io.tima.feature.chat.LinkColors) { color = it.orEmpty() }
            })
            // Окно «Облака» прежнее; ушли с поля ссылки — вид файла по ней, без предупреждений (отправителю
            // их не показываем, заказчик 2026-10-24); у видео и аудио — ещё «Добавить метки».
            ask == "cloud" -> ({ now ->
                val url = now.values.firstOrNull().orEmpty()
                if (0 in now.left && url.isNotBlank()) {
                    val kind = io.tima.domain.chat.FileKinds.ofName(io.tima.domain.chat.FileKinds.nameOf(if ("://" in url) url else "https://$url"))
                    io.tima.core.ui.Tertiary(cw.cloudFileIs(cw.fileKindName(kind.key)), modifier = androidx.compose.ui.Modifier.testTag(CLOUD_KIND_TAG))
                }
                if (onMarks != null && (now.chosen == "video" || now.chosen == "audio")) {
                    io.tima.core.ui.Chip(
                        ew.cloudMarks,
                        kind = if (withMarks) io.tima.core.ui.ChipKind.Selected else io.tima.core.ui.ChipKind.Quiet,
                        onClick = { withMarks = !withMarks },
                        modifier = androidx.compose.ui.Modifier.testTag(CLOUD_MARKS_TAG),
                    )
                }
            })
            else -> null
        },
        onCancel = onClose,
        onDone = { v ->
            val url = v[0].trim().let { if (it.isEmpty() || "://" in it) it else "https://$it" }
            when (ask) {
                "link" -> if (url.isNotEmpty()) {
                    onClose()
                    onLinkColor(color)
                    val mode = v.getOrNull(2) ?: "plain"
                    if (mode != "card") {
                        put(ComposedWidget.Link(url, v[1], mode, color = color))
                    } else {
                        // Карточку собирает отправитель (Решения §4): страница — сюда, картинка перекодируется и уходит в тело.
                        scope.launch {
                            val card = cards.card(url)
                            if (card == null) {
                                // По ссылке файл — карточку собрать не из чего; сказать почему (2а#).
                                onNotice(if (io.tima.domain.chat.FileKinds.isFile(url, null)) cw.cardIsFile else cardFailed)
                                put(ComposedWidget.Link(url, v[1], "button", color = color))
                                return@launch
                            }
                            val image = card.image?.let { bytes -> cardImage?.invoke(bytes) }
                            put(ComposedWidget.Link(url, v[1].ifBlank { card.title }, "card", card.description, image, color))
                        }
                    }
                }
                "cloud" -> if (url.isNotEmpty()) {
                    onClose()
                    val kind = v.getOrNull(2) ?: "file"
                    // Видео и аудио, которые играют в окне TIMA, — нашим виджетом (заказчик 2026-10-24).
                    val media = if (onMarks != null && doc != null) io.tima.feature.chat.cloudMediaWidget(url, v[1], kind, doc) else null
                    if (media == null) {
                        put(ComposedWidget.Cloud(url, kind, v[1]))
                    } else {
                        put(media)
                        if (withMarks) {
                            val bind = (media as? ComposedWidget.Video)?.description ?: (media as ComposedWidget.Audio).description
                            onMarks?.invoke(bind)
                        }
                    }
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

/** «Добавить метки» в «Облаке» у видео и аудио. */
internal const val CLOUD_MARKS_TAG = "chat:ask.marks"

/** Вид файла по ссылке в форме «Облака» — после ухода с поля ссылки (2026-10-24). */
internal const val CLOUD_KIND_TAG = "chat:ask.kind"

/** «По ссылке — файл: …» в форме ссылки — после ухода с поля адреса (2026-10-24). */
internal const val LINK_FILE_TAG = "chat:ask.file"

/** Цвет ссылки, выбранный в «Вставить ссылку» последним: имя палитры, пусто — цвет ссылок у читающего. */
internal const val LINK_COLOR_KEY = "link.color"

/** «Знаки md» (ADR-0048): `0` — выключены; одна галочка на редактор и строку ввода. */
internal const val MARKS_KEY = "editor.marks"

/** Ключ выключателя карточек ссылок в настройках аккаунта: `"0"` — выключены, нет или иное — включены. */
internal const val LINK_CARDS_KEY = "link.cards"
