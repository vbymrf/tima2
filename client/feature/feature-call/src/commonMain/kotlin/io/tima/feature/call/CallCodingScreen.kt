package io.tima.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.call.CallHand
import io.tima.core.call.CallLookKeys
import io.tima.core.call.CameraCrop
import io.tima.core.call.HardwareCoding
import io.tima.core.ui.Caption
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * «Настройки → Звонки» — аппаратное кодирование и раскодирование (ПЛАН-(В)-ВИДЕО.md В4,
 * решение заказчика 2026-09-29). Оба включены по умолчанию.
 *
 * Только телефон: на ПК кодеры и так программные, переключать там нечего.
 */
@Composable
fun CallCodingScreen(
    coding: HardwareCoding,
    onChange: (HardwareCoding) -> Unit,
    modifier: Modifier = Modifier,
    /** «Видео при сворачивании»: `false` — пауза (по умолчанию), `true` — продолжать показывать. */
    cameraInBackground: Boolean = false,
    onCameraInBackground: ((Boolean) -> Unit)? = null,
    /** «Увеличение изображения» и рука (ЗН8); `null` — не показывать. */
    zoom: Float? = null,
    onZoom: (Float) -> Unit = {},
    hand: CallHand = CallHand.Right,
    onHand: (CallHand) -> Unit = {},
    /** Телефон: кодирование и видео при сворачивании; на ПК их нет (кодеры программные). */
    phone: Boolean = true,
    /** ПК (ФЗ17): увеличение кнопками со своим изображением; `null` — телефон или камеры нет. */
    pcZoom: PcZoomSetup? = null,
    /** «Фильтры» без звонка (ФЗ12); `null` — движок их не умеет. */
    onFilters: (() -> Unit)? = null,
    /** Сколько фильтров изменено от стандарта — янтарным числом (ФЗ9). */
    filtersCount: Int = 0,
) {
    val words = Tima.words.call
    val onOff = listOf(true to words.codingOn, false to words.codingOff)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Tima.colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(TimaSpacing.about3),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        // Первым — увеличение своего изображения (заказчик 2026-11-10). Выбора вида окна нет — прежний убран (ЗН9).
        if (zoom != null) {
            Caption(words.zoomTitle, fontSize = TimaType.sz3, weight = FontWeight.Bold)
            Pick(words.zoomTitle, CallLookKeys.ZOOMS.map { it to CameraCrop.label(it) + "×" }, zoom, onZoom)
            if (zoom > 1f) {
                Pick(
                    words.handTitle,
                    listOf(CallHand.Right to words.handRight, CallHand.Left to words.handLeft, CallHand.Both to words.handBoth),
                    hand,
                    onHand,
                )
            }
            Secondary(words.zoomAbout)
        }
        if (pcZoom != null) PcZoomSection(pcZoom)
        // «Фильтры» без звонка (ФЗ12, заказчик 2026-11-12): своё изображение и тот же лист.
        if (onFilters != null) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onFilters).testTag(SETTINGS_FILTERS_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                Column(Modifier.weight(1f)) {
                    Caption(words.filtersTitle, fontSize = TimaType.sz3, weight = FontWeight.Bold)
                    Secondary(words.filtersSettingsAbout)
                }
                io.tima.core.ui.Counter(filtersCount)
                Caption("›", fontSize = TimaType.sz3, weight = FontWeight.Bold)
            }
        }
        if (!phone) return@Column
        Caption(words.codingTitle, fontSize = TimaType.sz3, weight = FontWeight.Bold)
        Secondary(words.codingAbout)
        Pick(words.codingEncode, onOff, coding.encode) { onChange(coding.copy(encode = it)) }
        Secondary(words.codingEncodeAbout)
        Pick(words.codingDecode, onOff, coding.decode) { onChange(coding.copy(decode = it)) }
        Secondary(words.codingDecodeAbout)
        // Свернули посреди видеозвонка (заказчик 2026-10-01): пауза по умолчанию (1б),
        // продолжать показывать — по выбору (1в).
        if (onCameraInBackground != null) {
            Pick(
                words.backgroundVideo,
                listOf(false to words.backgroundPause, true to words.backgroundKeep),
                cameraInBackground,
                onCameraInBackground,
            )
            Secondary(words.backgroundVideoAbout)
        }
    }
}
