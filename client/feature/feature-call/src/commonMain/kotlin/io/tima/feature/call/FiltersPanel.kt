package io.tima.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.tima.core.call.CallFilters
import io.tima.core.call.ColorLook
import io.tima.core.call.FilterSection
import io.tima.core.call.LipColor
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Counter
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import kotlin.math.roundToInt

/**
 * «Фильтры» (ПЛАН-(ФЗ) ФЗ7): лист поверх изображения, своё изображение видно над ним — фильтр собирается до отправки,
 * и окошко показывает то же, что уходит. [onChange] зовётся на каждом движении ползунка (кадр меняется сразу),
 * `final` — когда палец отпущен: тогда выбор пишется в настройки (решение 6: помнится). Точки лица ищет VNN.
 *
 * С ФЗ9 (заказчик 2026-11-12) — по разделам: шапка «Фильтры · Сбросить · Включены ✓ · ✕», под ней ряд «Красота ·
 * Цвет · Лицо · Морщины» с янтарным числом изменённого, под рядом — ползунки выбранного раздела. Открывается
 * последний открытый ([section]); «Сбросить» — всё. На задней камере «Лицо» и «Морщины» серые с пояснением
 * (ФЗ10): не рисуются, но настраиваются. [onClose] `null` — без «✕» (экран настроек, назад — своей кнопкой).
 */
@Composable
internal fun FiltersPanel(
    filters: CallFilters,
    onChange: (CallFilters, Boolean) -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    section: FilterSection = FilterSection.Beauty,
    onSection: (FilterSection) -> Unit = {},
    rearCamera: Boolean = false,
    maxHeight: androidx.compose.ui.unit.Dp = PANEL_MAX,
) {
    val colors = Tima.colors
    val words = Tima.words.call
    val f = filters
    var current by remember { mutableStateOf(section) }
    fun set(next: CallFilters, final: Boolean = true) = onChange(next, final)
    Column(
        modifier.fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(colors.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2)
            .testTag(FILTERS_TAG),
    ) {
        // Шапка крупнее (заказчик 2026-11-12): растёт за счёт «✕» в круге.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
            Caption(words.filtersTitle, modifier = Modifier.weight(1f), fontSize = TimaType.sz3, weight = FontWeight.Bold, lineOne = true)
            Chip(
                label = words.filtersReset,
                kind = ChipKind.Quiet,
                onClick = { set(CallFilters.OFF.copy(on = f.on)) },
                modifier = Modifier.testTag(FILTERS_RESET_TAG),
            )
            Row(
                Modifier.clickable { set(f.copy(on = !f.on)) }.testTag(FILTERS_ON_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
            ) {
                Caption(words.filtersOn, fontSize = TimaType.sz5)
                CheckMark(f.on)
            }
            if (onClose != null) SheetClose(onClose, Modifier.testTag(FILTERS_CLOSE_TAG))
        }
        // Разделы: выбранный — цветом, изменённое — янтарным числом (видно и при снятой галочке).
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = TimaSpacing.about3, bottom = TimaSpacing.about1),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (s in FilterSection.entries) {
                val name = when (s) {
                    FilterSection.Beauty -> words.filtersBeauty
                    FilterSection.Color -> words.filtersColor
                    FilterSection.Face -> words.filtersFace
                    FilterSection.Wrinkles -> words.filtersWrinkles
                }
                Row(
                    Modifier.alpha(if (rearCamera && s.frontOnly) DIM_SECTION else 1f).testTag(FILTERS_TAB_TAG_PREFIX + s.key),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Chip(
                        label = name,
                        kind = if (s == current) ChipKind.Selected else ChipKind.Quiet,
                        onClick = {
                            current = s
                            onSection(s)
                        },
                    )
                    Counter(f.changed(s))
                }
            }
        }
        val dim = rearCamera && current.frontOnly
        if (dim) Secondary(words.filtersRearOnly)
        Column(
            Modifier.verticalScroll(rememberScrollState()).alpha(if (dim) DIM_SECTION else 1f),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
        ) {
            when (current) {
                FilterSection.Beauty -> {
                    Level("smooth", words.filterSmooth, f.smooth, 0) { v, done -> set(f.copy(smooth = v), done) }
                    Level("whiten", words.filterWhiten, f.whiten, 0) { v, done -> set(f.copy(whiten = v), done) }
                    Level("sharpen", words.filterSharpen, f.sharpen, 0) { v, done -> set(f.copy(sharpen = v), done) }
                }
                FilterSection.Color -> {
                    Level("brightness", words.filterBrightness, f.brightness, -100) { v, done -> set(f.copy(brightness = v), done) }
                    Level("contrast", words.filterContrast, f.contrast, -100) { v, done -> set(f.copy(contrast = v), done) }
                    Level("saturation", words.filterSaturation, f.saturation, -100) { v, done -> set(f.copy(saturation = v), done) }
                    Level("warmth", words.filterWarmth, f.warmth, -100) { v, done -> set(f.copy(warmth = v), done) }
                    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                        for ((look, name) in listOf(ColorLook.None to words.lookNone, ColorLook.Light to words.lookLight, ColorLook.Custom to words.lookCustom)) {
                            Chip(label = name, kind = if (f.look == look) ChipKind.Selected else ChipKind.Quiet, onClick = { set(f.copy(look = look)) })
                        }
                    }
                    if (f.look != ColorLook.None) Level("look", words.filterLookStrength, f.lookStrength, 0) { v, done -> set(f.copy(lookStrength = v), done) }
                }
                FilterSection.Face -> {
                    Level("thin", words.filterThin, f.thin, 0) { v, done -> set(f.copy(thin = v), done) }
                    Level("eyes", words.filterEyes, f.eyes, 0) { v, done -> set(f.copy(eyes = v), done) }
                    Level("lipstick", words.filterLipstick, f.lipstick, 0) { v, done -> set(f.copy(lipstick = v), done) }
                    // Цвет помады (ФЗ14, заказчик 2026-11-12: «5 цветов поддерживаю»).
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).testTag(LIP_COLORS_TAG),
                        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                    ) {
                        for ((lip, name) in listOf(
                            LipColor.Classic to words.lipClassic, LipColor.Pink to words.lipPink, LipColor.Coral to words.lipCoral,
                            LipColor.Berry to words.lipBerry, LipColor.Cherry to words.lipCherry,
                        )) {
                            Chip(
                                label = name,
                                kind = if (f.lipColor == lip) ChipKind.Selected else ChipKind.Quiet,
                                onClick = { set(f.copy(lipColor = lip)) },
                                modifier = Modifier.testTag(LIP_COLOR_TAG_PREFIX + lip.name.lowercase()),
                            )
                        }
                    }
                    Level("blush", words.filterBlush, f.blush, 0) { v, done -> set(f.copy(blush = v), done) }
                }
                FilterSection.Wrinkles -> {
                    Level("forehead", words.filterForehead, f.forehead, 0) { v, done -> set(f.copy(forehead = v), done) }
                    Level("brows", words.filterBrows, f.brows, 0) { v, done -> set(f.copy(brows = v), done) }
                    Level("underEyes", words.filterUnderEyes, f.underEyes, 0) { v, done -> set(f.copy(underEyes = v), done) }
                    Level("eyeCorners", words.filterEyeCorners, f.eyeCorners, 0) { v, done -> set(f.copy(eyeCorners = v), done) }
                    Level("nasolabial", words.filterNasolabial, f.nasolabial, 0) { v, done -> set(f.copy(nasolabial = v), done) }
                }
            }
        }
    }
}

/** Ползунок с подписью и числом. [min] — −100 у величин «меньше-больше», 0 — у сил. */
@Composable
private fun Level(key: String, label: String, value: Int, min: Int, onValue: (Int, Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
        Caption(label, modifier = Modifier.width(LABEL), fontSize = TimaType.sz6, lineOne = true)
        FilterSlider(value, min, 100, onValue, Modifier.weight(1f).testTag(FILTER_SLIDER_TAG_PREFIX + key))
        Caption(value.toString(), modifier = Modifier.width(VALUE), fontSize = TimaType.sz6, lineOne = true)
    }
}

/** Свой ползунок: полоса и круг, движение пальцем и нажатие в место. Готового в наборе приложения нет. */
@Composable
private fun FilterSlider(value: Int, min: Int, max: Int, onValue: (Int, Boolean) -> Unit, modifier: Modifier) {
    val colors = Tima.colors
    val latest by rememberUpdatedState(onValue)
    val current by rememberUpdatedState(value)
    var dragX by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(modifier.height(THUMB), contentAlignment = Alignment.CenterStart) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val thumbPx = with(LocalDensity.current) { THUMB.toPx() }
        val travel = (widthPx - thumbPx).coerceAtLeast(1f)
        fun at(x: Float): Int = (min + ((x - thumbPx / 2) / travel).coerceIn(0f, 1f) * (max - min)).roundToInt()
        val fraction = (value - min).toFloat() / (max - min)
        Box(
            Modifier.fillMaxWidth().height(4.dp).background(colors.line, RoundedCornerShape(2.dp))
                .pointerInput(min, max, travel) {
                    detectTapGestures { latest(at(it.x), true) }
                },
        )
        Box(
            Modifier.offset { IntOffset((fraction * travel).roundToInt(), 0) }
                .size(THUMB)
                .background(if (value == 0) colors.line else colors.navigation, CircleShape)
                .pointerInput(min, max, travel) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragX = (current - min).toFloat() / (max - min) * travel + thumbPx / 2 },
                        onDragEnd = { latest(current, true) },
                        onDragCancel = { latest(current, true) },
                    ) { change, amount ->
                        change.consume()
                        dragX += amount
                        latest(at(dragX), false)
                    }
                },
        )
    }
}

private val PANEL_MAX = 380.dp

/** Серость разделов «Лицо» и «Морщины» на задней камере (ФЗ10). */
private const val DIM_SECTION = 0.45f
private val LABEL = 112.dp
private val VALUE = 32.dp
private val THUMB = 22.dp

const val FILTERS_TAG: String = "call:filters"
const val FILTERS_ON_TAG: String = "call:filters:on"
const val FILTERS_CLOSE_TAG: String = "call:filters:close"
const val FILTERS_ROW_TAG: String = "call:filters:open"
const val FILTER_SLIDER_TAG_PREFIX: String = "call:filter:"
const val FILTERS_RESET_TAG: String = "call:filters:reset"
const val FILTERS_TAB_TAG_PREFIX: String = "call:filters:tab:"
const val LIP_COLORS_TAG: String = "call:filter:lipColor"
const val LIP_COLOR_TAG_PREFIX: String = "call:filter:lipColor:"
