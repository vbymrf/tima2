package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Пункт настроек «Главное окно» (ПЛАН-(ВО) ВО5, проба 5, принята заказчиком 2026-11-09): сверху образец — меняется
 * сразу; расположение — шапка всегда вверху, вкладки, разделы и панель перехода переставляются ↑↓ выше или ниже
 * списка; переключатель панели перехода; «Переходы» — куда ведёт вход в окно каждым способом (ПЛАН-(МП) М5).
 *
 * На широком формате (Р7) панели перехода нет — нет её строки и переключателя; переходы — рейкой и переключателем.
 *
 * @param arrive куда ведёт способ: `true` — в точку окна, `false` — с начала; ключ — код способа
 *   (`panel`, `switcher`, `swipe`, `rail`).
 */
@Composable
fun MainWindowScreen(
    order: List<Area>,
    nav: Boolean,
    arrive: (String) -> Boolean,
    phone: Boolean,
    onOrder: (List<Area>) -> Unit,
    onNav: (Boolean) -> Unit,
    onArrive: (code: String, point: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val w = Tima.words.windows
    // На широком формате панели перехода нет: в раскладке её строка не показывается, место — то же.
    val shown = if (phone) order else order.filter { it != Area.Nav }
    Column(modifier.fillMaxSize().background(colors.surface).verticalScroll(rememberScrollState())) {
        SectionTitle(w.mainSample)
        Sample(order, nav && phone)

        SectionTitle(w.mainLayout)
        ListLine(middle = { Tertiary(w.mainHeaderFixed) })
        shown.forEachIndexed { i, area ->
            if (area == Area.Content) {
                ListLine(modifier = Modifier.background(colors.functional), middle = { Tertiary(w.areaContent) })
            } else {
                ListLine(
                    modifier = Modifier.testTag("layout:" + area.code),
                    middle = { Name(areaName(area)) },
                    right = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Arrow("↑", enabled = i > 0, tag = "layout:up." + area.code) { onOrder(moved(order, area, -1)) }
                            Arrow("↓", enabled = i < shown.lastIndex, tag = "layout:down." + area.code) { onOrder(moved(order, area, +1)) }
                        }
                    },
                )
            }
        }
        if (phone) {
            ListLine(
                modifier = Modifier.testTag("layout:nav"),
                onClick = { onNav(!nav) },
                middle = {
                    Column {
                        Name(w.areaNav)
                        Tertiary(if (nav) w.navShown else w.navHidden)
                    }
                },
                right = { CheckMark(nav) },
            )
        }

        SectionTitle(w.transitionsTitle)
        val ways = if (phone) listOf("panel" to w.arrivePanel, "switcher" to w.arriveSwitcher, "swipe" to w.arriveSwipe)
        else listOf("rail" to w.arriveRail, "switcher" to w.arriveSwitcher)
        for ((code, name) in ways) {
            Column(Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)) {
                Name(name)
                Row(
                    Modifier.padding(top = 6.dp).background(colors.functional, RoundedCornerShape(18.dp)).padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Pick(w.arrivePoint, arrive(code), "arrive:$code.point") { onArrive(code, true) }
                    Pick(w.arriveStart, !arrive(code), "arrive:$code.start") { onArrive(code, false) }
                }
            }
        }
        Tertiary(w.transitionsAbout, modifier = Modifier.padding(TimaSpacing.about4))
    }
}

@Composable
private fun areaName(area: Area): String {
    val w = Tima.words.windows
    return when (area) {
        Area.Tabs -> w.areaTabs
        Area.Sections -> w.areaSections
        Area.Nav -> w.areaNav
        Area.Content -> w.areaContent
    }
}

@Composable
private fun Arrow(glyph: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.size(34.dp).alpha(if (enabled) 1f else 0.3f).background(colors.functional, CircleShape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Caption(glyph, fontSize = TimaType.sz4, weight = FontWeight.Bold) }
}

@Composable
private fun Pick(text: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.background(if (on) colors.navigation else colors.functional, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp).testTag(tag),
    ) { Caption(text, fontSize = TimaType.sz6, weight = FontWeight.Bold, color = if (on) colors.onAccent else colors.text2) }
}

/** Образец — уменьшенное окно «Телефон» с текущей раскладкой; меняется сразу. */
@Composable
private fun Sample(order: List<Area>, nav: Boolean) {
    val colors = Tima.colors
    val w = Tima.words.windows
    Column(
        Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2).fillMaxWidth()
            .border(1.5.dp, colors.line, RoundedCornerShape(14.dp)).padding(bottom = 4.dp).testTag("layout:sample"),
    ) {
        Box(Modifier.padding(6.dp).fillMaxWidth().height(26.dp).background(colors.navigation, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption(w.short(Window.Phone), fontSize = TimaType.sz6, weight = FontWeight.ExtraBold, color = colors.onAccent, modifier = Modifier.weight(1f))
                if (!nav) {
                    Box(Modifier.background(colors.activity, RoundedCornerShape(9.dp)).padding(horizontal = 6.dp)) {
                        Caption("12 " + Window.Social.glyph, fontSize = TimaType.sz6, color = colors.text)
                    }
                }
            }
        }
        for (area in order) {
            when (area) {
                Area.Tabs -> SampleRow(w.areaTabs, filled = true)
                Area.Sections -> SampleRow(w.areaSections, filled = false)
                Area.Content -> Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    repeat(3) { Box(Modifier.padding(vertical = 3.dp).fillMaxWidth().height(20.dp).background(colors.functional, RoundedCornerShape(6.dp))) }
                }
                Area.Nav -> if (nav) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp).background(colors.functional).padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
                        for (win in navWindows()) Caption(win.glyph, fontSize = TimaType.sz6)
                    }
                }
            }
        }
    }
}

@Composable
private fun SampleRow(text: String, filled: Boolean) {
    val colors = Tima.colors
    Box(
        Modifier.padding(horizontal = 8.dp, vertical = 3.dp).fillMaxWidth().height(20.dp)
            .background(if (filled) colors.functional else colors.surface, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) { Caption(text, fontSize = TimaType.sz6, color = colors.text2) }
}
