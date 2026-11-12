package io.tima.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.call.CallFilters
import io.tima.core.call.CameraCrop
import io.tima.core.call.FilterSection
import io.tima.core.call.VideoHandle
import io.tima.core.ui.Caption
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaFixed
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * «Настройки → Звонки → Фильтры» (ПЛАН-(ФЗ) ФЗ12, заказчик 2026-11-12): своё изображение сверху и тот же лист, что в
 * звонке, — настроить без звонка. Выбор пишется сразу и действует в следующем звонке. Своё изображение с передней
 * камеры — зеркалом, как в звонке; кнопка смены камеры — справа внизу (на задней «Лицо» и «Морщины» серые).
 */
@Composable
fun FiltersSettingsScreen(
    filters: CallFilters,
    onChange: (CallFilters, Boolean) -> Unit,
    section: FilterSection,
    onSection: (FilterSection) -> Unit,
    video: VideoHandle?,
    front: Boolean,
    trouble: String?,
    onSwitchCamera: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = Tima.words.call
    Column(modifier.fillMaxSize().background(Tima.colors.surface).testTag(SETTINGS_FILTERS_SCREEN_TAG)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onBack).padding(TimaSpacing.about3).testTag(SETTINGS_FILTERS_BACK_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            Caption("‹", fontSize = TimaType.sz3, weight = FontWeight.Bold)
            Caption(words.filtersTitle, fontSize = TimaType.sz3, weight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(TimaFixed.videoShade).clipToBounds()) {
            if (video != null) {
                CallVideo(video, Modifier.fillMaxSize(), mirror = front && video.mirrorable)
            }
            if (trouble != null) {
                Caption(
                    trouble,
                    modifier = Modifier.align(Alignment.Center).padding(TimaSpacing.about4),
                    fontSize = TimaType.sz5,
                    color = TimaFixed.onVideo,
                )
            }
            if (onSwitchCamera != null && video != null) {
                CameraFlip(onSwitchCamera, Modifier.align(Alignment.BottomEnd).padding(TimaSpacing.about3))
            }
        }
        Secondary(words.filtersSettingsAbout, modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about1))
        FiltersPanel(
            filters = filters,
            onChange = onChange,
            onClose = null,
            section = section,
            onSection = onSection,
            rearCamera = !front,
            maxHeight = SETTINGS_PANEL_MAX,
        )
    }
}

/**
 * ПК (ФЗ17, решение 10): «Увеличение изображения» в «Настройки → Звонки» — своё изображение и кнопки тестера, до 2× и
 * не дальше края кадра. Что видно здесь, то с начала звонка уйдёт собеседнику.
 */
class PcZoomSetup(
    val crop: CameraCrop,
    val onCrop: (CameraCrop) -> Unit,
    /** Показ камеры («Микрофон и камера»); `null` — камеры нет. */
    val preview: (@Composable (Modifier) -> Unit)?,
)

@Composable
internal fun PcZoomSection(setup: PcZoomSetup) {
    val words = Tima.words.call
    Caption(words.zoomTitle, fontSize = TimaType.sz3, weight = FontWeight.Bold)
    Box(
        Modifier.fillMaxWidth().height(PC_PREVIEW).background(TimaFixed.videoShade).clipToBounds().testTag(SETTINGS_PC_ZOOM_TAG),
    ) {
        setup.preview?.invoke(Modifier.fillMaxSize().then(cropView(setup.crop)))
    }
    val (sx, sy) = setup.crop.shiftPercent
    Caption(words.adaptShift(CameraCrop.signed(sx), CameraCrop.signed(sy)), fontSize = TimaType.sz5, weight = FontWeight.Bold)
    AdaptKeys(setup.crop, inside = true, onCrop = setup.onCrop)
    Secondary(words.zoomPcAbout)
}

private val SETTINGS_PANEL_MAX = 330.dp
private val PC_PREVIEW = 240.dp

const val SETTINGS_FILTERS_TAG: String = "settings:calls:filters"
const val SETTINGS_FILTERS_SCREEN_TAG: String = "settings:filters"
const val SETTINGS_FILTERS_BACK_TAG: String = "settings:filters:back"
const val SETTINGS_PC_ZOOM_TAG: String = "settings:calls:zoom"
