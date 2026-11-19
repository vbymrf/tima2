package io.tima.feature.group

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/** Метки звукового чата — для сценариев. */
object VoiceTags {
    const val ENTER = "voice:enter"
    const val LEAVE = "voice:leave"
    const val MIC = "voice:mic"
    const val SOUND = "voice:sound"
    const val HOST = "voice:host"
    const val TEXT = "voice:text"
    const val STRIP = "voice:strip"
    const val CREATE = "voice:create"
    const val ROOM = "voice:room"
}

/** Участник звукового чата на экране. */
data class VoicePerson(val userId: String, val name: String, val speaking: Boolean, val host: Boolean)

/**
 * Окно звукового чата (ПЛАН-(СЦ) СЦ7): сверху закреплены участники (говорит — зелёная рамка, ведущий — пометка),
 * ниже — переписка чата (кнопкой: текст — его группа), внизу — микрофон и звук, если разрешено, и «Выйти».
 */
@Composable
fun VoiceChatScreen(
    title: String,
    people: List<VoicePerson>,
    allowMute: Boolean,
    mic: Boolean,
    sound: Boolean,
    paused: Boolean,
    /** Место ведущего свободно и ведущий разрешён — «Стать ведущим». */
    canTakeHost: Boolean,
    /** Выгнать участника — ведущий с разрешением, владелец и админы; `null` — нельзя. */
    onKick: ((String) -> Unit)?,
    onTakeHost: () -> Unit,
    onMic: (Boolean) -> Unit,
    onSound: (Boolean) -> Unit,
    onText: (() -> Unit)?,
    onLeave: () -> Unit,
    onBack: () -> Unit,
) {
    val w = Tima.words.socium
    val colors = Tima.colors
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        SubwindowHeader(title = title, onBack = onBack, caption = w.voiceChat, avatar = title.take(2).uppercase())
        FlowRow(
            Modifier.fillMaxWidth().padding(TimaSpacing.about4),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            for (p in people) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(enabled = onKick != null) { onKick?.invoke(p.userId) }) {
                    Box(Modifier.border(3.dp, if (p.speaking) colors.confirmed else colors.surface, CircleShape).padding(3.dp)) {
                        Avatar(letters = p.name.take(2).uppercase())
                    }
                    Tertiary(p.name, lineOne = true)
                    if (p.host) Caption(w.voiceHost, fontSize = TimaType.sz6, weight = FontWeight.Bold, color = colors.confirmed)
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4)) { Secondary(if (paused) w.voicePaused else w.voiceNoE2e) }
        Column(Modifier.weight(1f).fillMaxWidth().padding(TimaSpacing.about4), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3)) {
            onText?.let { Button(w.voiceText, onClick = it, kind = ButtonKind.Quiet, modifier = Modifier.testTag(VoiceTags.TEXT)) }
            if (canTakeHost) Button(w.voiceTakeHost, onClick = onTakeHost, kind = ButtonKind.Quiet, modifier = Modifier.testTag(VoiceTags.HOST))
        }
        Row(
            Modifier.fillMaxWidth().padding(TimaSpacing.about4),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Микрофон и звук — только если разрешено настройкой чата (§1.3, 4); иначе остаётся «Выйти».
            if (allowMute) {
                Button(if (mic) w.micOff else w.micOn, onClick = { onMic(!mic) }, kind = ButtonKind.Quiet, modifier = Modifier.testTag(VoiceTags.MIC))
                Button(if (sound) w.soundOff else w.soundOn, onClick = { onSound(!sound) }, kind = ButtonKind.Quiet, modifier = Modifier.testTag(VoiceTags.SOUND))
            }
            Button(w.leave, onClick = onLeave, kind = ButtonKind.Leave, modifier = Modifier.testTag(VoiceTags.LEAVE))
        }
    }
}

/** Полоса звукового чата поверх окон, пока человек в чате (§1.8, 4): нажатие — окно чата. */
@Composable
fun VoiceStrip(title: String, allowMute: Boolean, mic: Boolean, onOpen: () -> Unit, onMic: (Boolean) -> Unit, onLeave: () -> Unit) {
    val w = Tima.words.socium
    Row(
        Modifier.fillMaxWidth().background(Tima.colors.confirmed).clickable(onClick = onOpen).padding(horizontal = TimaSpacing.about4, vertical = 6.dp).testTag(VoiceTags.STRIP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        Caption(w.voiceChat + ": " + title, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = Tima.colors.onAccent, lineOne = true, modifier = Modifier.weight(1f))
        if (allowMute) Caption(if (mic) w.micOff else w.micOn, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = Tima.colors.onAccent, modifier = Modifier.clickable { onMic(!mic) })
        Caption(w.leave, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = Tima.colors.onAccent, modifier = Modifier.clickable(onClick = onLeave))
    }
}

/** Звуковые чаты сообщества строками: войти; владельцу и админам — создать и настройки. */
@Composable
fun VoiceRoomsList(
    rooms: List<Pair<String, String>>,
    count: (String) -> Int,
    onEnter: (String, String) -> Unit,
    onCreate: ((String) -> Unit)?,
) {
    val w = Tima.words.socium
    Column(verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
        if (rooms.isNotEmpty() || onCreate != null) Tertiary(w.voiceChats)
        for ((id, title) in rooms) {
            ListLine(
                modifier = Modifier.testTag(VoiceTags.ROOM),
                onClick = { onEnter(id, title) },
                left = { TypeIcon(io.tima.domain.chat.EntityType(io.tima.domain.chat.EntityKind.Voice, io.tima.domain.chat.EntityReach.Public), 24.dp) },
                middle = { Name(title); Tertiary(w.voiceInside(count(id))) },
                right = { Box(Modifier.size(10.dp).background(if (count(id) > 0) Tima.colors.confirmed else Tima.colors.quiet, CircleShape)) },
            )
        }
        if (onCreate != null) {
            var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                io.tima.core.ui.Field(name, { name = it }, modifier = Modifier.weight(1f), hint = w.voiceNewHint, sentences = true)
                Button(w.voiceCreate, onClick = { if (name.isNotBlank()) { onCreate(name.trim()); name = "" } }, modifier = Modifier.testTag(VoiceTags.CREATE))
            }
        }
    }
}

/** Настройки звукового чата (владелец и админы): выключать микрофон и звук, ведущий, выгон ведущим, предел. */
@Composable
fun VoiceSettings(allowMute: Boolean, hostAllowed: Boolean, hostCanKick: Boolean, max: Int, onChange: (Boolean, Boolean, Boolean, Int) -> Unit) {
    val w = Tima.words.socium
    @Composable
    fun line(on: Boolean, text: String, flip: () -> Unit) = Row(
        Modifier.fillMaxWidth().clickable(onClick = flip), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) { CheckMark(on); Secondary(text) }
    Column(verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
        line(allowMute, w.voiceAllowMute) { onChange(!allowMute, hostAllowed, hostCanKick, max) }
        line(hostAllowed, w.voiceHostAllowed) { onChange(allowMute, !hostAllowed, hostCanKick, max) }
        line(hostCanKick, w.voiceHostKicks) { onChange(allowMute, hostAllowed, !hostCanKick, max) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
            Secondary(w.voiceMax(max))
            Button("−", onClick = { if (max > 2) onChange(allowMute, hostAllowed, hostCanKick, max - 1) }, kind = ButtonKind.Quiet)
            Button("+", onClick = { if (max < 1000) onChange(allowMute, hostAllowed, hostCanKick, max + 1) }, kind = ButtonKind.Quiet)
        }
    }
}
