package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.ShowcaseLine
import io.tima.domain.chat.ShowcasePage
import io.tima.domain.chat.ShowcaseView

/** Метки окна «Описание» для сценариев (СЦЕНАРИИ-ПО-ОКНАМ.md). */
object ShowcaseTags {
    const val SCREEN = "showcase:screen"
    const val ITEM = "showcase:item"
    const val ACTION = "showcase:action"
    const val SUPER = "showcase:super"
}

/** Что окно «Описание» знает сейчас. */
data class ShowcaseState(
    val loading: Boolean = true,
    val page: ShowcasePage? = null,
    /** Не загрузилось: сеть или отказ. */
    val failed: Boolean = false,
)

/**
 * Окно «Описание» (ПЛАН-(ЗО) ЗО4): сообщения уровня 0 группы, канала, сообщества. Суперсообщение
 * есть — показывается оно; нет и сообщений до трёх — лентой целиком; больше — списком по три
 * строки, нажатие открывает само сообщение, одно («без ничего»). Внизу — «Вступить» или
 * «Подписаться» по положению читающего; у владельца — суперсообщение (ЗО5).
 */
@Composable
fun ShowcaseScreen(
    title: String,
    state: ShowcaseState,
    onBack: () -> Unit,
    /** Кнопка внизу — `(надпись, действие)`; `null` — уже участник или подписчик. */
    action: Pair<String, () -> Unit>? = null,
    /** Владельцу — «Создать» или «Изменить суперсообщение»; `null` — не владелец. */
    onSuper: (() -> Unit)? = null,
    avatarPicture: String? = null,
    modifier: Modifier = Modifier,
) {
    val w = Tima.words.showcase
    var single by remember { mutableStateOf<Long?>(null) }
    val page = state.page
    // Подкладка и переход внутри описания — по его же сообщениям: «Продолжить чтение» открывает
    // сообщение одно, без ленты.
    val quoteLook: (String, String, Long) -> QuoteLook = { _, _, number ->
        page?.line(number)?.let { QuoteLook(null, it.line.text) } ?: QuoteLook(null, null)
    }
    val open: (String, String, Long) -> Unit = { _, _, number -> if (page?.line(number) != null) single = number }
    Column(modifier.fillMaxSize().background(Tima.colors.surface).testTag(ShowcaseTags.SCREEN)) {
        SubwindowHeader(
            title = title,
            caption = if (single != null) w.single else w.title,
            onBack = { if (single != null) single = null else onBack() },
            avatar = title.take(2).uppercase(),
            avatarPicture = avatarPicture,
        )
        androidx.compose.runtime.CompositionLocalProvider(
            LocalQuoteLook provides quoteLook,
            LocalOpenMessage provides open,
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(TimaSpacing.about4),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                val shown = single?.let { page?.line(it) }
                when {
                    shown != null -> Whole(shown)
                    state.loading && page == null -> Tertiary(w.loading)
                    page == null -> Tertiary(w.failed)
                    else -> when (val view = page.view()) {
                        ShowcaseView.Empty -> Tertiary(w.empty)
                        is ShowcaseView.Super -> Whole(view.line)
                        is ShowcaseView.Feed -> view.lines.forEach { Whole(it) }
                        is ShowcaseView.List -> view.lines.forEach { line ->
                            Caption(
                                line.line.text,
                                modifier = Modifier.fillMaxWidth().clickable { single = line.serverId }.testTag(ShowcaseTags.ITEM),
                                fontSize = TimaType.sz4,
                                maxLines = ShowcasePage.LIST_LINES,
                            )
                        }
                    }
                }
            }
        }
        if (single == null) {
            Column(Modifier.fillMaxWidth().padding(TimaSpacing.about4), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                onSuper?.let {
                    val label = if ((page?.superId ?: 0L) > 0L) w.changeSuper else w.makeSuper
                    Button(label, it, Modifier.fillMaxWidth().testTag(ShowcaseTags.SUPER), kind = ButtonKind.Quiet)
                }
                action?.let { (label, act) -> Button(label, act, Modifier.fillMaxWidth().testTag(ShowcaseTags.ACTION)) }
            }
        }
    }
}

/** Сообщение описания целиком — тем же рисованием, что в переписке. */
@Composable
private fun Whole(line: ShowcaseLine) {
    val rich = line.line.rich
    if (rich != null) RichBody(rich, Modifier.fillMaxWidth().testTag(ShowcaseTags.ITEM))
    else Caption(line.line.text, modifier = Modifier.fillMaxWidth().testTag(ShowcaseTags.ITEM), fontSize = TimaType.sz4)
}
