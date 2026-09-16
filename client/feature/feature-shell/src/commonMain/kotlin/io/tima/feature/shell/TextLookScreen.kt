package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import io.tima.core.ui.AppFont
import io.tima.core.ui.Appearance
import io.tima.core.ui.Avatar
import io.tima.core.ui.AvatarSize
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.ProvideTextScale
import io.tima.core.ui.RadioMark
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tab
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.font
import io.tima.core.ui.place
import io.tima.core.ui.words

/**
 * «Шрифты и размеры» — ПЛАН-ШРИФТОВ Ш4 и Ш5.
 *
 * **Отдельный экран от цветов** (решение заказчика 2026-09-16): «Оформление» разрослось
 * до семнадцати цветов, сохранённых наборов, шрифта и пяти ручек размера. Один пункт на
 * всё означал бы экран, по которому надо прокручивать, чтобы найти известное.
 *
 * ── ОБРАЗЕЦ ─────────────────────────────────────────────────────────────────
 *
 * Сверху — область, которая меняется вместе с ползунками: шапка, ряд вкладок, строка
 * списка, строка меню, пузырь сообщения. Без неё выбор делается вслепую: последствия
 * видны только после выхода из настроек, а к тому времени уже забыто, что меняли.
 *
 * Образец рисуется **теми же композициями**, что и настоящие экраны ([Tab], [ListLine],
 * [Avatar]), а не их копией: копия разойдётся с оригиналом и начнёт показывать не то.
 */
@Composable
fun TextLookScreen(
    appearance: Appearance,
    onAppearance: (Appearance) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val words = Tima.words.appearance
    val look = appearance.text

    Column(
        modifier
            .fillMaxSize()
            .background(colors.surface)
            .verticalScroll(rememberScrollState()),
    ) {
        SectionTitle(words.sizeSample)
        Sample(appearance)
        Tertiary(words.sizeSampleAbout, modifier = Modifier.padding(horizontal = TimaSpacing.about4))

        SectionTitle(words.fontChoice)
        for (font in AppFont.entries) {
            ListLine(
                onClick = { onAppearance(appearance.copy(text = look.copy(font = font))) },
                middle = { Name(words.font(font)) },
                right = { RadioMark(font == look.font) },
            )
        }

        SectionTitle(words.sizes)
        for (place in TextPlace.entries) {
            Steps(
                title = words.place(place),
                chosen = look.sizeOf(place),
                steps = place.steps,
                onPick = { onAppearance(appearance.copy(text = look.withSize(place, it))) },
            )
        }
    }
}

/**
 * Ступени размера — ряд кнопок с числами, а не ползунок.
 *
 * Ползунок на телефоне промахивается: пять делений на ширину экрана дают по семьдесят
 * точек, и палец между ними не попадает. Числа же нажимаются прямо и говорят, **что
 * именно** выбрано, — «17», а не «где-то посередине».
 */
@Composable
private fun Steps(title: String, chosen: Int, steps: List<Int>, onPick: (Int) -> Unit) {
    val words = Tima.words.appearance
    Column(
        modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Name(title)
        Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
            for (step in steps) {
                Tab(label = words.points(step), current = step == chosen, onClick = { onPick(step) })
            }
        }
    }
}

/** Образец: по строке на каждую группу, каждая со своим множителем. */
@Composable
private fun Sample(appearance: Appearance) {
    val colors = appearance.colors
    val words = Tima.words
    val look = appearance.text
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TimaSpacing.about4)
            .clip(RoundedCornerShape(TimaShapes.square))
            .background(colors.surface),
    ) {
        // Шапка — с настоящим фоном плашки, иначе не видно, во что упирается её рост.
        ProvideTextScale(look.scaleOf(TextPlace.HEADERS)) {
            Box(
                modifier = Modifier.fillMaxWidth().background(colors.functional)
                    .padding(TimaSpacing.about3),
                contentAlignment = Alignment.CenterStart,
            ) { Name(words.settings2.settings) }
        }
        ProvideTextScale(look.scaleOf(TextPlace.TABS)) {
            Row(
                modifier = Modifier.fillMaxWidth().background(colors.functional)
                    .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about2),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                for (tab in SAMPLE_TABS) {
                    Tab(label = words.tabs.label(tab), current = tab == SAMPLE_TABS.first(), onClick = {})
                }
            }
        }
        ProvideTextScale(look.scaleOf(TextPlace.LISTS)) {
            ListLine(
                onClick = {},
                left = { Avatar(letters = "П", size = AvatarSize.Small) },
                middle = {
                    Column {
                        Name(words.appearance.sizeLists.substringBefore(':'))
                        Secondary(words.chat.nameless, lineOne = true)
                    }
                },
            )
        }
        ProvideTextScale(look.scaleOf(TextPlace.MENU)) {
            ListLine(onClick = {}, left = { Name("🔠") }, middle = { Name(words.appearance.fontAndSize) })
        }
        ProvideTextScale(look.scaleOf(TextPlace.MESSAGES)) {
            Box(
                modifier = Modifier.padding(TimaSpacing.about3)
                    .clip(RoundedCornerShape(TimaShapes.square))
                    .background(colors.my)
                    .padding(TimaSpacing.about3),
            ) { Name(words.appearance.sizeSampleAbout) }
        }
    }
}

/** Три вкладки для образца: ряд из одной ничего не скажет о тесноте. */
private val SAMPLE_TABS = listOf(WindowTab.Chats, WindowTab.Contacts, WindowTab.Calls)
