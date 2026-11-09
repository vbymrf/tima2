package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Панель перехода (ПЛАН-(ВО) ВО2, проба 2а, заказчик 2026-11-09): окна значком и названием внизу главного окна,
 * текущее — салатовым, янтарное число в углу значка — сколько нового в окне. Окна — те, что показаны в
 * переключателе, без временных (звонок, стенд) и выключенных (Медиа): Телефон, Социум, Общение, Страница. У
 * «Общения» числа нет (Р8) — своего журнала уведомлений у него нет.
 *
 * Нажатие — в окно, в начальное положение (ПЛАН-(МП) М3): первая вкладка, без подокна. Только телефон: на широком
 * формате окна меняет рейка (Р7).
 */
class WindowNav(
    val current: Window,
    val windows: List<Window>,
    val counters: Map<Window, Int>,
    val onSelect: (Window) -> Unit,
)

/** Панель перехода для каркаса окна; `null` — панели нет (скрыта настройкой, широкий формат, снимки). */
val LocalWindowNav = compositionLocalOf<WindowNav?> { null }

@Composable
fun WindowNavBar(nav: WindowNav, modifier: Modifier = Modifier) {
    val colors = Tima.colors
    val names = Tima.words.windows
    Row(
        modifier.fillMaxWidth().background(colors.functional).padding(horizontal = 4.dp, vertical = 6.dp).testTag(WINDOW_NAV_TAG),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (window in nav.windows) {
            val here = window == nav.current
            Column(
                Modifier.weight(1f).clickable { nav.onSelect(window) }.padding(vertical = 2.dp).testTag(navTag(window)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(width = 52.dp, height = 30.dp)
                            .background(if (here) colors.navigation else colors.functional, RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Caption(window.glyph, fontSize = TimaType.sz4) }
                    val count = nav.counters[window] ?: 0
                    if (count > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).widthIn(min = 18.dp)
                                .background(colors.activity, RoundedCornerShape(9.dp))
                                .border(2.dp, colors.functional, RoundedCornerShape(9.dp))
                                .padding(horizontal = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) { Caption(if (count > 99) "99+" else count.toString(), fontSize = TimaType.sz6, weight = FontWeight.ExtraBold, color = colors.text) }
                    }
                }
                Caption(
                    names.short(window),
                    fontSize = TimaType.sz6,
                    weight = if (here) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = if (here) colors.text else colors.text2,
                    lineOne = true,
                )
            }
        }
    }
}

/** Окна панели перехода: показанные, без временных. */
fun navWindows(): List<Window> = Window.shown(inCall = false, bench = false).filterNot { it.temporary }

const val WINDOW_NAV_TAG = "nav:bar"

/** Метка кнопки окна — `nav:phone`, `nav:social`… */
fun navTag(window: Window): String = "nav:" + window.name.lowercase()

/**
 * Подсказка к панели перехода (ПЛАН-(ВО) ВО8, Р14): пузырь над панелью при первом её показе — что это, как убрать;
 * «Настройки» — туда, где панель убирают и переставляют. Показывается один раз; помнит это вызывающий.
 */
class WindowNavTip(val onClose: () -> Unit, val onSettings: () -> Unit)

/** Подсказка для каркаса окна; `null` — её нет (уже показана, панель скрыта). */
val LocalWindowNavTip = compositionLocalOf<WindowNavTip?> { null }

@Composable
fun WindowNavTipBubble(tip: WindowNavTip) {
    val colors = Tima.colors
    val w = Tima.words.windows
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .background(colors.surface, RoundedCornerShape(14.dp))
            .border(2.dp, colors.navigation, RoundedCornerShape(14.dp))
            .padding(14.dp)
            .testTag(NAV_TIP_TAG),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Caption(w.navTipTitle, fontSize = TimaType.sz4, weight = FontWeight.Bold)
        Caption(w.navTipText, fontSize = TimaType.sz5, color = colors.text2)
        Caption(w.navTipHow, fontSize = TimaType.sz5, color = colors.text2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            Box(
                Modifier.background(colors.functional, RoundedCornerShape(18.dp)).clickable(onClick = tip.onSettings)
                    .padding(horizontal = 14.dp, vertical = 8.dp).testTag(NAV_TIP_SETTINGS_TAG),
            ) { Caption(w.navTipSettings, fontSize = TimaType.sz5, weight = FontWeight.SemiBold) }
            Box(
                Modifier.background(colors.navigation, RoundedCornerShape(18.dp)).clickable(onClick = tip.onClose)
                    .padding(horizontal = 14.dp, vertical = 8.dp).testTag(NAV_TIP_CLOSE_TAG),
            ) { Caption(w.navTipOk, fontSize = TimaType.sz5, weight = FontWeight.SemiBold, color = colors.onAccent) }
        }
    }
}

const val NAV_TIP_TAG = "tip:nav"
const val NAV_TIP_SETTINGS_TAG = "tip:nav.settings"
const val NAV_TIP_CLOSE_TAG = "tip:nav.close"

/**
 * Уведомление соседнего окна в шапке (ПЛАН-(ВО) ВО3, проба 3в) — когда панель перехода скрыта: число в оранжевом
 * пузыре, значок окна в правом углу пузыря. Смотрим только «Телефон» и «Социум»: на «Телефоне» — пузырь «Социума», на
 * «Социуме» — «Телефона», на остальных — оба. Нажатие — в окно и на вкладку последнего пришедшего (Р4). Только телефон.
 */
class HeaderNotice(val window: Window, val count: Int, val onOpen: () -> Unit)

/** Пузыри шапки; пусто — их нет (панель показана, широкий формат, нового нет). */
val LocalHeaderNotices = compositionLocalOf<List<HeaderNotice>> { emptyList() }

/** Окна, о которых пузыри в шапке, и чьи — на каком окне (Р3). */
val HEADER_NOTICE_WINDOWS = listOf(Window.Phone, Window.Social)

@Composable
fun HeaderNoticeBubble(notice: HeaderNotice) {
    val colors = Tima.colors
    Box(Modifier.padding(top = 6.dp, end = 8.dp)) {
        Box(
            Modifier.widthIn(min = 30.dp).background(colors.activity, RoundedCornerShape(14.dp))
                .clickable(onClick = notice.onOpen).padding(horizontal = 9.dp, vertical = 4.dp)
                .testTag(headerNoticeTag(notice.window)),
            contentAlignment = Alignment.Center,
        ) {
            Caption(if (notice.count > 99) "99+" else notice.count.toString(), fontSize = TimaType.sz5, weight = FontWeight.ExtraBold, color = colors.text)
        }
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-8).dp).size(20.dp)
                .background(colors.surface, androidx.compose.foundation.shape.CircleShape)
                .border(1.5.dp, colors.navigation, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) { Caption(notice.window.glyph, fontSize = TimaType.sz6) }
    }
}

/** Метка пузыря: `header:notice.phone`, `header:notice.social`. */
fun headerNoticeTag(window: Window): String = "header:notice." + window.name.lowercase()
