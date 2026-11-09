package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import io.tima.core.ui.IconButton
import io.tima.core.ui.authorStrip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.ui.MarkKind
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonCircle
import io.tima.core.ui.Mark
import io.tima.core.ui.Field
import io.tima.core.ui.Caption
import io.tima.core.ui.Bubble
import io.tima.core.ui.Side
import io.tima.core.ui.Arrow
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.TimaZones
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.core.ui.Tertiary
import io.tima.core.ui.SubwindowHeader
import io.tima.core.ui.Chip
import io.tima.domain.chat.PersonField
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.RichContent
import kotlinx.coroutines.launch
import io.tima.domain.chat.letter
import io.tima.domain.chat.line
import io.tima.domain.chat.CarryToPage
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.KIND_SYSTEM
import io.tima.domain.chat.MessageDisplay

/*
 * Лента переписки — реплики, переходы, подгрузка старых, дни и черта «Новые сообщения»; лента указателей.
 * Вынесена из ChatScreen.kt (ПЛАН-(РФ) РФ5) без изменения поведения.
 */

/**
 * Лента реплик.
 *
 * `reverseLayout`: список приходит **новым сверху** — так его отдаёт запрос к базе, — а на
 * экране новое внизу, и открывается переписка на последнем сообщении. Переворачивать
 * список в коде значило бы делать в памяти то, что уже сделано в SQL, и заодно потерять
 * бесплатное «прокрутка начинается снизу».
 */
@Composable
internal fun Feed(
    /** Переходы внутри переписки (§11.4); `null` — их нет (ветка). */
    jumps: FeedJumps? = null,
    lines: List<ChatLine>,
    authorName: (ChatLine) -> String?,
    authorLetter: (ChatLine) -> String = { "+" },
    authorFace: (ChatLine) -> ImageBitmap? = { null },
    ownerId: String? = null,
    hues: Map<String, Int> = emptyMap(),
    stripes: Boolean = false,
    modifier: Modifier = Modifier,
    /** Показывать ли метку круга у реплик. По умолчанию нет — см. [ChatState.showCircles]. */
    showCircles: Boolean = false,
    onNarrow: ((Long, Int, Int) -> Unit)? = null,
    onCarry: ((Long, Int) -> Unit)? = null,
    onAskChatKeys: (() -> Unit)? = null,
    /** Где аватар в группе (пробы «вид группы», 2026-10-07): «Вид» в «Социуме». */
    avatarLook: io.tima.core.ui.AvatarLook = io.tima.core.ui.AvatarLook.Free,
    /** Нажатие на аватар автора — его страница, во всех видах. `null` — не нажимается. */
    onAuthor: ((String) -> Unit)? = null,
    /** Номера сообщений, к которым ключа не осталось ни у кого: «запросить» у них нет. */
    lostKeys: Set<Long> = emptySet(),
    onThread: ((Long) -> Unit)? = null,
    onFailed: ((ChatLine) -> Unit)? = null,
    /** Нажатие на отправленное — меню сообщения (Решения §11.1). `null` — меню нет. */
    onMenu: ((ChatLine) -> Unit)? = null,
    /** Долгое нажатие — выделить часть и скопировать (Решения §11.1). `null` — нет. */
    onSelect: ((ChatLine) -> Unit)? = null,
    invite: (ChatLine) -> CallInvite? = { null },
    onInvite: (ChatLine) -> Unit = {},
    /** По содержимому, а не во всю высоту — сообщение подокном (журнал указателей). */
    wrap: Boolean = false,
    /** Дошли до верха — ещё старых; `null` — лента не растёт. */
    onOlder: (() -> Unit)? = null,
    /** Разделитель дней между сообщениями (заказчик 2026-11-09); в подокне одного сообщения — нет. */
    days: Boolean = jumps != null,
    /** Первое непрочитанное при открытии — над ним черта «Новые сообщения» (ПЛАН-(МП) М2); 0 — нет. */
    firstUnread: Long = 0,
    /** Выход из ленты — номер сообщения у верхнего края, 0 — внизу (ПЛАН-(МП) М2). */
    onLeave: ((Long) -> Unit)? = null,
) =
    // Сообщения — своя группа размера (ПЛАН-(Ш)-ШРИФТОВ Ш3): её укрупняют чаще всего, и
    // от списка чатов она не зависит.
    ProvidePlace(TextPlace.MESSAGES) {
    // ── К НОВОМУ СООБЩЕНИЮ (заказчик 2026-10-09: «прокручиваем») ──────────────
    //
    // Список держит позицию по ключу строки: новое сообщение встаёт на место 0, а на экране
    // остаётся прежнее — новое оказывается за нижним краем. Особенно при открытой клавиатуре:
    // отправил — и не видишь отправленного (realme, PH-47 навыка телефонов).
    //
    // Своё — к нему всегда: его только что написали. Входящее — только если человек и так был
    // внизу (на месте 0 или 1 — после вставки прежнее нижнее съезжает на 1): читающего историю
    // новое сообщение сдёргивать не должно.
    val listState = rememberLazyListState()
    val newest = lines.firstOrNull()
    LaunchedEffect(newest?.dedupKey) {
        if (newest != null && (newest.outgoing || listState.firstVisibleItemIndex <= 1)) {
            listState.animateScrollToItem(0)
        }
    }
    // Просьба перейти — от блока ответа и вставленного; ведёт лента: прокрутка у неё.
    // Прокрутка — своей задачей, как у «назад» и «вперёд»: `taken()` меняет ключ эффекта, и прокрутка
    // внутри него отменялась на следующем кадре — переход писался в журнал, но не случался (2026-10-20).
    if (jumps != null) {
        val jumpScope = androidx.compose.runtime.rememberCoroutineScope()
        val feed by androidx.compose.runtime.rememberUpdatedState(lines)
        LaunchedEffect(jumps.request) {
            val target = jumps.request ?: return@LaunchedEffect
            jumps.taken()
            jumpScope.launch { jumps.performReaching(target, { feed }, listState, record = jumps.record, flash = !jumps.quiet) }
        }
    }
    // Дошли до верха ленты — ещё порция старых (заказчик 2026-11-09). Лента перевёрнута: верх — конец списка.
    // Где остановились — при выходе (ПЛАН-(МП) М2): верхнее видимое сообщение; у самого низа — 0, откроется внизу.
    if (onLeave != null) {
        val leave by androidx.compose.runtime.rememberUpdatedState(onLeave)
        val shown by androidx.compose.runtime.rememberUpdatedState(lines)
        androidx.compose.runtime.DisposableEffect(listState) {
            onDispose {
                val top = listState.layoutInfo.visibleItemsInfo.maxByOrNull { it.index }?.index
                val at = if (listState.firstVisibleItemIndex <= 1 || top == null) 0L else shown.getOrNull(top)?.serverId ?: 0L
                leave(at)
            }
        }
    }
    if (onOlder != null) {
        val older by androidx.compose.runtime.rememberUpdatedState(onOlder)
        LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow {
                val info = listState.layoutInfo
                info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - OLDER_AHEAD
            }.collect { near -> if (near) older() }
        }
    }
    if (jumps != null) WatchLeaving(jumps, lines, listState)
    // Нажатие на ссылку внутри сообщения — оно и есть «откуда» (ПЛАН-(БП)).
    val openOuter = LocalOpenMessage.current
    val flashColor = Tima.colors.activity.copy(alpha = 0.22f)
    // Список в сообщении раскрыли или свернули — лента сдвигается, строка пункта стоит на месте (2026-10-21).
    val nudge: (Float) -> Unit = remember(listState) { { d -> listState.dispatchRawDelta(d) } }
    Box(modifier.fillMaxWidth()) {
    androidx.compose.runtime.CompositionLocalProvider(LocalFeedNudge provides nudge) {
    LazyColumn(
        modifier = if (wrap) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(
            horizontal = TimaSpacing.about3,
            vertical = TimaSpacing.about4,
        ),
        // Зазор между репликами — вдвое меньше прежнего (заказчик 2026-10-07), везде; над репликой
        // с аватаром — прежний: аватар выступает в него вверх (см. отступ у реплики ниже).
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3 / 2, Alignment.Bottom),
    ) {
        // Порядок появления авторов — по первому сообщению в переписке (заказчик 2026-09-19):
        // список идёт новым сверху, поэтому считается с конца. Свои и владелец в счёт не идут —
        // у своих полосы нет, у владельца она салатовая. На двух устройствах одного аккаунта
        // порядок совпадает: сообщения выстроены по времени написания.
        // Выбравшие цвет сами в порядок не входят: у них номер свой, а автомат обходит их
        // номера (`taken`), чтобы не подсадить новичка на чужой цвет.
        val order: Map<String, Int> = if (stripes) {
            lines.asReversed().asSequence()
                .filter { !it.outgoing && it.senderId != null && it.senderId != ownerId && it.senderId !in hues }
                .map { it.senderId!! }.distinct()
                .withIndex().associate { (at, who) -> who to at }
        } else {
            emptyMap()
        }
        val taken = hues.values.toSet()
        itemsIndexed(lines, key = { _, it -> it.dedupKey }) { index, line ->
            Column(Modifier.fillMaxWidth()) {
            // Предыдущее по времени лежит НИЖЕ в списке: список идёт новым сверху.
            val previous = lines.getOrNull(index + 1)
            // Первое сообщение дня — над ним день (заказчик 2026-11-09): «Сегодня», «Вчера», «29 октября».
            if (days && (previous == null || !sameDay(previous.atMs, line.atMs))) DayDivider(line.atMs)
            if (firstUnread > 0 && line.serverId == firstUnread) NewMessagesDivider()
            // Приглашение в групповой звонок — карточкой словами читающего (решение 3а).
            val called = invite(line)
            if (called != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (line.outgoing) Arrangement.End else Arrangement.Start,
                ) { CallInviteCard(called, onJoin = { onInvite(line) }) }
                return@itemsIndexed
            }
            // Переход сюда — коротко подсвечено (В34).
            Box(if (jumps != null && line.serverId > 0 && jumps.highlight == line.serverId) Modifier.background(flashColor, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) else Modifier) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalOpenMessage provides { kind, chat, number ->
                    if (jumps != null && line.serverId > 0) jumps.from = line.serverId
                    openOuter(kind, chat, number)
                },
                // Где стоит звук: к этому сообщению ведёт полоса «сейчас играет» (2026-10-24).
                LocalMessagePlace provides line.serverId.takeIf { it > 0 }?.let { MessagePlace(line.chatId, it) },
            ) {
            Reply(
                line = line,
                author = authorName(line),
                letter = authorLetter(line),
                face = authorFace(line),
                strip = if (stripes) authorStrip(order[line.senderId], owner = line.senderId == ownerId, hue = hues[line.senderId], taken = taken) else null,
                // Смена автора разрывает цепочку, даже когда обе реплики чужие: иначе в
                // группе два человека подряд слились бы в одного, и имя второго не
                // показалось бы вовсе.
                continuation = previous?.outgoing == line.outgoing &&
                    previous?.senderId == line.senderId,
                showCircle = showCircles,
                onNarrow = onNarrow,
                onCarry = onCarry,
                onAskChatKeys = onAskChatKeys,
                lostKeys = lostKeys,
                onThread = onThread,
                avatarLook = avatarLook,
                onAuthor = onAuthor,
                onFailed = onFailed,
                onMenu = onMenu,
                onSelect = onSelect,
            )
            }
            }
            }
        }
    }
    }
    if (jumps != null) JumpButtons(jumps, lines, listState, Modifier.align(Alignment.BottomEnd).padding(TimaSpacing.about3))
    }
    }

/**
 * Указатели сущности — сами сообщения, пузырём ленты (ПЛАН-(УК) УК3): нажали по сообщению — переход;
 * нажали рядом или долго — выделение ([chosen] — ключи строк).
 */
@Composable
internal fun PointerFeed(
    lines: List<ChatLine>,
    authorOf: (ChatLine) -> String?,
    chosen: Set<String>,
    onOpen: (ChatLine) -> Unit,
    onToggle: (ChatLine) -> Unit,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalBubbleOpen provides onOpen,
        LocalChatSelection provides ChatSelection(chosen, onToggle),
    ) {
        Feed(
            lines = lines,
            authorName = authorOf,
            authorLetter = { line -> authorOf(line)?.take(1)?.uppercase() ?: "+" },
            modifier = modifier,
            onMenu = onToggle,
            onSelect = onToggle,
            wrap = wrap,
        )
    }
}

/** За сколько строк до верха ленты просить ещё старых. */
internal const val OLDER_AHEAD = 10

/** Черта «Новые сообщения» над первым непрочитанным (ПЛАН-(МП) М2). */
@Composable
internal fun NewMessagesDivider() {
    val colors = Tima.colors
    Row(Modifier.fillMaxWidth().padding(vertical = TimaSpacing.about3 / 2).testTag(NEW_DIVIDER_TAG), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.5.dp).background(colors.activity))
        Caption(Tima.words.chat.feedNew, fontSize = TimaType.sz6, weight = FontWeight.Bold, color = colors.text2, modifier = Modifier.padding(horizontal = 10.dp))
        Box(Modifier.weight(1f).height(1.5.dp).background(colors.activity))
    }
}

/** Метка черты «Новые сообщения» — для сценариев. */
const val NEW_DIVIDER_TAG = "chat:new"

/** Метка разделителя дней — для сценариев. */
const val DAY_DIVIDER_TAG = "chat:day"

/** День над первым сообщением дня — посередине, тихо, как служебная строка. */
@Composable
internal fun DayDivider(atMs: Long) {
    val words = Tima.words.chat
    Box(Modifier.fillMaxWidth().padding(vertical = TimaSpacing.about3 / 2), contentAlignment = Alignment.Center) {
        Caption(
            feedDay(atMs, words),
            fontSize = TimaType.sz6,
            weight = FontWeight.Bold,
            color = Tima.colors.text3,
            lineOne = true,
            modifier = Modifier
                .background(Tima.colors.border.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 3.dp)
                .testTag(DAY_DIVIDER_TAG),
        )
    }
}
