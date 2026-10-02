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
import androidx.compose.foundation.layout.height
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

/** Как показывать: страницами или «Говорящий» — два места наверху и пузыри внизу. */
enum class GroupMode { Pages, Speaker }

/** Как показывать групповой — состояние вида, общее для окна 0 и области 3 ПК. */
class GroupView {
    /** Страницами или «Говорящий» (заказчик 2026-10-01). */
    var mode by mutableStateOf(GroupMode.Pages)

    /** Два места наверху вида «Говорящий» — ключи клеток. */
    var slots by mutableStateOf(listOf<String?>(null, null))

    /** Правило мест — помнит, кто когда начал и кончил говорить. */
    val speaker = SpeakerSlots()

    /** Выбор вида строкой для сохранения: `режим:сколько:себя`. */
    fun saved(): String = mode.name + ":" + perPage + ":" + (if (showSelf) "1" else "0")

    /** Выбор вида из сохранённого; негодное — умолчание. */
    fun restore(saved: String?) {
        val parts = saved?.split(':') ?: return
        GroupMode.entries.firstOrNull { it.name == parts.getOrNull(0) }?.let { mode = it }
        parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in setOf(1, 2, 4) }?.let { perPage = it }
        parts.getOrNull(2)?.let { showSelf = it != "0" }
    }

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

    /** Открыт ли лист событий звонка и сколько событий уже видено — зелёная «развернуть». */
    var eventsOpen by mutableStateOf(false)
    var eventsSeen by mutableStateOf(0)
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
    if (view.mode == GroupMode.Speaker) return view.slots.filterNotNull().toSet()
    val page = pages.getOrNull(view.page.coerceIn(0, pages.size - 1)) ?: return emptySet()
    return if (page is GroupPage.Video) page.tiles.map { it.key }.toSet() else emptySet()
}

/**
 * Верхняя полоса: «развернуть» событий и «Вид» слева, название, «‹ 2/5 ›» и время справа.
 * События больше не занимают строку сверху: «развернуть» зелёная, когда есть новое, серая —
 * когда нет; нажатие открывает лист событий поверх окна (заказчик 2026-10-02).
 */
@Composable
internal fun GroupTopBar(group: GroupStage, view: GroupView, pages: Int, time: String, events: Int = 0) {
    val words = Tima.words.groupCall
    if (view.eventsOpen && events > view.eventsSeen) view.eventsSeen = events
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about2, vertical = TimaSpacing.about1),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                glyph = if (view.eventsOpen) "▲" else "▼",
                onClick = {
                    view.eventsOpen = !view.eventsOpen
                    view.eventsSeen = events
                },
                live = events > view.eventsSeen,
            )
            Button(label = words.viewButton, onClick = { view.choosing = !view.choosing }, kind = ButtonKind.Quiet)
            Caption(group.title, modifier = Modifier.weight(1f), fontSize = TimaType.sz4, weight = FontWeight.Bold, lineOne = true)
            if (pages > 1 && view.expanded == null && view.mode == GroupMode.Pages) {
                IconButton(glyph = "‹", onClick = { if (view.page > 0) view.page-- }, live = view.page > 0)
                Caption("" + (view.page + 1) + "/" + pages, fontSize = TimaType.sz5, weight = FontWeight.Bold, lineOne = true)
                IconButton(glyph = "›", onClick = { if (view.page < pages - 1) view.page++ }, live = view.page < pages - 1)
            }
            Secondary(words.count(group.count, group.max) + " · " + time, lineOne = true)
        }
        if (view.choosing) ViewChoice(view)
    }
}

/** Выбор вида поверх окна — тот же, что в настройке звонка. */
@Composable
private fun ViewChoice(view: GroupView) = GroupViewChoice(view, Modifier.background(Tima.colors.functional))

/**
 * Выбор вида — то же подокно в звонке и в настройке группового звонка, в стиле настроек
 * уведомлений (заказчик 2026-10-02): переключатель «Тип» — «Говорящий» или «Групповой»; у
 * «Группового» — «По одному», «По 2», «По 4»; ниже «Показывать себя». Выбор сохраняется.
 */
@Composable
fun GroupViewChoice(view: GroupView, modifier: Modifier = Modifier) {
    val words = Tima.words.groupCall
    Column(modifier.fillMaxWidth().padding(vertical = TimaSpacing.about1)) {
        Caption(
            words.viewType,
            modifier = Modifier.padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about1),
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
            color = Tima.colors.text2,
        )
        ChoiceLine(words.viewSpeaker, words.viewSpeakerAbout, view.mode == GroupMode.Speaker, indent = false) {
            view.mode = GroupMode.Speaker
            view.expanded = null
        }
        ChoiceLine(words.viewGrid, words.viewGridAbout, view.mode == GroupMode.Pages, indent = false) {
            view.mode = GroupMode.Pages
            view.expanded = null
        }
        if (view.mode == GroupMode.Pages) {
            for ((n, label) in listOf(1 to words.viewOne, 2 to words.viewTwo, 4 to words.viewFour)) {
                ChoiceLine(label, null, view.perPage == n, indent = true) {
                    view.perPage = n
                    view.page = 0
                    view.expanded = null
                }
            }
        }
        ListLine(
            onClick = { view.showSelf = !view.showSelf },
            left = { CheckMark(view.showSelf) },
            middle = {
                Caption(words.viewSelf, fontSize = TimaType.sz4, weight = if (view.showSelf) FontWeight.Bold else FontWeight.Normal)
                io.tima.core.ui.Tertiary(words.viewSelfAbout)
            },
        )
    }
}

/** Строка выбора с точкой — как в настройках уведомлений. */
@Composable
private fun ChoiceLine(label: String, about: String?, on: Boolean, indent: Boolean, onClick: () -> Unit) {
    ListLine(
        modifier = if (indent) Modifier.padding(start = TimaSpacing.about5) else Modifier,
        onClick = onClick,
        left = { io.tima.core.ui.RadioMark(on) },
        middle = {
            Caption(label, fontSize = TimaType.sz4, weight = if (on) FontWeight.Bold else FontWeight.Normal)
            about?.let { io.tima.core.ui.Tertiary(it) }
        },
    )
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
        val selfShown = withSelf && view.showSelf && self != null
        val selfWidth = maxWidth / 4
        if (view.mode == GroupMode.Speaker) {
            SpeakerBody(group, view, peers, Modifier.fillMaxSize(), reserve = if (selfShown) selfWidth + TimaSpacing.about2 else 0.dp)
        } else when (val page = pages[at]) {
            is GroupPage.Video ->
                if (page.tiles.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        io.tima.core.ui.Tertiary(Tima.words.groupCall.alone)
                    }
                } else {
                    PageGrid(page.tiles, view.perPage, Modifier.fillMaxSize()) { view.expanded = it.key }
                }
            is GroupPage.Voice -> VoiceList(page.tiles, Modifier.fillMaxSize())
        }
        // «Я» — малое окно справа внизу во всех видах (заказчик 2026-10-02): четверть ширины,
        // 3:4, в рамке. Нажатием не меняется.
        if (selfShown && self != null) {
            GroupCell(
                self,
                Modifier.align(Alignment.BottomEnd).padding(TimaSpacing.about2)
                    .width(selfWidth).aspectRatio(3f / 4f)
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

/**
 * Правило двух мест вида «Говорящий» (заказчик 2026-10-01).
 *
 * - Наверх встаёт тот, кто говорит без перерыва дольше [enterMs] — кашель и «угу» картинку
 *   не дёргают.
 * - Место занимает пустое или того, кто молчит дольше [leaveMs] («вытесняемый»); местами не
 *   меняем. Оба наверху ещё говорят — новый ждёт.
 * - Говорит один — во втором месте остаётся последний говоривший, пока его не вытеснят (3а).
 * - Закреплённый ([pinned], один — 7а) держит своё место; говорящие его не вытесняют.
 * - Никто ещё не говорил — места заняты первыми по порядку входа: экран не пустой.
 */
class SpeakerSlots(private val enterMs: Long = 1_000, private val leaveMs: Long = 3_000) {
    private val since = HashMap<String, Long>()
    private val last = HashMap<String, Long>()
    var slots: List<String?> = listOf(null, null)
        private set

    fun update(order: List<String>, speaking: Set<String>, pinned: String?, now: Long): List<String?> {
        for (k in speaking) {
            since.getOrPut(k) { now }
            last[k] = now
        }
        since.keys.retainAll(speaking)
        val here = order.toSet()
        val s = slots.map { it?.takeIf { k -> k in here } }.toMutableList()
        val pin = pinned?.takeIf { it in here }
        if (pin != null && pin !in s) {
            val i = s.indexOfFirst { it == null }.takeIf { it >= 0 } ?: displaceable(s, now, pin) ?: 1
            s[i] = pin
        }
        val waiting = speaking.filter { it in here && it !in s && now - (since[it] ?: now) >= enterMs }.sortedBy { since[it] }
        for (k in waiting) {
            val i = s.indexOfFirst { it == null }.takeIf { it >= 0 } ?: displaceable(s, now, pin) ?: break
            s[i] = k
        }
        for (i in s.indices) if (s[i] == null) s[i] = order.firstOrNull { it !in s }
        slots = s
        return s
    }

    private fun displaceable(s: List<String?>, now: Long, pinned: String?): Int? =
        // Не говоривший вовсе (поставлен наверх, пока все молчали) уступает сразу.
        s.indices
            .filter { s[it] != null && s[it] != pinned && (last[s[it]]?.let { t -> now - t >= leaveMs } ?: true) }
            .minByOrNull { last[s[it]] ?: Long.MIN_VALUE }
}

/**
 * «Говорящий»: верх — два места рядом; низ — пузыри всех, кто в комнате (заказчик 2026-10-02):
 * у не автора в две строки, если влезает, — пузырь узкий, по аватару; у автора — одна строка,
 * «Голос» и «📌» встают столбиком рядом с аватаром. В конце ряда — запас [reserve], чтобы
 * последний пузырь выкручивался из-под окна «Я».
 */
@Composable
private fun SpeakerBody(group: GroupStage, view: GroupView, peers: List<GroupTile>, modifier: Modifier, reserve: androidx.compose.ui.unit.Dp) {
    val words = Tima.words.groupCall
    val everyone = peers + group.tiles.filter { it.self }
    val author = group.onVoice != null || group.onPin != null
    Column(modifier) {
        Row(
            Modifier.weight(1f).fillMaxWidth().padding(TimaSpacing.about1),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
        ) {
            for (key in view.slots) {
                val tile = everyone.firstOrNull { it.key == key }
                if (tile == null) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(Tima.colors.functional))
                } else {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        GroupCell(tile, Modifier.fillMaxSize().clickable { view.expanded = tile.key })
                        if (tile.key == group.pinnedKey) {
                            Caption("📌", modifier = Modifier.align(Alignment.TopEnd).padding(TimaSpacing.about2), fontSize = TimaType.sz4)
                        }
                    }
                }
            }
        }
        val padding = androidx.compose.foundation.layout.PaddingValues(
            start = TimaSpacing.about3, end = TimaSpacing.about3 + reserve, top = TimaSpacing.about1, bottom = TimaSpacing.about1,
        )
        if (author) {
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                contentPadding = padding,
            ) {
                items(everyone.size, key = { everyone[it].key }) { i -> Bubble(everyone[i], group, words, author = true) }
            }
        } else {
            androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(
                rows = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (everyone.size > 4) 2 else 1),
                modifier = Modifier.fillMaxWidth().height(if (everyone.size > 4) BUBBLE_HEIGHT * 2 + TimaSpacing.about3 else BUBBLE_HEIGHT + TimaSpacing.about2),
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
                contentPadding = padding,
            ) {
                items(everyone.size, key = { everyone[it].key }) { i -> Bubble(everyone[i], group, words, author = false) }
            }
        }
    }
}

/** Высота пузыря: аватар и строка имени. */
private val BUBBLE_HEIGHT = 64.dp

/** Пузырь участника: аватар, имя, рамка говорящего, отметки; у автора — «Голос» и «📌» столбиком. */
@Composable
private fun Bubble(tile: GroupTile, group: GroupStage, words: io.tima.core.words.GroupCallWords, author: Boolean) {
    val marks = listOfNotNull(
        "🔇".takeIf { !tile.microphoneOn || tile.micForbidden },
        "📌".takeIf { tile.key == group.pinnedKey },
    ).joinToString("")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.border(
                    width = if (tile.speaking) 3.dp else 0.dp,
                    color = if (tile.speaking) Tima.colors.navigation else Tima.colors.surface,
                ).padding(2.dp),
            ) {
                Avatar(letters = tile.letters)
                if (marks.isNotEmpty()) {
                    Caption(marks, modifier = Modifier.align(Alignment.TopEnd), fontSize = TimaType.sz6, lineOne = true)
                }
            }
            Caption(
                tile.name,
                fontSize = TimaType.sz6,
                weight = FontWeight.SemiBold,
                color = if (tile.micForbidden) Tima.colors.alarm else Tima.colors.text,
                lineOne = true,
            )
        }
        if (author && !tile.self) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                group.onVoice?.let { voice ->
                    IconButton(
                        glyph = if (tile.micForbidden) "🔇" else "🎤",
                        onClick = { voice(tile) },
                        live = !tile.micForbidden,
                        background = if (tile.micForbidden) Tima.colors.alarm else null,
                        colorGlyph = if (tile.micForbidden) Tima.colors.onAccent else null,
                    )
                }
                group.onPin?.let { pin -> IconButton(glyph = "📌", onClick = { pin(tile) }, live = tile.key == group.pinnedKey) }
            }
        }
    }
}
