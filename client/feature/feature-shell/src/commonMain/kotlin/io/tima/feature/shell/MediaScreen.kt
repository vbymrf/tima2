package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.CheckMark
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.RadioMark
import io.tima.core.ui.SectionTitle
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Trouble
import io.tima.core.ui.words

/** Устройство в выборе: `id` — как его знает система, `name` — для человека. */
data class MediaDevice(val id: String, val name: String)

/** Выбор человека. `null` у устройства — «как в системе». */
data class MediaChoice(
    val microphone: String? = null,
    val speaker: String? = null,
    val camera: String? = null,
    val echo: Boolean = true,
    val noise: Boolean = true,
    val gain: Boolean = true,
)

/**
 * «Микрофон и камера» — выбор устройств и проверка без звонка (заказчик 2026-09-26).
 *
 * Отвечает на три вопроса, которые иначе задают собеседнику: **меня слышно?** — полоса
 * уровня; **меня видно?** — своя картинка; **я слышу?** — пробный звук. Громкость
 * микрофона — та же ручка, что в «Параметрах» Windows: «слышно тихо» чаще всего она.
 *
 * Выбор не синхронизируется: устройства у каждого ПК свои.
 */
@Composable
fun MediaScreen(
    microphones: List<MediaDevice>,
    speakers: List<MediaDevice>,
    cameras: List<MediaDevice>,
    choice: MediaChoice,
    onChoice: (MediaChoice) -> Unit,
    /** Уровень микрофона 0…1. */
    level: Float,
    /** Громкость микрофона 0…1; `null` — узнать нечем, ручки нет. */
    volume: Float?,
    onVolume: (Float) -> Unit,
    onTestSound: () -> Unit,
    /** Слушать себя: микрофон — в выбранные колонки, пока открыт экран. */
    listen: Boolean,
    onListen: (Boolean) -> Unit,
    /** Своя картинка. `null` — камеры нет или она не открылась. */
    preview: (@Composable (Modifier) -> Unit)?,
    /** Что не получилось при проверке. */
    trouble: String?,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val words = Tima.words.settings2
    Column(
        modifier.fillMaxSize().background(colors.surface).verticalScroll(rememberScrollState())
            .padding(bottom = TimaSpacing.about5),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        trouble?.let { Box(Modifier.padding(horizontal = TimaSpacing.about4)) { Trouble(it) } }

        // ── МИКРОФОН ─────────────────────────────────────────────────────────
        SectionTitle(words.mediaMicrophone)
        Picker(microphones, choice.microphone) { onChoice(choice.copy(microphone = it)) }
        Column(
            Modifier.padding(horizontal = TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            Secondary(words.mediaMicrophoneAbout)
            LevelBar(level)
        }
        Toggle(words.mediaListen, listen, onListen)
        Column(
            Modifier.padding(horizontal = TimaSpacing.about4),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            if (volume != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                ) {
                    Name(words.mediaVolume + ": " + (volume * 100).toInt() + "%")
                    Button(label = "−", onClick = { onVolume((volume - STEP).coerceAtLeast(0f)) }, kind = ButtonKind.Quiet)
                    Button(label = "+", onClick = { onVolume((volume + STEP).coerceAtMost(1f)) }, kind = ButtonKind.Quiet)
                }
            }
        }

        // ── КОЛОНКИ ──────────────────────────────────────────────────────────
        SectionTitle(words.mediaSpeaker)
        Picker(speakers, choice.speaker) { onChoice(choice.copy(speaker = it)) }
        Column(Modifier.padding(horizontal = TimaSpacing.about4)) {
            Button(label = words.mediaTestSound, onClick = onTestSound, kind = ButtonKind.Action)
        }

        // ── КАМЕРА ───────────────────────────────────────────────────────────
        SectionTitle(words.mediaCamera)
        Picker(cameras, choice.camera) { onChoice(choice.copy(camera = it)) }
        Box(
            Modifier.padding(horizontal = TimaSpacing.about4).widthIn(max = PREVIEW_WIDTH).fillMaxWidth()
                .height(PREVIEW_HEIGHT).background(colors.functional, RoundedCornerShape(TimaSpacing.about2)),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) preview(Modifier.fillMaxSize()) else Tertiary(words.mediaNoCamera)
        }

        // ── ОБРАБОТКА ЗВУКА ──────────────────────────────────────────────────
        SectionTitle(words.mediaProcessing)
        Toggle(words.mediaEcho, choice.echo) { onChoice(choice.copy(echo = it)) }
        Toggle(words.mediaNoise, choice.noise) { onChoice(choice.copy(noise = it)) }
        Toggle(words.mediaGain, choice.gain) { onChoice(choice.copy(gain = it)) }
        Column(Modifier.padding(horizontal = TimaSpacing.about4)) {
            Tertiary(words.mediaProcessingAbout)
            Tertiary(words.mediaNextCall)
        }
    }
}

/** Выбор одного устройства: «как в системе» и все найденные. */
@Composable
private fun Picker(devices: List<MediaDevice>, chosen: String?, onPick: (String?) -> Unit) {
    val words = Tima.words.settings2
    // Выбранное, которого больше нет (выдернули), считается «как в системе» — так его и
    // возьмёт движок.
    val current = chosen?.takeIf { id -> devices.any { it.id == id } }
    ListLine(onClick = { onPick(null) }, left = { RadioMark(current == null) }) { Name(words.mediaDefault) }
    for (device in devices) {
        ListLine(onClick = { onPick(device.id) }, left = { RadioMark(current == device.id) }) { Name(device.name) }
    }
}

@Composable
private fun Toggle(title: String, on: Boolean, onChange: (Boolean) -> Unit) {
    ListLine(onClick = { onChange(!on) }, left = { CheckMark(on) }) { Name(title) }
}

/** Полоса уровня: зелёная доля от ширины. Числа не нужны — нужно «двигается или нет». */
@Composable
private fun LevelBar(level: Float) {
    val colors = Tima.colors
    Box(
        Modifier.widthIn(max = PREVIEW_WIDTH).fillMaxWidth().height(10.dp)
            .background(colors.line, RoundedCornerShape(5.dp)),
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0f, 1f))
                .background(colors.navigation, RoundedCornerShape(5.dp)),
        )
    }
}

private const val STEP = 0.1f
private val PREVIEW_WIDTH = 360.dp
private val PREVIEW_HEIGHT = 200.dp
