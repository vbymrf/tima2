package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Avatar
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.CheckMark
import io.tima.core.ui.Field
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaZones
import io.tima.core.ui.words
import io.tima.domain.chat.Section

// ── ЖУРНАЛ ЗВОНКА (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ ГЗ6, решение заказчика 8) ─────────────────
//
// Тот же вид, что журнал контактов: полоса разделов, поиск, строки с квадратом справа,
// полоса действий внизу — видна всегда и не прыгает. Первым разделом — «Участники»: кто
// добавлен; остальные разделы — кого можно добавить. У участника в строке «микрофон» и
// «камера»: у создателя — у каждого, у остальных — только у себя.

/** Кого можно позвать: человек из книги или участник группы. */
data class CallCandidate(
    val userId: String,
    val name: String,
    val letters: String,
    val face: ImageBitmap? = null,
    /** Раздел книги; `""` — «Общий». У участников группы — пусто. */
    val sectionId: String = "",
)

/** Что с участником звонка. */
enum class CallMemberState { Self, In, Invited, Added, Left, Removed }

/** Участник звонка — строка раздела «Участники». */
data class CallMember(
    val userId: String,
    val name: String,
    val letters: String,
    val state: CallMemberState,
    val face: ImageBitmap? = null,
    val microphoneOn: Boolean = false,
    val cameraOn: Boolean = false,
    val creator: Boolean = false,
    /** Запрет создателя: сервер не принимает от него звук или видео. */
    val micForbidden: Boolean = false,
    val videoForbidden: Boolean = false,
)

/** Ключ раздела «Участники» на полосе. */
private const val PARTICIPANTS = "participants"

/** Ключ раздела «Все контакты» или «Группа». */
private const val EVERYONE = "everyone"

/**
 * Журнал звонка.
 *
 * @param live звонок идёт; `false` — звонок собирается (решение 8: при создании активны
 *   «Добавить» и «Удалить»).
 * @param mine у меня команды — я создатель или собираю звонок сам.
 * @param fromGroup кандидаты — участники группы, а не книга.
 * @param setup «Звонить» и «Включить видео» с главной кнопкой — пока звонок собирается.
 */
@Composable
fun CallLedgerPage(
    members: List<CallMember>,
    candidates: List<CallCandidate>,
    sections: List<Section>,
    live: Boolean,
    mine: Boolean,
    fromGroup: Boolean,
    max: Int,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    view: BookView = BookView(),
    setup: CallLedgerSetup? = null,
    onAdd: (List<String>) -> Unit = {},
    onRemove: (List<String>) -> Unit = {},
    /** Запретить (`true`) или разрешить микрофон отмеченным (уточнение 2026-10-01). */
    onForbidMic: (List<String>, Boolean) -> Unit = { _, _ -> },
    onForbidCamera: (List<String>, Boolean) -> Unit = { _, _ -> },
    onPause: (Boolean) -> Unit = {},
    onStop: () -> Unit = {},
    onOwnMic: (Boolean) -> Unit = {},
    onOwnCamera: (Boolean) -> Unit = {},
) {
    val words = Tima.words.groupCall
    val book = Tima.words.book
    // Открывается всегда на «Все контакты» (заказчик 2026-10-02).
    var tab by remember { mutableStateOf(EVERYONE) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    val counted = members.count { it.state != CallMemberState.Left && it.state != CallMemberState.Removed }
    val present = members.filter { it.state != CallMemberState.Removed }.map { it.userId }.toSet()
    val q = query.trim().lowercase()
    val pool = candidates
        .filter { it.userId !in present }
        .filter { tab == EVERYONE || tab == PARTICIPANTS || it.sectionId == (if (tab == COMMON_SECTION) "" else tab) }
        .filter { q.isEmpty() || it.name.lowercase().contains(q) }
        .sortedBy { it.name.lowercase() }
    val shownMembers = members.filter { q.isEmpty() || it.name.lowercase().contains(q) }
    val onParticipants = tab == PARTICIPANTS
    val chosenMembers = members.filter { it.userId in selected && it.state != CallMemberState.Self }.map { it.userId }
    val chosenPool = pool.filter { it.userId in selected }.map { it.userId }

    Column(modifier.fillMaxWidth()) {
        // Разделы — та же полоса, что в журнале контактов: «Участники» первым, дальше —
        // откуда звать. У группы — один раздел «Группа»; у книги — её разделы.
        val tabs = buildList {
            add(SectionTab(EVERYONE, if (fromGroup) words.groupMembers else words.allContacts, 0))
            if (!fromGroup) addAll(sectionTabs(sections, null, book).drop(1))
        }
        // «Участники» — отдельным серым пузырём слева (второй уровень серого темы), дальше —
        // откуда звать (заказчик 2026-10-02).
        Row(verticalAlignment = Alignment.CenterVertically) {
            val on = tab == PARTICIPANTS
            Caption(
                words.participants + " · " + counted,
                modifier = Modifier.padding(start = TimaSpacing.about3)
                    .background(Tima.colors.quiet, androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .clickable {
                        tab = PARTICIPANTS
                        selected = emptySet()
                    }
                    .padding(horizontal = TimaSpacing.about3, vertical = TimaSpacing.about1),
                fontSize = TimaType.sz5,
                weight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                color = if (on) Tima.colors.navigation else Tima.colors.text,
                lineOne = true,
            )
            SectionsRow(
                tabs = tabs,
                chosen = if (tab == PARTICIPANTS) "-" else tab,
                icons = view.icons && !fromGroup,
                onPick = { id ->
                    tab = when (id) {
                        "" -> EVERYONE
                        else -> id
                    }
                    selected = emptySet()
                },
                modifier = Modifier.weight(1f),
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
        ) {
            Field(value = query, onChange = { query = it }, hint = book.ledgerSearch)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tertiary(words.picked(counted, max), lineOne = true)
                if (mine) {
                    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3)) {
                        CallLink(book.ledgerSelectAll) {
                            selected = if (onParticipants) {
                                members.filter { it.state != CallMemberState.Self }.map { it.userId }.toSet()
                            } else {
                                pool.map { it.userId }.toSet()
                            }
                        }
                        CallLink(book.ledgerSelectNone) { selected = emptySet() }
                    }
                }
            }
        }

        // Едет только список; разделы и полоса действий — на месте.
        LazyColumn(Modifier.weight(1f)) {
            if (onParticipants) {
                if (shownMembers.size <= 1 && !live) {
                    item { Empty(words.nobody) }
                }
                items(shownMembers, key = { "m" + it.userId }) { m ->
                    MemberRow(
                        m = m,
                        mine = mine,
                        live = live,
                        on = m.userId in selected,
                        onToggle = { selected = if (m.userId in selected) selected - m.userId else selected + m.userId },
                        onMic = {
                            if (m.state == CallMemberState.Self) onOwnMic(!m.microphoneOn) else onForbidMic(listOf(m.userId), !m.micForbidden)
                        },
                        onCamera = {
                            if (m.state == CallMemberState.Self) onOwnCamera(!m.cameraOn) else onForbidCamera(listOf(m.userId), !m.videoForbidden)
                        },
                    )
                }
            } else {
                if (pool.isEmpty()) item { Empty(book.listEmpty) }
                items(pool, key = { "c" + it.userId }) { c ->
                    val on = c.userId in selected
                    ListLine(
                        onClick = if (mine) ({ selected = if (on) selected - c.userId else selected + c.userId }) else null,
                        left = { Avatar(letters = c.letters, image = c.face) },
                        middle = {
                            Column {
                                Name(c.name)
                                if (!fromGroup) {
                                    Tertiary(sections.firstOrNull { it.id == c.sectionId }?.name ?: book.commonSection, lineOne = true)
                                }
                            }
                        },
                        right = if (mine) ({ Box(Modifier.padding(TimaSpacing.about2)) { CheckMark(on) } }) else null,
                    )
                }
            }
        }

        // ── ПОЛОСА ДЕЙСТВИЙ — ВИДНА ВСЕГДА ─────────────────────────────────────
        Column(
            Modifier.fillMaxWidth().background(Tima.colors.functional)
                .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        ) {
            if (!mine) {
                Tertiary(words.onlyCreator)
            } else {
                val tooMany = !onParticipants && counted + chosenPool.size > max
                if (tooMany) Caption(words.tooMany(max), fontSize = TimaType.sz5, weight = FontWeight.SemiBold, color = Tima.colors.alarm)
                Row(
                    Modifier.fillMaxWidth(),
                    // В идущем звонке шесть кнопок — по ширине; до звонка две — рядом слева.
                    horizontalArrangement = if (live) Arrangement.SpaceBetween else Arrangement.spacedBy(TimaSpacing.about5),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CallAction("➕", words.add, chosenPool.isNotEmpty() && !tooMany) {
                        onAdd(chosenPool)
                        selected = emptySet()
                    }
                    CallAction("➖", words.remove, chosenMembers.isNotEmpty()) {
                        onRemove(chosenMembers)
                        selected = emptySet()
                    }
                    if (live) {
                        // Все отмеченные уже под запретом — кнопка снимает его, иначе ставит.
                        val chosen = members.filter { it.userId in chosenMembers }
                        CallAction("🔇", words.mic, chosenMembers.isNotEmpty()) {
                            onForbidMic(chosenMembers, !chosen.all { it.micForbidden })
                            selected = emptySet()
                        }
                        CallAction("🚫", words.camera, chosenMembers.isNotEmpty()) {
                            onForbidCamera(chosenMembers, !chosen.all { it.videoForbidden })
                            selected = emptySet()
                        }
                        CallAction(if (paused) "▶" else "⏸", if (paused) words.resume else words.pause, true) { onPause(!paused) }
                        CallAction("⏹", words.stop, true, danger = true) { onStop() }
                    }
                }
                setup?.let { SetupFooter(it, enabled = members.any { m -> m.state != CallMemberState.Self }) }
            }
        }
    }
}

/**
 * Пока звонок собирается — «Звонить», «Включить видео» и главная кнопка («Позвонить» или
 * «Создать чат») в полосе действий журнала.
 */
data class CallLedgerSetup(
    val ring: Boolean,
    val video: Boolean,
    val primary: String,
    val onRing: (Boolean) -> Unit,
    val onVideo: (Boolean) -> Unit,
    val onPrimary: () -> Unit,
    /** Вместо главной кнопки — почему нельзя (нет прав, не на связи). */
    val trouble: String? = null,
)

@Composable
private fun SetupFooter(setup: CallLedgerSetup, enabled: Boolean) {
    val words = Tima.words.groupCall
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckChip(words.ring, setup.ring) { setup.onRing(!setup.ring) }
        CheckChip(words.video, setup.video) { setup.onVideo(!setup.video) }
    }
    setup.trouble?.let { Caption(it, fontSize = TimaType.sz5, weight = FontWeight.SemiBold, color = Tima.colors.alarm) }
    Button(label = setup.primary, onClick = setup.onPrimary, modifier = Modifier.fillMaxWidth(), enabled = enabled && setup.trouble == null)
}

/** Квадрат с подписью в одну строку — для «Звонить» и «Включить видео» в полосе. */
@Composable
private fun CheckChip(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(vertical = TimaSpacing.about1),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckMark(on)
        Caption(label, fontSize = TimaType.sz4, weight = if (on) FontWeight.Bold else FontWeight.Normal, lineOne = true)
    }
}

@Composable
private fun MemberRow(
    m: CallMember,
    mine: Boolean,
    live: Boolean,
    on: Boolean,
    onToggle: () -> Unit,
    onMic: () -> Unit,
    onCamera: () -> Unit,
) {
    val words = Tima.words.groupCall
    val self = m.state == CallMemberState.Self
    val state = when (m.state) {
        CallMemberState.Self -> words.stateSelf
        CallMemberState.In -> words.stateIn
        CallMemberState.Invited -> words.stateInvited
        CallMemberState.Added -> words.stateAdded
        CallMemberState.Left -> words.stateLeft
        CallMemberState.Removed -> words.stateRemoved
    }
    val inRoom = live && (self || m.state == CallMemberState.In)
    // Кнопки микрофона и камеры — у создателя у каждого в комнате, у остальных — у себя.
    val controls = inRoom && (self || mine)
    ListLine(
        onClick = if (mine && !self) onToggle else null,
        left = { Avatar(letters = m.letters, image = m.face) },
        middle = {
            Column {
                Name(m.name)
                Tertiary(
                    listOfNotNull(
                        state,
                        words.creator.takeIf { m.creator },
                        words.forbiddenMic.takeIf { m.micForbidden },
                        words.forbiddenVideo.takeIf { m.videoForbidden },
                    ).joinToString(" · "),
                    lineOne = true,
                )
            }
        },
        right = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (controls) {
                    // Запрещённое — красным: это не «выключено», а «не принимается».
                    ForbidButton(if (m.microphoneOn) "🎤" else "🔇", m.microphoneOn, m.micForbidden, onMic)
                    ForbidButton("📹", m.cameraOn, m.videoForbidden, onCamera)
                }
                if (mine && !self) {
                    Box(Modifier.clickable(onClick = onToggle).padding(TimaSpacing.about2)) { CheckMark(on) }
                }
            }
        },
    )
}

@Composable
private fun ForbidButton(glyph: String, on: Boolean, forbidden: Boolean, onClick: () -> Unit) {
    IconButton(
        glyph = glyph,
        onClick = onClick,
        live = on && !forbidden,
        background = if (forbidden) Tima.colors.alarm else null,
        colorGlyph = if (forbidden) Tima.colors.onAccent else null,
    )
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(TimaSpacing.about5), contentAlignment = Alignment.Center) {
        Tertiary(text, lineOne = false)
    }
}

/** Значок действия с подписью: пока не выделено — виден, но не действует. */
@Composable
private fun CallAction(glyph: String, label: String, active: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            glyph = glyph,
            onClick = { if (active) onClick() },
            live = active,
            background = if (danger && active) Tima.colors.alarm else null,
            colorGlyph = if (danger && active) Tima.colors.onAccent else null,
        )
        Tertiary(label, lineOne = true)
    }
}

@Composable
private fun CallLink(label: String, onClick: () -> Unit) {
    Caption(
        label,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = TimaSpacing.about1),
        fontSize = TimaType.sz5,
        weight = FontWeight.Bold,
        color = Tima.colors.navigation,
        lineOne = true,
    )
}

/** «?» журнала звонка: значки полосы действий и что делает каждый. */
@Composable
fun CallLedgerHelpPage(modifier: Modifier = Modifier) {
    val words = Tima.words.groupCall
    Column(modifier.fillMaxWidth()) {
        val rows = listOf(
            Triple("➕", words.add, words.helpAdd),
            Triple("➖", words.remove, words.helpRemove),
            Triple("🔇", words.mic, words.helpMic),
            Triple("🚫", words.camera, words.helpCamera),
            Triple("⏸", words.pause, words.helpPause),
            Triple("⏹", words.stop, words.helpStop),
            Triple("🎤", words.helpRowTitle, words.helpRow),
            Triple("☐", Tima.words.book.ledgerHelpSelectTitle, Tima.words.book.ledgerHelpSelect),
            Triple("▤", words.helpSectionsTitle, words.helpSections),
        )
        for ((glyph, title, about) in rows) {
            ListLine(
                left = {
                    if (glyph == "☐") {
                        Box(Modifier.size(TimaZones.zone1 * 0.8f), contentAlignment = Alignment.Center) { CheckMark(true) }
                    } else {
                        IconButton(glyph = glyph, onClick = {}, live = true)
                    }
                },
                middle = {
                    Name(title)
                    Secondary(about)
                },
            )
        }
    }
}

// ── НАСТРОЙКА ЗВОНКА (решения 3, 3б) ──────────────────────────────────────────────

/**
 * Что видно по кнопке «Групповой звонок»: «Звонить» (выключено), «Включить видео»
 * (включено) и две кнопки — выбрать участников (звонок сразу) или создать чат группового
 * звонка. Из группы первая кнопка — журнал группы, второй нет: группа уже есть.
 *
 * @param noRights вместо кнопок — почему нельзя начать (решение 14).
 */
@Composable
fun GroupCallSetup(
    ring: Boolean,
    video: Boolean,
    fromGroup: Boolean,
    onRing: (Boolean) -> Unit,
    onVideo: (Boolean) -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    onCreateChat: (() -> Unit)? = null,
    noRights: Boolean = false,
    /** Выбранный тип вида — зелёным под строкой «Вид»; `null` — строки нет. */
    viewNow: String? = null,
    /** «Вид» — подокно выбора вида, то же, что в звонке (заказчик 2026-10-01). */
    onView: () -> Unit = {},
) {
    val words = Tima.words.groupCall
    Column(modifier.fillMaxWidth().padding(vertical = TimaSpacing.about2)) {
        SetupCheck(words.ring, words.ringAbout, ring) { onRing(!ring) }
        SetupCheck(words.video, words.videoAbout, video) { onVideo(!video) }
        // «Вид» — сразу после «Включить видео», в том же виде: название, описание, справа
        // маленькая кнопка «Вид», как в окне звонка.
        viewNow?.let { now ->
            ListLine(
                onClick = onView,
                left = { Box(Modifier.size(TimaZones.zone1 * 0.8f)) },
                middle = {
                    Caption(words.viewButton, fontSize = TimaType.sz4, weight = FontWeight.Bold)
                    Tertiary(words.viewAbout)
                    Caption(now, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = Tima.colors.navigation)
                },
                right = { Button(label = words.viewButton, onClick = onView, kind = ButtonKind.Quiet) },
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
            verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        ) {
            if (noRights) {
                Caption(words.noRights, fontSize = TimaType.sz5, weight = FontWeight.SemiBold, color = Tima.colors.alarm)
            } else {
                SetupButton(
                    if (fromGroup) words.pickFromGroup else words.pickFromContacts,
                    if (fromGroup) words.pickFromGroupAbout else words.pickFromContactsAbout,
                    ButtonKind.Action,
                    onPick,
                )
                onCreateChat?.let { SetupButton(words.createChat, words.createChatAbout, ButtonKind.Quiet, it) }
            }
        }
    }
}

@Composable
private fun SetupCheck(label: String, about: String, on: Boolean, onClick: () -> Unit) {
    ListLine(
        onClick = onClick,
        left = { CheckMark(on) },
        middle = {
            Caption(label, fontSize = TimaType.sz4, weight = if (on) FontWeight.Bold else FontWeight.Normal)
            Tertiary(about)
        },
    )
}

@Composable
private fun SetupButton(label: String, about: String, kind: ButtonKind, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TimaSpacing.about1)) {
        Button(label = label, onClick = onClick, kind = kind, modifier = Modifier.fillMaxWidth())
        Tertiary(about)
    }
}

// ── ПОЛОСА «ИДЁТ ЗВОНОК» В ГРУППЕ И ПРИГЛАШЕНИЕ (решение 3а) ─────────────────────

/**
 * Полоса над перепиской группы, пока в ней идёт звонок: «Идёт звонок · в звонке 3 · 14 мин
 * — Присоединиться». Вместо закреплённого сообщения: полоса знает правду сама и исчезает,
 * когда звонок кончился, а сообщение пришлось бы снимать.
 */
@Composable
fun GroupCallBanner(text: String, joinLabel: String?, onJoin: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Tima.colors.activity.copy(alpha = 0.18f))
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption("🔊", fontSize = TimaType.sz4)
        Caption(text, modifier = Modifier.weight(1f), fontSize = TimaType.sz5, weight = FontWeight.SemiBold)
        joinLabel?.let { Button(label = it, onClick = onJoin) }
    }
}

/** Приглашение в групповой звонок в личной переписке: что с ним сейчас. */
sealed interface CallInvite {
    val title: String

    data class Checking(override val title: String) : CallInvite
    data class Live(override val title: String) : CallInvite
    data class Ended(override val title: String) : CallInvite
}

/**
 * Приглашение пузырём: «Групповой звонок · Планёрка» и «Присоединиться» — или «Звонок
 * завершён», когда звонка нет или группу уже удалили (решение 10). Слова читающего, а не
 * отправителя: в самом сообщении — только ссылка.
 */
@Composable
fun CallInviteCard(invite: CallInvite, onJoin: () -> Unit, modifier: Modifier = Modifier) {
    val words = Tima.words.groupCall
    Column(
        modifier.background(Tima.colors.functional).padding(TimaSpacing.about3),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Caption("🔊 " + words.inviteLine(invite.title), fontSize = TimaType.sz4, weight = FontWeight.Bold)
        when (invite) {
            is CallInvite.Checking -> Tertiary(words.inviteChecking, lineOne = true)
            is CallInvite.Live -> Button(label = words.join, onClick = onJoin)
            is CallInvite.Ended -> Tertiary(words.inviteEnded, lineOne = true)
        }
    }
}

/** Ссылка приглашения в тексте сообщения: `tima://call/<groupId>`. */
object CallInviteLink {
    private const val PREFIX = "tima://call/"

    fun of(groupId: String): String = PREFIX + groupId

    /** Группа приглашения; `null` — сообщение не приглашение. */
    fun groupOf(text: String?): String? {
        val t = text?.trim() ?: return null
        if (!t.startsWith(PREFIX)) return null
        val id = t.removePrefix(PREFIX)
        return id.takeIf { it.length in 8..64 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
    }
}
