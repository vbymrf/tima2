package io.tima.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.ShowcaseOf
import io.tima.domain.chat.ShowcaseSource
import io.tima.feature.chat.EditorModel
import io.tima.feature.chat.EditorScreen
import io.tima.feature.chat.ShowcaseScreen
import io.tima.feature.chat.ShowcaseState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Окно «Описание» (ПЛАН-(ЗО) ЗО4–ЗО5): забирается при каждом открытии, в ленту и базу не ложится.
 *
 * Суперсообщение владельца: у группы — переписка с редактором (сообщение уходит уровнем 0 тем же
 * путём, что любое, и ставится суперсообщением по номеру); у сообщества переписки нет — редактор
 * здесь же, сообщение уходит открытыми узлами.
 */
@Composable
internal fun ShowcaseWindow(
    of: ShowcaseOf,
    title: String,
    source: ShowcaseSource,
    showcaseApi: io.tima.core.network.ShowcaseOverHttp,
    scope: CoroutineScope,
    /** Участник группы или подписчик сообщества — кнопки «Вступить» и «Подписаться» нет. */
    member: Boolean,
    owner: Boolean,
    onJoin: () -> Unit,
    onGroupSuper: (ComposedDoc) -> Unit,
    onBack: () -> Unit,
) {
    var state by remember(of) { mutableStateOf(ShowcaseState()) }
    var reload by remember(of) { mutableIntStateOf(0) }
    LaunchedEffect(of, reload) {
        val page = runCatching { source.page(of) }.getOrNull()
        state = ShowcaseState(loading = false, page = page, failed = page == null)
        if (page == null) {
            Journal.trouble(LogCode.SHOWCASE, "не загрузилось", "вид" to of.kind)
        } else {
            Journal.note(
                LogCode.SHOWCASE, "открыто", "вид" to of.kind, "сообщений" to page.lines.size,
                "супер" to (page.superId > 0), "как" to (page.view()::class.simpleName ?: "?"),
            )
        }
    }
    val w = Tima.words.showcase
    var editing by remember(of) { mutableStateOf<ComposedDoc?>(null) }
    var notice by remember(of) { mutableStateOf<String?>(null) }
    val superFailed = w.superFailed
    editing?.let { doc ->
        val model = remember(doc) { EditorModel(doc, io.tima.core.encryption.TypedOverQuickMarkdown) }
        EditorScreen(
            model = model,
            // Описание — только «всем и всегда»: другой уровень у сообщества не бывает вовсе.
            levels = listOf(MessageCircle.Everyone),
            defaultLevel = 0,
            preview = io.tima.core.encryption.ComposedBodiesOverKodium::preview,
            problems = io.tima.core.encryption.ComposedBodiesOverKodium::problems,
            onBack = { editing = null; notice = null },
            onSend = { sent ->
                val body = io.tima.core.encryption.ComposedBodiesOverKodium.open(sent)
                scope.launch {
                    val id = body?.let { showcaseApi.addCommunity(of.id, it) }
                    val ok = id != null && source.setSuper(of, id)
                    Journal.note(LogCode.SHOWCASE, "суперсообщение", "вид" to of.kind, "вышло" to ok)
                    if (ok) {
                        editing = null
                        reload++
                    } else {
                        notice = superFailed
                    }
                }
            },
            notice = notice,
        )
        return
    }
    val page = state.page
    ShowcaseScreen(
        title = title,
        state = state,
        onBack = onBack,
        action = if (member) null else (if (of.kind == "community") w.subscribe else w.join) to onJoin,
        onSuper = if (owner && page != null && of.kind != "channel") {
            {
                val draft = page.superDraft(of.kind, of.id)
                if (of.kind == "group") onGroupSuper(draft) else editing = draft
            }
        } else {
            null
        },
    )
}
