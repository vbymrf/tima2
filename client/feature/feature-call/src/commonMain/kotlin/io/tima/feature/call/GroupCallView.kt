package io.tima.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Avatar
import io.tima.core.ui.AvatarSize
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

// ── ВИД ГРУППОВОГО ЗВОНКА (заказчик 2026-10-01) ─────────────────────────────────────
//
// Сверху слева — «Вид»: по одному, по 2, по 4 и «Показывать себя». Сверху справа, перед
// временем, — «‹ 2/5 ›». Себя — окошко в правом верхнем углу, четверть ширины. Порядок —
// по входу: вошедший — в конец, ушедший выпадает. Сначала страницы с видео, следом —
// страницы списком тех, кто только голосом, с отметкой микрофона. Нажатие на клетку
// разворачивает её на всё окно, «‹ Назад» возвращает. Принимаем видео только видимых.

/** Как показывать групповой — состояние вида, общее для окна 0 и области 3 ПК. */
class GroupView {
    /** Сколько клеток с видео на странице: 1, 2 или 4. */
    var perPage by mutableStateOf(4)

    /** Показывать ли своё окошко. */
    var showSelf by mutableStateOf(true)

    /** Страница — с нуля. */
    var page by mutableStateOf(0)

    /** Развёрнутая на всё окно клетка (ключ); `null` — страница. */
    var expanded by mutableStateOf<String?>(null)

    /** Открыт ли выбор вида. */
    var choosing by mutableStateOf(false)
}

/** Страница сетки: клетки с видео или список тех, кто только голосом. */
sealed interface GroupPage {
    val tiles: List<GroupTile>

    data class Video(override val tiles: List<GroupTile>) : GroupPage
    data class Voice(override val tiles: List<GroupTile>) : GroupPage
}

/** Сколько строк голосового списка на странице. */
const val VOICE_PER_PAGE = 8

/**
 * Порядок участников: прежний, ушедшие выпадают, вошедшие — в конец (заказчик
 * 2026-10-01, 4б). Говорящий не переезжает: листать страницы, которые сами меняются, нельзя.
 */
fun groupOrder(previous: List<String>, present: List<String>): List<String> {
    val here = present.toSet()
    val kept = previous.filter { it in here }
    return kept + present.filter { it !in kept.toSet() }
}

/**
 * Страницы: сначала с видео — по [perPage], потом голосовые — списком по [voicePerPage].
 * Пусто — одна пустая страница с видео: «пока вы одни».
 */
fun groupPages(peers: List<GroupTile>, perPage: Int, voicePerPage: Int = VOICE_PER_PAGE): List<GroupPage> {
    val video = peers.filter { it.cameraOn }
    val voice = peers.filter { !it.cameraOn }
    val pages = video.chunked(perPage.coerceAtLeast(1)).map { GroupPage.Video(it) } +
        voice.chunked(voicePerPage).map { GroupPage.Voice(it) }
    return pages.ifEmpty { listOf(GroupPage.Video(emptyList())) }
}

/** Чьё видео принимать: клетки текущей страницы или развёрнутая. Список голосом — ничьё. */
fun groupVisible(pages: List<GroupPage>, view: GroupView): Set<String> {
    view.expanded?.let { return setOf(it) }
    val page = pages.getOrNull(view.page.coerceIn(0, pages.size - 1)) ?: return emptySet()
    return if (page is GroupPage.Video) page.tiles.map { it.key }.toSet() else emptySet()
}

/** Верхняя полоса: «Вид» слева, название, «‹ 2/5 ›» и время справа. */
@Composable
internal fun GroupTopBar(group: GroupStage, view: GroupView, pages: Int, time: String) {
    val words = Tima.words.groupCall
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about2, vertical = TimaSpacing.about1),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(label = words.viewButton, onClick = { view.choosing = !view.choosing }, kind = ButtonKind.Quiet)
            Caption(group.title, modifier = Modifier.weight(1f), fontSize = TimaType.sz4, weight = FontWeight.Bold, lineOne = true)
            if (pages > 1 && view.expanded == null) {
                IconButton(glyph = "‹", onClick = { if (view.page > 0) view.page-- }, live = view.page > 0)
                Caption("" + (view.page + 1) + "/" + pages, fontSize = TimaType.sz5, weight = FontWeight.Bold, lineOne = true)
                IconButton(glyph = "›", onClick = { if (view.page < pages - 1) view.page++ }, live = view.page < pages - 1)
            }
            Secondary(words.count(group.count, group.max) + " · " + time, lineOne = true)
        }
        if (view.choosing) ViewChoice(view)
    }
}

/** Выбор вида: по одному, по 2, по 4; показывать ли себя. */
@Composable
private fun ViewChoice(view: GroupView) {
    val words = Tima.words.groupCall
    Column(Modifier.fillMaxWidth().background(Tima.colors.functional).padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about1)) {
        Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2), verticalAlignment = Alignment.CenterVertically) {
            for ((n, label) in listOf(1 to words.viewOne, 2 to words.viewTwo, 4 to words.viewFour)) {
                Button(
                    label = label,
                    onClick = {
                        view.perPage = n
                        view.page = 0
                        view.expanded = null
                    },
                    kind = if (view.perPage == n) ButtonKind.Action else ButtonKind.Quiet,
                )
            }
        }
        Row(
            Modifier.clickable { view.showSelf = !view.showSelf }.padding(vertical = TimaSpacing.about1),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CheckMark(view.showSelf)
            Caption(words.viewSelf, fontSize = TimaType.sz4, lineOne = true)
        }
    }
}

/**
 * Тело группового: страница или развёрнутая клетка, и своё окошко в углу. На ПК с тремя
 * областями себя показывает окно 0, а это тело стоит в области 3 — без своего окошка
 * ([withSelf] = `false`).
 */
@Composable
fun GroupCallBody(group: GroupStage, view: GroupView, withSelf: Boolean, modifier: Modifier = Modifier) {
    val self = group.tiles.firstOrNull { it.self }
    val peers = group.tiles.filter { !it.self }
    val pages = groupPages(peers, view.perPage)
    // Ушёл участник — страниц стало меньше: показываем последнюю, состояние не трогаем.
    val at = view.page.coerceIn(0, pages.size - 1)
    val expanded = view.expanded?.let { key -> group.tiles.firstOrNull { it.key == key } }
    BoxWithConstraints(modifier) {
        if (expanded != null) {
            GroupCell(expanded, Modifier.fillMaxSize())
            Button(
                label = "‹ " + Tima.words.groupCall.back,
                onClick = { view.expanded = null },
                kind = ButtonKind.Quiet,
                modifier = Modifier.align(Alignment.TopStart).padding(TimaSpacing.about2),
            )
            return@BoxWithConstraints
        }
        when (val page = pages[at]) {
            is GroupPage.Video ->
                if (page.tiles.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        io.tima.core.ui.Tertiary(Tima.words.groupCall.alone)
                    }
                } else {
                    PageGrid(page.tiles, view.perPage, Modifier.fillMaxSize()) { view.expanded = it.key }
                }
            // Список не уходит под своё окошко: кнопки микрофона должны быть видны.
            is GroupPage.Voice -> VoiceList(
                page.tiles,
                Modifier.fillMaxSize().padding(end = if (withSelf && view.showSelf && self != null) maxWidth / 4 + TimaSpacing.about2 else 0.dp),
            )
        }
        // Себя — окошко в правом верхнем углу, четверть ширины, 3:4. Меньше — лица не
        // различить, больше — закрывает собеседника. Нажатием не меняется (5а).
        if (withSelf && view.showSelf && self != null) {
            // Рамка — чтобы своё окошко не сливалось с клеткой под ним.
            GroupCell(
                self,
                Modifier.align(Alignment.TopEnd).padding(TimaSpacing.about2)
                    .width(maxWidth / 4).aspectRatio(3f / 4f)
                    .border(2.dp, Tima.colors.text3),
                compact = true,
            )
        }
    }
}

/** Страница с видео: 1 — во весь кадр, 2 — одна над другой, 4 — 2×2. */
@Composable
private fun PageGrid(tiles: List<GroupTile>, perPage: Int, modifier: Modifier, onOpen: (GroupTile) -> Unit) {
    val columns = if (perPage >= 4) 2 else 1
    Column(modifier.padding(TimaSpacing.about1), verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
        val rows = tiles.chunked(columns)
        val wanted = (perPage + columns - 1) / columns
        for (row in rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
                for (tile in row) GroupCell(tile, Modifier.weight(1f).fillMaxHeight().clickable { onOpen(tile) })
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        // Неполная страница не растягивает клетки: место недостающих рядов остаётся пустым.
        repeat(wanted - rows.size) { Box(Modifier.weight(1f)) }
    }
}

/** Страница тех, кто только голосом: строкой, с микрофоном; говорящий — в рамке. */
@Composable
private fun VoiceList(tiles: List<GroupTile>, modifier: Modifier) {
    Column(modifier) {
        Caption(
            Tima.words.groupCall.voiceTitle,
            modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about1),
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
        )
        for (tile in tiles) {
            ListLine(
                modifier = if (tile.speaking) Modifier.border(2.dp, Tima.colors.navigation) else Modifier,
                left = { Avatar(letters = tile.letters) },
                middle = { Name(tile.name) },
                right = { IconButton(glyph = if (tile.microphoneOn) "🎤" else "🔇", onClick = {}, live = tile.microphoneOn) },
            )
        }
    }
}

/** Клетка участника: картинка или аватар, имя, 🔇, ⏸, пропажа видео; говорящий — в рамке. */
@Composable
internal fun GroupCell(tile: GroupTile, modifier: Modifier, compact: Boolean = false) {
    val colors = Tima.colors
    Box(
        modifier
            .background(colors.functional)
            .border(width = if (tile.speaking) 3.dp else 0.dp, color = if (tile.speaking) colors.navigation else colors.functional),
    ) {
        if (tile.video != null) {
            CallVideo(tile.video, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Avatar(letters = tile.letters, size = if (compact) AvatarSize.Normal else AvatarSize.Big)
            }
        }
        if (!compact) {
            tile.videoTrouble?.let {
                Caption(
                    it,
                    modifier = Modifier.align(Alignment.TopStart).background(colors.surface.copy(alpha = 0.8f))
                        .padding(horizontal = TimaSpacing.about2, vertical = TimaSpacing.about1),
                    fontSize = TimaType.sz6,
                    weight = FontWeight.SemiBold,
                    color = colors.alarm,
                )
            }
            val marks = (if (!tile.microphoneOn) " 🔇" else "") + (if (tile.paused) " ⏸" else "")
            Caption(
                tile.name + marks,
                modifier = Modifier.align(Alignment.BottomStart).background(colors.surface.copy(alpha = 0.7f))
                    .padding(horizontal = TimaSpacing.about2, vertical = TimaSpacing.about1),
                fontSize = TimaType.sz5,
                weight = FontWeight.SemiBold,
                lineOne = true,
            )
        }
    }
}
