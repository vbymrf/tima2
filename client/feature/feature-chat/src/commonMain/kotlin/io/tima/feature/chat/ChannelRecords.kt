package io.tima.feature.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.Emotion
import io.tima.domain.chat.Reactions
import io.tima.domain.chat.RichBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Лента канала без пузыря (ПЛАН-(СЦ) §1.18, СЦ37–СЦ38; образец — doc/Layout-UI-light/пробы-канал/вариант-лента.html и
 * вариант-статья.html, «делаем по нему», заказчик 2026-11-25).
 *
 * «Лента» — записи подряд во всю ширину: фото от края до края, над записью черта со временем (у сегодняшних — только
 * время), над первой непрочитанной — «Новые записи». Под записью — плашка откликов из двух областей (эмоции → «Оценка»,
 * комментарии → комментарии) и после неё палец вверх и вниз контуром. Меню записи — длительным нажатием.
 * «Статья» — одна запись на экран, листается целиком; длинная читается прокруткой до конца. Под лентой постоянная
 * панель: плашка и пальцы записи на экране; «⋯», «назад», предыдущая, следующая, число новых, «вниз».
 */

/** Метки ленты канала — для сценариев (СЦЕНАРИИ-ПО-ОКНАМ.md). */
object RecordTags {
    const val RECORD = "channel:record"
    const val TIME = "channel:record.time"
    const val NEW = "channel:record.new"
    const val EMOTIONS = "channel:record.emotions"
    const val COMMENTS = "channel:record.comments"
    const val UP = "channel:record.up"
    const val DOWN = "channel:record.down"
    const val ARTICLE = "channel:article"
    const val PANEL = "channel:article.panel"
    const val MORE = "channel:article.more"
    const val BACK = "channel:article.back"
    const val PREV = "channel:article.prev"
    const val NEXT = "channel:article.next"
    const val END = "channel:article.end"
    const val FRESH = "channel:article.fresh"
    const val RATE = "channel:rate"
    const val RATE_UNSET = "channel:rate.unset"
    const val MODE = "channel:mode"
    fun emotion(key: String) = "channel:rate.$key"
}

/**
 * Что ленте канала знать о записях кроме самих строк. `null` в [LocalRecordLook] — обычная переписка пузырями.
 *
 * Функции, а не готовые списки: эмоции и пальцы меняются нажатием сразу, без перезагрузки ленты.
 */
class RecordLook(
    /** Эмоции записи — счёт и своя. */
    val reactions: (ChatLine) -> Reactions,
    /** Свой палец: 1, −1, 0. */
    val vote: (ChatLine) -> Int,
    /** Пальцы показываются: подписчику — да, модератору и выше — нет (свой канал себе не подбирают). */
    val voting: Boolean,
    /** Число комментариев и закрыто ли обсуждение. */
    val comments: (ChatLine) -> Pair<Int, Boolean>,
    val onEmotions: (ChatLine) -> Unit,
    val onComments: (ChatLine) -> Unit,
    val onVote: (ChatLine, Int) -> Unit,
    /** Длительное нажатие в «Ленте», «⋯» в «Статье» — меню записи. */
    val onMenu: (ChatLine) -> Unit,
    /** «Статья» — одна запись на экран. */
    val article: Boolean,
)

val LocalRecordLook = staticCompositionLocalOf<RecordLook?> { null }

/** Фото и видео — от края до края экрана (В2): насколько выйти за поля записи. 0 — не выходить (пузырь). */
internal val LocalMediaBleed = staticCompositionLocalOf { 0.dp }

/** Выйти за поля на [by] с каждой стороны: кадр во всю ширину экрана, текст остаётся в полях. */
internal fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

/** Время на черте записи: сегодня — только время, раньше — день и время («Вчера, 18:02»). */
internal fun recordWhen(atMs: Long, words: io.tima.core.words.ChatWords, nowMs: Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()): String =
    if (sameDay(atMs, nowMs)) time(atMs) else feedDay(atMs, words, nowMs) + ", " + time(atMs)

// ── Значки: контуры те же, что в макете (lucide), рисуются линией цветом темы ──────────────────

/** Значок — контуры пути (24×24), линией. */
class RecordGlyph(vararg val paths: String)

internal object RecordIcons {
    val up = RecordGlyph(
        "M7 10v12",
        "M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z",
    )
    val down = RecordGlyph(
        "M17 14V2",
        "M9 18.12 10 14H4.17a2 2 0 0 1-1.92-2.56l2.33-8A2 2 0 0 1 6.5 2H20a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.76a2 2 0 0 0-1.79 1.11L12 22a3.13 3.13 0 0 1-3-3.88Z",
    )
    val back = RecordGlyph("M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    val prev = RecordGlyph("m18 15-6-6-6 6")
    val next = RecordGlyph("m6 9 6 6 6-6")
    val end = RecordGlyph("M12 3v13", "m6 10 6 6 6-6", "M5 21h14")
    val more = RecordGlyph("M5 12h.01", "M12 12h.01", "M19 12h.01")
    val feed = RecordGlyph(
        "M5.5 3h13a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 8.5v-4A1.5 1.5 0 0 1 5.5 3Z",
        "M5.5 14h13a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19.5v-4A1.5 1.5 0 0 1 5.5 14Z",
    )
    val article = RecordGlyph(
        "M6.5 2.5h11A1.5 1.5 0 0 1 19 4v16a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 20V4a1.5 1.5 0 0 1 1.5-1.5Z",
        "M8.5 7h7", "M8.5 11h7", "M8.5 15h4.5",
    )
}

/** Значок линией цветом темы [color]: вектор строится этим цветом, а не перекрашивается. */
@Composable
fun RecordIcon(glyph: RecordGlyph, color: androidx.compose.ui.graphics.Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val vector = remember(glyph, color) {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in glyph.paths) {
            b.addPath(
                pathData = addPathNodes(d),
                stroke = SolidColor(color),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        b.build()
    }
    Image(painter = rememberVectorPainter(vector), contentDescription = null, modifier = modifier.size(size))
}

/** Значок режима, в который переключит нажатие (3а#): из «Ленты» — «Статья», из «Статьи» — «Лента». */
fun modeIcon(toArticle: Boolean): RecordGlyph = if (toArticle) RecordIcons.article else RecordIcons.feed

// ── Запись «Ленты» ───────────────────────────────────────────────────────────────────────

/**
 * Запись канала во всю ширину (В2, Р3, О-А): черта со временем, тело — фото от края до края, текст в полях; плашка
 * откликов и пальцы. Длительное нажатие — меню записи; идёт выбор — нажатие ставит и снимает отметку.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RecordItem(line: ChatLine, look: RecordLook, isNew: Boolean) {
    val colors = Tima.colors
    val selection = LocalChatSelection.current
    val choosing = selection?.active == true
    val chosen = selection?.keys?.contains(line.dedupKey) == true
    val quiet = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth()
            .background(if (chosen) colors.navigation.copy(alpha = 0.08f) else colors.surface)
            .combinedClickable(
                interactionSource = quiet,
                indication = null,
                onClick = { if (choosing && selectable(line)) selection!!.toggle(line) },
                onLongClick = { if (!choosing) look.onMenu(line) },
            )
            .testTag(RecordTags.RECORD),
    ) {
        RecordTimeLine(line.atMs, isNew, mark = if (choosing && selectable(line)) chosen else null) { selection?.toggle(line) }
        Column(Modifier.fillMaxWidth().padding(start = TimaSpacing.about4, end = TimaSpacing.about4, top = 4.dp, bottom = 14.dp)) {
            RecordBody(line)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (count, closed) = look.comments(line)
                ReactionPlaque(
                    reactions = look.reactions(line),
                    comments = count,
                    closed = closed,
                    grow = true,
                    onEmotions = { if (choosing) selection?.toggle(line) else look.onEmotions(line) },
                    onComments = { if (choosing) selection?.toggle(line) else look.onComments(line) },
                    modifier = Modifier.weight(1f),
                )
                if (look.voting) Thumbs(look.vote(line)) { v -> look.onVote(line, v) }
            }
        }
    }
}

/** Тело записи: оформленное — тем же рисованием, что сообщение, с кадрами от края до края; иначе текст. */
@Composable
internal fun RecordBody(line: ChatLine) {
    val rich = line.rich
    androidx.compose.runtime.CompositionLocalProvider(LocalMediaBleed provides TimaSpacing.about4) {
        if (rich != null) RichBody(rich, Modifier.fillMaxWidth())
        else Caption(line.text.orEmpty(), fontSize = TimaType.sz4)
    }
}

/**
 * Черта со временем над записью (Р3, «только время, тесно»): у сегодняшних — время, у вчерашних и старше — с днём.
 * Над первой непрочитанной — та же черта жёлтая, «Новые записи · время». Идёт выбор — справа отметка.
 */
@Composable
internal fun RecordTimeLine(atMs: Long, isNew: Boolean, mark: Boolean?, onMark: () -> Unit) {
    val colors = Tima.colors
    val words = Tima.words
    val rule = if (isNew) colors.activity else colors.line
    val thick = if (isNew) 1.5.dp else 1.dp
    Row(
        Modifier.fillMaxWidth().padding(start = TimaSpacing.about4, end = TimaSpacing.about3, top = 6.dp)
            .testTag(if (isNew) RecordTags.NEW else RecordTags.TIME),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(thick).background(rule))
        Caption(
            if (isNew) words.socium.newRecords + " · " + recordWhen(atMs, words.chat) else recordWhen(atMs, words.chat),
            fontSize = TimaType.sz6,
            weight = FontWeight.Bold,
            color = if (isNew) colors.text2 else colors.text3,
            lineOne = true,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Box(Modifier.weight(1f).height(thick).background(rule))
        if (mark != null) SelectMark(mark, onMark)
    }
}

// ── Плашка откликов и пальцы ─────────────────────────────────────────────────────────────

/**
 * Плашка откликов (§1.18, 8–9): две области через разделитель — эмоции и комментарии; вида не меняет: эмоций нет —
 * «😊 0», комментариев нет — «💬 0»; обсуждение закрыто — значок приглушён. Моя эмоция — залита чёрным (1а#).
 * [grow] — эмоции переносятся (в «Ленте»); иначе одной строкой, лишние обрезаны («Статья»).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReactionPlaque(
    reactions: Reactions,
    comments: Int,
    closed: Boolean,
    grow: Boolean,
    onEmotions: () -> Unit,
    onComments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Tima.colors
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.clip(shape).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).background(colors.text.copy(alpha = 0.06f)).clickable(onClick = onEmotions)
                .heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 7.dp).testTag(RecordTags.EMOTIONS),
            contentAlignment = Alignment.CenterStart,
        ) {
            val shown = reactions.counts.mapNotNull { c -> Emotion.of(c.emotion)?.let { it to c.count } }
            val chip: @Composable (String, Int, Boolean) -> Unit = { sign, n, mine ->
                Caption(
                    "$sign $n",
                    fontSize = TimaType.sz5,
                    weight = FontWeight.Bold,
                    color = if (mine) colors.surface else colors.text,
                    lineOne = true,
                    modifier = if (mine) Modifier.background(colors.text, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier,
                )
            }
            if (shown.isEmpty()) {
                chip("😊", 0, false)
            } else if (grow) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((e, n) in shown) chip(e.sign, n, e.key == reactions.mine)
                }
            } else {
                Row(Modifier.clip(RoundedCornerShape(0.dp)), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((e, n) in shown) chip(e.sign, n, e.key == reactions.mine)
                }
            }
        }
        Box(Modifier.width(1.dp).heightIn(min = 40.dp).background(colors.surface))
        Box(
            Modifier.background(colors.text.copy(alpha = 0.06f)).clickable(onClick = onComments)
                .heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 7.dp).testTag(RecordTags.COMMENTS)
                .semantics { if (closed) contentDescription = "closed" },
            contentAlignment = Alignment.Center,
        ) {
            Caption("💬 $comments", fontSize = TimaType.sz5, weight = FontWeight.Bold, color = if (closed) colors.text3 else colors.text, lineOne = true,
                modifier = Modifier.alpha(if (closed) 0.5f else 1f))
        }
    }
}

/** Палец вверх и палец вниз — контуром (4а); выбранный залит чёрным: чёрный — «я». Нажатие по выбранному снимает. */
@Composable
internal fun Thumbs(vote: Int, onVote: (Int) -> Unit) {
    val colors = Tima.colors
    val words = Tima.words.socium
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((value, icon, tag, label) in listOf(
            Quad(1, RecordIcons.up, RecordTags.UP, words.voteUp),
            Quad(-1, RecordIcons.down, RecordTags.DOWN, words.voteDown),
        )) {
            val on = vote == value
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .background(if (on) colors.text else colors.surface)
                    .border(1.5.dp, if (on) colors.text else colors.line, CircleShape)
                    .clickable { onVote(if (on) 0 else value) }
                    .semantics { contentDescription = label }
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) { RecordIcon(icon, if (on) colors.surface else colors.text2, size = 18.dp) }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

// ── «Оценка» ─────────────────────────────────────────────────────────────────────────────

/**
 * Окно «Оценка» (подокно `оценка.html`, §1.18, 9): восемь эмоций четырьмя осями, «Спокойствие» посередине; выбрать
 * можно одну, нажатие по выбранной или «Снять» — снять. Ниже «Ваш выбор» — все эмоции, что уже поставлены.
 */
@Composable
fun EmotionSheet(reactions: Reactions, onPick: (String?) -> Unit, onClose: () -> Unit) {
    val colors = Tima.colors
    val words = Tima.words.socium
    // Выбор — один раз: окно закрывается, плашка под записью уже показывает новое (правило листов, 2026-10-24).
    val once = io.tima.core.ui.rememberOnePress(RecordTags.RATE)
    val pick: (String?) -> Unit = { e -> once.press { onPick(e); onClose() } }
    ProvidePlace(TextPlace.MENU) {
        Box(
            Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.45f)).clickable(onClick = onClose),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier.fillMaxWidth().background(colors.surface).clickable(enabled = false) {}
                    .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3).testTag(RecordTags.RATE),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Caption(words.rateTitle, fontSize = TimaType.sz5, weight = FontWeight.ExtraBold)
                        io.tima.core.ui.Tertiary(words.rateHint)
                    }
                    io.tima.core.ui.IconButton(glyph = "✕", onClick = onClose)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    io.tima.core.ui.Tertiary(words.rateAxis, Modifier.weight(1f))
                    io.tima.core.ui.Tertiary("+1", Modifier.width(118.dp))
                    io.tima.core.ui.Tertiary("−1", Modifier.width(118.dp))
                }
                for (axis in 0 until Emotion.AXES) {
                    val pair = Emotion.entries.filter { it.axis == axis }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Caption(words.emotionAxis(axis), fontSize = TimaType.sz5, color = colors.text2, modifier = Modifier.weight(1f), lineOne = true)
                        if (pair.size == 1) {
                            Box(Modifier.width(236.dp), contentAlignment = Alignment.Center) { EmotionChip(pair[0], reactions.mine, pick) }
                        } else {
                            EmotionChip(pair.first { it.positive == true }, reactions.mine, pick)
                            Spacer(Modifier.width(8.dp))
                            EmotionChip(pair.first { it.positive == false }, reactions.mine, pick)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    val mine = Emotion.of(reactions.mine)
                    Caption(
                        words.rateMine + ": " + (mine?.let { it.sign + " " + words.emotion(it.key) } ?: "—"),
                        fontSize = TimaType.sz5, color = colors.text2, modifier = Modifier.weight(1f),
                    )
                    io.tima.core.ui.Button(words.rateUnset, onClick = { pick(null) }, kind = io.tima.core.ui.ButtonKind.Quiet,
                        modifier = Modifier.testTag(RecordTags.RATE_UNSET))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                io.tima.core.ui.Tertiary(words.rateGiven)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val given = reactions.counts.mapNotNull { c -> Emotion.of(c.emotion)?.let { it to c.count } }
                    if (given.isEmpty()) Caption("😊 0", fontSize = TimaType.sz5, weight = FontWeight.Bold)
                    for ((e, n) in given) {
                        val mine = e.key == reactions.mine
                        Caption(
                            "${e.sign} $n", fontSize = TimaType.sz5, weight = FontWeight.Bold, lineOne = true,
                            color = if (mine) colors.surface else colors.text,
                            modifier = if (mine) Modifier.background(colors.text, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun EmotionChip(e: Emotion, mine: String?, onPick: (String?) -> Unit) {
    val colors = Tima.colors
    val on = e.key == mine
    Box(
        Modifier.width(114.dp).clip(CircleShape)
            .background(if (on) colors.text else colors.surface)
            .border(1.dp, if (on) colors.text else colors.line, CircleShape)
            .clickable { onPick(if (on) null else e.key) }
            .padding(vertical = 6.dp)
            .testTag(RecordTags.emotion(e.key)),
        contentAlignment = Alignment.Center,
    ) {
        Caption(e.sign + " " + Tima.words.socium.emotion(e.key), fontSize = TimaType.sz6, weight = FontWeight.Bold,
            color = if (on) colors.surface else colors.text, lineOne = true)
    }
}

// ── «Статья» ─────────────────────────────────────────────────────────────────────────────

/**
 * «Статья» (В3 + О-Г): одна запись на экран. Листается целиком; запись длиннее экрана читается прокруткой до конца,
 * и только потом лента переходит к следующей. Под лентой — постоянная панель: плашка и пальцы записи на экране,
 * салатовая черта прочитанного у длинной; ниже — «⋯», «назад», предыдущая, следующая, число новых, «вниз».
 * [lines] — новым сверху, как у переписки; страницы идут от старой к новой.
 */
@Composable
internal fun ArticleFeed(lines: List<ChatLine>, look: RecordLook, firstUnread: Long, modifier: Modifier = Modifier) {
    val colors = Tima.colors
    val pages = remember(lines) { lines.asReversed() }
    if (pages.isEmpty()) {
        Box(modifier.fillMaxWidth())
        return
    }
    val start = remember(lines.isEmpty()) {
        pages.indexOfFirst { firstUnread > 0 && it.serverId == firstUnread }.takeIf { it >= 0 } ?: pages.lastIndex
    }
    val pager = rememberPagerState(initialPage = start) { pages.size }
    val scope = rememberCoroutineScope()
    val back = remember { mutableStateListOf<Int>() }
    var readPart by remember { mutableStateOf(-1f) }
    val selection = LocalChatSelection.current
    val choosing = selection?.active == true
    Column(modifier.fillMaxWidth().testTag(RecordTags.ARTICLE)) {
        VerticalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { pages.getOrNull(it)?.dedupKey ?: it }) { index ->
            val line = pages[index]
            val scroll = rememberScrollState()
            if (index == pager.currentPage) {
                LaunchedEffect(scroll.value, scroll.maxValue) {
                    readPart = if (scroll.maxValue <= 0) -1f else scroll.value.toFloat() / scroll.maxValue
                }
            }
            val chosen = selection?.keys?.contains(line.dedupKey) == true
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .background(if (chosen) colors.navigation.copy(alpha = 0.08f) else colors.surface)
                    .clickable(enabled = choosing, onClick = { selection?.toggle(line) }),
            ) {
                val full = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Column(Modifier.fillMaxWidth().heightIn(min = full).padding(horizontal = TimaSpacing.about4, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Caption(recordWhen(line.atMs, Tima.words.chat), fontSize = TimaType.sz6, weight = FontWeight.Bold,
                                color = colors.text3, modifier = Modifier.weight(1f).testTag(RecordTags.TIME))
                            if (choosing && selectable(line)) SelectMark(chosen) { selection?.toggle(line) }
                        }
                        Spacer(Modifier.height(4.dp))
                        // Короткая запись — посередине экрана, а не строкой у верха пустого листа.
                        val short = line.rich?.blocks?.none { it is RichBlock.Album || it is RichBlock.Image } != false &&
                            (line.text?.length ?: 0) < SHORT_CHARS
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = full - 60.dp),
                            contentAlignment = if (short) Alignment.CenterStart else Alignment.TopStart,
                        ) {
                            RecordBody(line)
                        }
                    }
                }
            }
        }
        val current = pages.getOrNull(pager.currentPage) ?: pages.last()
        ArticlePanel(
            line = current,
            look = look,
            readPart = readPart,
            choosing = choosing,
            atFirst = pager.currentPage == 0,
            atLast = pager.currentPage == pages.lastIndex,
            fresh = if (firstUnread <= 0) 0 else pages.drop(pager.currentPage + 1).count { it.serverId >= firstUnread },
            canBack = back.isNotEmpty(),
            onPrev = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } },
            onNext = { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(pages.lastIndex)) } },
            onEnd = { back.add(pager.currentPage); scope.launch { pager.animateScrollToPage(pages.lastIndex) } },
            onBack = { if (back.isNotEmpty()) scope.launch { pager.animateScrollToPage(back.removeAt(back.lastIndex)) } },
        )
    }
}

/** Знаков у записи, которую «Статья» ставит посередине экрана. */
private const val SHORT_CHARS = 280

@Composable
private fun ArticlePanel(
    line: ChatLine,
    look: RecordLook,
    readPart: Float,
    choosing: Boolean,
    atFirst: Boolean,
    atLast: Boolean,
    fresh: Int,
    canBack: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onEnd: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = Tima.colors
    val words = Tima.words.socium
    Column(Modifier.fillMaxWidth().background(colors.surface).testTag(RecordTags.PANEL)) {
        // Черта прочитанного — только у записи длиннее экрана (из О-Г).
        Box(Modifier.fillMaxWidth().height(3.dp).background(colors.line)) {
            if (readPart >= 0f) Box(Modifier.fillMaxWidth(readPart.coerceIn(0.02f, 1f)).fillMaxHeight().background(colors.navigation))
        }
        if (!choosing) {
            Row(
                Modifier.fillMaxWidth().padding(start = TimaSpacing.about4, end = TimaSpacing.about3, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val (count, closed) = look.comments(line)
                ReactionPlaque(look.reactions(line), count, closed, grow = false,
                    onEmotions = { look.onEmotions(line) }, onComments = { look.onComments(line) }, modifier = Modifier.weight(1f))
                if (look.voting) Thumbs(look.vote(line)) { v -> look.onVote(line, v) }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(colors.functional).padding(horizontal = TimaSpacing.about3, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PanelButton(RecordIcons.more, words.recordActions, RecordTags.MORE) { look.onMenu(line) }
            // «Назад» появляется и исчезает, как в переписке; место держится — кнопки не прыгают.
            Box(Modifier.alpha(if (canBack) 1f else 0f)) {
                PanelButton(RecordIcons.back, words.recordBack, RecordTags.BACK, enabled = canBack, onClick = onBack)
            }
            Spacer(Modifier.weight(1f))
            PanelButton(RecordIcons.prev, words.recordPrev, RecordTags.PREV, enabled = !atFirst, onClick = onPrev)
            PanelButton(RecordIcons.next, words.recordNext, RecordTags.NEXT, enabled = !atLast, onClick = onNext)
            Spacer(Modifier.weight(1f))
            Row(Modifier.alpha(if (atLast) 0f else 1f), verticalAlignment = Alignment.CenterVertically) {
                if (fresh > 0) {
                    Caption(
                        "$fresh", fontSize = TimaType.sz6, weight = FontWeight.ExtraBold, lineOne = true,
                        modifier = Modifier.background(colors.activity, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp).testTag(RecordTags.FRESH),
                    )
                    Caption(" " + words.fresh, fontSize = TimaType.sz6, weight = FontWeight.ExtraBold, lineOne = true)
                    Spacer(Modifier.width(6.dp))
                }
                PanelButton(RecordIcons.end, words.recordDown, RecordTags.END, enabled = !atLast, onClick = onEnd)
            }
        }
    }
}

@Composable
private fun PanelButton(icon: RecordGlyph, label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.size(38.dp).clip(CircleShape)
            .background(if (enabled) colors.text.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { RecordIcon(icon, if (enabled) colors.text else colors.text3) }
}

/** Кнопки ленты в «Ленте» бледнеют через 2 секунды без прокрутки (К3) и возвращаются при прокрутке. */
@Composable
internal fun rememberCalmAlpha(scrolling: Boolean): Float {
    var calm by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) {
        if (scrolling) calm = false else {
            delay(CALM_AFTER_MS)
            calm = true
        }
    }
    val alpha by animateFloatAsState(if (calm) CALM_ALPHA else 1f)
    return alpha
}

private const val CALM_AFTER_MS = 2_000L
private const val CALM_ALPHA = 0.3f

/**
 * Сообщение «Описания» над его комментариями (ПЛАН-(СЦ) §1.18, 11): целиком, тем же рисованием, что в переписке; под ним
 * плашка откликов — эмоции «Оценкой» и число комментариев; у канала — ещё пальцы ([vote] `null` — пальцев нет).
 */
@Composable
fun ShowcaseMessageTop(
    rich: io.tima.domain.chat.RichContent?,
    text: String?,
    reactions: Reactions,
    comments: Int,
    onEmotions: () -> Unit,
    vote: Int?,
    onVote: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3)) {
        ProvidePlace(TextPlace.MESSAGES) {
            if (rich != null) RichBody(rich, Modifier.fillMaxWidth()) else Caption(text.orEmpty(), fontSize = TimaType.sz4)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReactionPlaque(reactions, comments, closed = false, grow = true, onEmotions = onEmotions, onComments = {}, modifier = Modifier.weight(1f))
            if (vote != null) Thumbs(vote, onVote)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tima.colors.line))
    }
}
