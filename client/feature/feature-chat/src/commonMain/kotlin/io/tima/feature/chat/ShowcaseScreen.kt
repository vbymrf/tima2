package io.tima.feature.chat

import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
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
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TextPlace
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
    /** Полоса вкладок страницы сущности (ПЛАН-(СЦ) СЦ4); `null` — нет. */
    tabs: (@Composable () -> Unit)? = null,
    /** Верх «Описание» над суперсообщением: тип, пузыри, кнопки (СЦ4); `null` — нет. */
    top: (@Composable () -> Unit)? = null,
    /**
     * Нажатие на сообщение «Описания» — его комментарии и оценка (ПЛАН-(СЦ) §1.18, 11), когда у сущности включены
     * «Комментарии к описанию»; `null` — нажатие открывает сообщение одно, как прежде.
     */
    onComments: ((ShowcaseLine) -> Unit)? = null,
) {
    val w = Tima.words.showcase
    var single by remember { mutableStateOf<Long?>(null) }
    val page = state.page
    // Подкладка и переход внутри описания — по его же сообщениям: «Продолжить чтение» открывает
    // сообщение одно, без ленты.
    val quoteLook: (String, String, Long) -> QuoteLook = { _, _, number ->
        // Исходное целиком — подкладка суперсообщения показывает его с оформлением, а не простым текстом (§1.18, 11).
        page?.line(number)?.let { QuoteLook(null, it.line.text, rich = it.line.rich) } ?: QuoteLook(null, null)
    }
    val open: (String, String, Long) -> Unit = { _, _, number -> if (page?.line(number) != null) single = number }
    // Страница — «Списки» «Шрифтов и размеров», сами сообщения уровня 0 — «Сообщения», как в переписке (заказчик
    // 2026-11-23); шапка и вкладки ставят своё.
    ProvidePlace(TextPlace.LISTS) {
    Column(modifier.fillMaxSize().background(Tima.colors.surface).testTag(ShowcaseTags.SCREEN)) {
        SubwindowHeader(
            title = title,
            caption = if (single != null) w.single else w.title,
            onBack = { if (single != null) single = null else onBack() },
            avatar = title.take(2).uppercase(),
            avatarPicture = avatarPicture,
        )
        if (single == null) tabs?.invoke()
        // «Описание целиком» (§1.18, 11): подкладки суперсообщения — без обрезки по строкам.
        val whole = page?.settings?.whole == true
        val tap: (ShowcaseLine) -> Unit = { line -> if (onComments != null) onComments(line) else single = line.serverId }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalQuoteLook provides quoteLook,
            LocalOpenMessage provides open,
            LocalRefWhole provides whole,
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(TimaSpacing.about4),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
            ) {
                val shown = single?.let { page?.line(it) }
                if (shown == null) top?.invoke()
                when {
                    shown != null -> Whole(shown)
                    state.loading && page == null -> Tertiary(w.loading)
                    page == null -> Tertiary(w.failed)
                    else -> when (val view = page.view()) {
                        // Сообщений уровня 0 нет — под чертой пусто: «Описания пока нет» спорило с описанием сущности
                        // над пузырями (заказчик 2026-11-24: «убери надпись совсем»).
                        ShowcaseView.Empty -> Unit
                        is ShowcaseView.Super -> Box(if (onComments != null) Modifier.clickable { onComments(view.line) } else Modifier) { Whole(view.line) }
                        // Между сообщениями уровня 0 — разделитель (СЦ28, §1.13, 8).
                        is ShowcaseView.Feed -> view.lines.forEachIndexed { i, it ->
                            if (i > 0) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Tima.colors.line))
                            Box(if (onComments != null) Modifier.clickable { onComments(it) } else Modifier) { Whole(it) }
                        }
                        // Больше трёх — списком (§1.18, 11): целиком или обрезкой; обрезка — с полным оформлением (выделение,
                        // заголовки, картинки), а не простым текстом; нажатие — сообщение целиком или его комментарии.
                        is ShowcaseView.List -> view.lines.forEachIndexed { i, line ->
                            if (i > 0) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Tima.colors.line))
                            Box(Modifier.fillMaxWidth().clickable { tap(line) }) {
                                if (whole) Whole(line) else Clipped(line)
                            }
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
}

/**
 * Сообщение описания обрезкой (§1.18, 11): тем же рисованием, что в переписке, — с оформлением, — но по высоте около трёх
 * строк, низ затухает.
 */
@Composable
private fun Clipped(line: ShowcaseLine) {
    val surface = Tima.colors.surface
    Box(Modifier.fillMaxWidth().heightIn(max = CLIPPED_HEIGHT).clipToBounds()) {
        Whole(line)
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(CLIPPED_FADE)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface))),
        )
    }
}

/** Высота сообщения обрезкой — около трёх строк текста. */
private val CLIPPED_HEIGHT = 84.dp
private val CLIPPED_FADE = 22.dp

/** Сообщение описания целиком — тем же рисованием, что в переписке. */
@Composable
private fun Whole(line: ShowcaseLine) = ProvidePlace(TextPlace.MESSAGES) {
    val rich = line.line.rich
    if (rich != null) RichBody(rich, Modifier.fillMaxWidth().testTag(ShowcaseTags.ITEM))
    else Caption(line.line.text, modifier = Modifier.fillMaxWidth().testTag(ShowcaseTags.ITEM), fontSize = TimaType.sz4)
}
