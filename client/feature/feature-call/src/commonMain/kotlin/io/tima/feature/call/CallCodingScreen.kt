package io.tima.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.call.HardwareCoding
import io.tima.core.ui.Caption
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * «Настройки → Звонки» — аппаратное кодирование и раскодирование (ПЛАН-ВИДЕО.md В4,
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
