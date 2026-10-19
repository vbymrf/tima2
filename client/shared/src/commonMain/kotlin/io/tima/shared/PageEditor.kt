package io.tima.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.MessageCircle
import io.tima.feature.chat.EditorModel
import io.tima.feature.chat.EditorScreen
import io.tima.feature.chat.LEVEL_PAGE
import io.tima.feature.chat.PageState
import io.tima.feature.chat.PageStore
import kotlinx.coroutines.CoroutineScope

/**
 * Редактор на личной странице (Решения §10.2): тот же, что у сообщения; уровни — открытые 0…3,
 * по умолчанию «всем». Картинок в записи страницы нет: у записи канала нет файлов.
 */
@Composable
internal fun PageEditor(
    page: PageStore,
    state: PageState,
    scope: CoroutineScope,
    cardsOn: Boolean = true,
    linkColor: String = "",
    onLinkColor: (String) -> Unit = {},
) {
    val model = remember { EditorModel(state.draft ?: ComposedDoc()) }
    val ew = Tima.words.editor
    var ask by remember { mutableStateOf<String?>(null) }
    val cards = remember { io.tima.core.network.LinkCardsOverHttp() }
    val noPlace = Tima.words.chat.placeNoAccess
    fun put(widget: io.tima.domain.chat.ComposedWidget) = model.insert(ComposedItem.Widget(widget))
    val contact = io.tima.core.media.rememberContactPicker { c -> c?.let { put(io.tima.domain.chat.ComposedWidget.Contact(it.name, it.phone)) } }
    val place = io.tima.core.media.rememberMyPlace { p ->
        if (p != null) put(io.tima.domain.chat.ComposedWidget.Place(p.first, p.second)) else { page.editorNoticeShown(noPlace); ask = "place" }
    }
    EditorScreen(
        model = model,
        levels = MessageCircle.entries.filter { it != MessageCircle.Secret }.sortedBy { it.level },
        defaultLevel = LEVEL_PAGE,
        typingMarks = io.tima.core.encryption.TypingMarksOverQuickMarkdown,
        preview = io.tima.core.encryption.ComposedBodiesOverKodium::preview,
        problems = io.tima.core.encryption.ComposedBodiesOverKodium::problems,
        onBack = page::editorBack,
        onDraft = page::editorDraft,
        onSend = page::publish,
        notice = state.editorNotice,
        onButton = { key ->
            page.editorNoticeShown(null)
            when (key) {
                "link", "cloud" -> { ask = key; true }
                "place" -> { place?.invoke() ?: run { ask = "place" }; true }
                "contact" -> { contact?.invoke() ?: run { ask = "contact" }; true }
                "picture" -> { page.editorNoticeShown(ew.pageNoPictures); true }
                else -> false
            }
        },
    )
    ask?.let { a ->
        EditorAskSheet(
            ask = a,
            scope = scope,
            cards = cards,
            cardImage = null,
            put = ::put,
            onNotice = page::editorNoticeShown,
            onClose = { ask = null },
            cardsOn = cardsOn,
            linkColor = linkColor,
            onLinkColor = onLinkColor,
        )
    }
}
