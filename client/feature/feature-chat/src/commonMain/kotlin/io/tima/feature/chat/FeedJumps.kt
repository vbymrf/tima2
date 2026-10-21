package io.tima.feature.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.JumpBuffer
import io.tima.domain.chat.JumpKind
import io.tima.domain.chat.JumpPoint
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Буферы переходов устройства (doc_mig/ПЛАН-(БП)-БУФЕРА-ПЕРЕХОДОВ.md): по ключу — сообщество или
 * переписка вне него, — из настроек устройства; изменение сразу пишется обратно. Один объект на
 * приложение: переписки одного сообщества делят буфер.
 */
@Stable
class JumpBuffers(private val stored: Map<String, String>, private val write: (String, String) -> Unit) {
    private val open = HashMap<String, JumpBuffer>()

    /** Меняется с каждым изменением любого буфера — кнопки перерисовываются. */
    var version by mutableStateOf(0)
        private set

    fun of(key: String): JumpBuffer = open.getOrPut(key) { JumpBuffer.decode(stored[PREFIX + key]) }

    fun save(key: String) {
        version++
        write(PREFIX + key, of(key).encode())
    }

    companion object {
        /** Ключ настроек: `jumps.<буфер>`. */
        const val PREFIX = "jumps."
    }
}

/**
 * Где живёт буфер переписки и куда вести за её пределы (ПЛАН-(БП)): [chatId] — эта переписка, [key] —
 * её буфер. [sameBuffer] — переписка того же буфера (группа того же сообщества): ссылка на неё ведёт
 * туда, а не в окно одного сообщения. [elsewhere] — открыть другую переписку и перейти к сообщению;
 * [pointers] — точка «из Указателей»; [jumpTo] — переписку открыли, чтобы перейти к этому сообщению.
 */
class JumpPlace(
    val buffers: JumpBuffers,
    val chatId: String,
    val key: String,
    val sameBuffer: (String) -> Boolean = { false },
    val elsewhere: (chatId: String, serverId: Long) -> Unit = { _, _ -> },
    val pointers: (() -> Unit)? = null,
    val jumpTo: Long? = null,
)

/**
 * Переходы в переписке (Решения §11.4, ПЛАН-(БП)): по блоку ответа, вставленному, «Продолжить чтение»,
 * закреплённому. Это **местная история прокрутки**, а не «назад» приложения: точки «откуда» и «куда»
 * в буфере устройства ([JumpBuffer]); без [place] — буфер только в памяти (проверки, витрина). Номера —
 * серверные номера сообщений: место в списке меняется с каждым новым.
 */
@Stable
class FeedJumps(private val place: JumpPlace? = null) {
    private val own = JumpBuffer()

    val chatId: String get() = place?.chatId.orEmpty()

    val buffer: JumpBuffer get() = place?.let { it.buffers.of(it.key) } ?: own

    private var tick by mutableStateOf(0)

    /** Куда просили перейти; ведёт лента — у неё прокрутка. */
    var request by mutableStateOf<Long?>(null)
        private set

    /** Писать ли переход в буфер: «назад», «вперёд» и приход из другой переписки его уже сдвинули. */
    var record: Boolean = true
        private set

    /** Сообщение коротко подсвечено после перехода (В34). */
    var highlight by mutableStateOf<Long?>(null)
        private set

    /** Кому сказать о переходе — `(to — к сообщению, step — назад или вперёд; длина цепочки)`; журнал ведёт общий слой. */
    var onJump: ((String, Int) -> Unit)? = null

    /**
     * Сколько пикселей сверху ленты закрыто тем, что лежит **поверх** неё ([coversFeedTop]). Что стоит
     * **над** лентой (шапка, закреплённое, полосы) — вне её области и учитывается само: верх
     * считается по области ленты. Сейчас поверх ленты сверху ничего нет.
     */
    var topCover by mutableStateOf(0)
        internal set

    /** Сообщение, на ссылке которого нажали, — точка «откуда»; ставит лента у каждого сообщения. */
    internal var from: Long? = null

    /** Точку, на которой стоим, видели на экране: ушла с него — ожила (ПЛАН-(БП)). */
    internal var seen = false

    /** Идёт прокрутка перехода — уход точки с экрана в это время не в счёт. */
    internal var moving = false

    val canBack: Boolean get() = tick.let { place?.buffers?.version; buffer.backTarget() != null }

    val canForward: Boolean get() = tick.let { place?.buffers?.version; buffer.forwardTarget() != null }

    fun go(serverId: Long, record: Boolean = true) {
        this.record = record
        request = serverId
    }

    internal fun taken() {
        request = null
    }

    /** Буфер поменялся: перерисовать кнопки и записать на устройство. */
    internal fun changed() {
        tick++
        place?.let { it.buffers.save(it.key) }
    }

    internal suspend fun flash(serverId: Long) {
        highlight = serverId
        delay(FLASH_MS)
        if (highlight == serverId) highlight = null
    }

    /** Долгое нажатие на «назад» — буфер очищен. */
    fun clear() {
        buffer.clear()
        changed()
    }

    /** Ссылка на сообщение другой переписки того же буфера: точки — в буфер, ту переписку — открыть и перейти. */
    fun elsewhere(chat: String, number: Long) {
        buffer.jumped(from?.let { JumpPoint(chatId, it, JumpKind.FROM) }, JumpPoint(chat, number, JumpKind.TO))
        from = null
        changed()
        onJump?.invoke("to", buffer.points.size)
        place?.elsewhere?.invoke(chat, number)
    }

    /** «Назад» или «вперёд» привели к [point]: здесь — прокрутка, в другой переписке — туда, «из Указателей» — во вкладку. */
    internal fun step(point: JumpPoint?, scroll: (Long) -> Unit) {
        point ?: return
        changed()
        when {
            point.kind == JumpKind.POINTERS -> place?.pointers?.invoke()
            point.chatId != chatId && place != null -> place.elsewhere(point.chatId, point.serverId)
            else -> scroll(point.serverId)
        }
    }

    companion object {
        const val FLASH_MS = 1_600L
    }
}

/** Метки кнопок переходов (СЦЕНАРИИ-ПО-ОКНАМ). */
const val JUMP_BACK_TAG = "chat:jump.back"
const val JUMP_FORWARD_TAG = "chat:jump.forward"
const val DOWN_TAG = "chat:down"

/**
 * Номер сообщения у края экрана сейчас — «откуда», когда сообщение со ссылкой неизвестно (закреплённое).
 * Служебная строка без номера («Вы закрепили сообщение») пропускается: иначе «назад» после перехода не
 * появлялось (2026-10-20).
 */
private fun anchorOf(lines: List<ChatLine>, state: LazyListState): Long? =
    state.layoutInfo.visibleItemsInfo.sortedBy { it.index }
        .firstNotNullOfOrNull { lines.getOrNull(it.index)?.serverId?.takeIf { id -> id > 0 } }

/**
 * Перейти к сообщению: записать «откуда» и «куда», прокрутить, подсветить. Нет в ленте — ничего.
 *
 * Одно место для всех переходов — по ссылке, ответу, закреплённому, «назад» и «вперёд»: сообщение
 * встаёт **верхом к верху** видимой ленты (заказчик 2026-10-20: «переход идёт на низ сообщения —
 * сделай на верх»). Видимое — область самой ленты без её полей и без [FeedJumps.topCover].
 */
internal suspend fun FeedJumps.perform(target: Long, lines: List<ChatLine>, state: LazyListState, record: Boolean = true) {
    val index = lines.indexOfFirst { it.serverId == target }
    if (index < 0) return
    if (record) {
        val source = (from ?: anchorOf(lines, state))?.takeIf { it != target }
        buffer.jumped(source?.let { JumpPoint(chatId, it, JumpKind.FROM) }, JumpPoint(chatId, target, JumpKind.TO))
        changed()
    }
    from = null
    seen = false
    onJump?.invoke(if (record) "to" else "step", buffer.points.size)
    moving = true
    try {
        // Не на экране — сначала довести до него (лента перевёрнута: встаёт низом к низу).
        if (state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.animateScrollToItem(index)
        topDelta(state, index, topCover)?.let { if (it != 0) state.animateScrollBy(it.toFloat()) }
    } finally {
        moving = false
    }
    flash(target)
}

/**
 * На сколько прокрутить, чтобы верх сообщения [index] встал к верху видимой ленты. Лента
 * перевёрнута: место сообщения считается от низа, положительная прокрутка ведёт к старым — то есть
 * опускает сообщения. Выше старых нет — прокрутка упрётся сама.
 */
internal fun topDelta(state: LazyListState, index: Int, cover: Int): Int? {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    val top = info.viewportEndOffset - info.afterContentPadding - cover
    return item.offset + item.size - top
}

/** Лежит поверх ленты сверху — сообщает свою высоту, чтобы переход не прятал под ним сообщение. */
fun Modifier.coversFeedTop(jumps: FeedJumps): Modifier = this.then(
    Modifier.onSizeChanged { jumps.topCover = it.height },
)

/**
 * Снизу вверх: «вниз», «вперёд», «назад» (§11.4), справа над строкой ввода. «Вниз» — когда
 * человек ушёл от последнего сообщения; «назад» и «вперёд» — когда в буфере есть куда.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun JumpButtons(jumps: FeedJumps, lines: List<ChatLine>, state: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val awayFromBottom = state.firstVisibleItemIndex > 2
    val scroll: (Long) -> Unit = { to -> scope.launch { jumps.perform(to, lines, state, record = false) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        if (jumps.canBack) {
            JumpButton("↩", JUMP_BACK_TAG, onLong = jumps::clear) { jumps.step(jumps.buffer.back(), scroll) }
        }
        if (jumps.canForward) {
            JumpButton("↪", JUMP_FORWARD_TAG) { jumps.step(jumps.buffer.forward(), scroll) }
        }
        if (awayFromBottom) {
            JumpButton("↓", DOWN_TAG) { scope.launch { state.animateScrollToItem(0) } }
        }
    }
}

/**
 * Точка, на которой стоим, ушла с экрана целиком — ожила: «назад» вернёт к ней (ПЛАН-(БП)). Пока идёт
 * прокрутка перехода, не в счёт; точку сначала надо увидеть.
 */
@Composable
internal fun WatchLeaving(jumps: FeedJumps, lines: List<ChatLine>, state: LazyListState) {
    val feed by rememberUpdatedState(lines)
    LaunchedEffect(jumps, state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.index } }.collect { visible ->
            val b = jumps.buffer
            if (jumps.moving || !b.on) return@collect
            val p = b.points.getOrNull(b.at) ?: return@collect
            if (p.chatId != jumps.chatId) return@collect
            val index = feed.indexOfFirst { it.serverId == p.serverId }
            if (index < 0) return@collect
            if (index in visible) {
                jumps.seen = true
            } else if (jumps.seen) {
                b.left()
                jumps.seen = false
                jumps.changed()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JumpButton(glyph: String, tag: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.size(42.dp)
            .background(colors.surface, CircleShape)
            .background(colors.functional.copy(alpha = 0.6f), CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Caption(glyph, fontSize = 20.sp, color = colors.text)
    }
}
