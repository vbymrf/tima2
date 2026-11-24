package io.tima.feature.group

import io.tima.core.ui.TextPlace
import io.tima.core.ui.ProvidePlace
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
import androidx.compose.foundation.layout.heightIn
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

    /** Кнопки полосы аудиочата (ПЛАН-(ВС) ВС5): микрофон и выход. */
    const val STRIP_MIC = "voice:strip:mic"
    const val STRIP_LEAVE = "voice:strip:leave"
    const val CREATE = "voice:create"
    const val ROOM = "voice:room"
    const val NAME = "voice:name"
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
    // «Списки» «Шрифтов и размеров» (заказчик 2026-11-23); шапка и вкладки ставят своё.
    ProvidePlace(TextPlace.LISTS) {
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
        // С переносом: в одну строку три кнопки не помещались, и «Выйти» уезжала за правый край — на realme её не было
        // видно вовсе (живой разговор троих 2026-11-19).
        FlowRow(
            Modifier.fillMaxWidth().padding(TimaSpacing.about4),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
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
}

/**
 * Полоса звукового чата поверх окон, пока человек в чате (§1.8, 4): нажатие — окно чата.
 *
 * С 2026-11-20 (ПЛАН-(ВС) ВС5, заказчик: «увеличить размер, сделать кнопки в виде иконок; панель узкая, её не видно
 * сверху») — высотой строки списка в три строки: значок аудиочата, название и «в чате N», справа две круглые кнопки —
 * микрофон (перечёркнут, когда выключен) и красная трубка выхода. Прежде — строка мелкого текста с надписями.
 */
@Composable
fun VoiceStrip(
    title: String,
    allowMute: Boolean,
    mic: Boolean,
    onOpen: () -> Unit,
    onMic: (Boolean) -> Unit,
    onLeave: () -> Unit,
    /** Сколько в чате, со мной; `null` — не знаем, строки «в чате» нет. */
    count: Int? = null,
) {
    val w = Tima.words.socium
    val ink = Tima.colors.onAccent
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).background(Tima.colors.confirmed).clickable(onClick = onOpen)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2).testTag(VoiceTags.STRIP),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
    ) {
        Box(
            Modifier.size(44.dp).background(ink.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            TypeIcon(io.tima.domain.chat.EntityType(io.tima.domain.chat.EntityKind.Voice, io.tima.domain.chat.EntityReach.Public), 26.dp)
        }
        Column(Modifier.weight(1f)) {
            Caption(title, fontSize = TimaType.sz4, weight = FontWeight.Bold, color = ink, lineOne = true)
            Caption(
                if (count != null) w.voiceChat + " · " + w.voiceInside(count) else w.voiceChat,
                fontSize = TimaType.sz5,
                color = ink,
                lineOne = true,
            )
        }
        if (allowMute) {
            io.tima.core.ui.IconButton(
                glyph = if (mic) "🎤" else "🔇",
                onClick = { onMic(!mic) },
                live = mic,
                modifier = Modifier.testTag(VoiceTags.STRIP_MIC),
            )
        }
        io.tima.core.ui.IconButton(
            glyph = "📞",
            onClick = onLeave,
            background = Tima.colors.alarm,
            colorGlyph = ink,
            modifier = Modifier.testTag(VoiceTags.STRIP_LEAVE),
        )
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
                left = { TypeIcon(io.tima.domain.chat.EntityType(io.tima.domain.chat.EntityKind.Voice, io.tima.domain.chat.EntityReach.Public), 24.dp, markBeside = true) },
                middle = { Name(title); Tertiary(w.voiceInside(count(id))) },
                right = { Box(Modifier.size(10.dp).background(if (count(id) > 0) Tima.colors.confirmed else Tima.colors.quiet, CircleShape)) },
            )
        }
        if (onCreate != null) {
            var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                io.tima.core.ui.Field(name, { name = it }, modifier = Modifier.weight(1f).testTag(VoiceTags.NAME), hint = w.voiceNewHint, sentences = true)
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
