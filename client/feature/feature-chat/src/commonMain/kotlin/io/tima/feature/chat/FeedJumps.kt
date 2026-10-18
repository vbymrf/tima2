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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.domain.chat.ChatLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Переходы внутри переписки (Решения §11.4): по блоку ответа, вставленному и «Продолжить чтение».
 * Это **местная история прокрутки**, а не «назад» приложения: помнятся все переходы, после «назад»
 * появляется «вперёд». Номера — серверные номера сообщений: место в списке меняется с каждым новым.
 */
@Stable
class FeedJumps {
    /** Куда просили перейти; ведёт лента — у неё прокрутка. */
    var request by mutableStateOf<Long?>(null)
        private set

    /** Сообщение коротко подсвечено после перехода (В34). */
    var highlight by mutableStateOf<Long?>(null)
        private set

    val back = mutableStateListOf<Long>()
    val forward = mutableStateListOf<Long>()

    /** Кому сказать о переходе — `(to — к сообщению, step — назад или вперёд; длина цепочки)`; журнал ведёт общий слой. */
    var onJump: ((String, Int) -> Unit)? = null

    fun go(serverId: Long) {
        request = serverId
    }

    internal fun taken() {
        request = null
    }

    internal suspend fun flash(serverId: Long) {
        highlight = serverId
        delay(FLASH_MS)
        if (highlight == serverId) highlight = null
    }

    /** Долгое нажатие на «назад» — цепочка забыта. */
    fun clear() {
        back.clear()
        forward.clear()
    }

    companion object {
        const val FLASH_MS = 1_600L
    }
}

/** Метки кнопок переходов (СЦЕНАРИИ-ПО-ОКНАМ). */
const val JUMP_BACK_TAG = "chat:jump.back"
const val JUMP_FORWARD_TAG = "chat:jump.forward"
const val DOWN_TAG = "chat:down"

/** Номер сообщения у верхнего края экрана сейчас — то, к чему вернёт «назад». */
private fun anchorOf(lines: List<ChatLine>, state: LazyListState): Long? =
    lines.getOrNull(state.firstVisibleItemIndex)?.serverId?.takeIf { it > 0 }

/** Перейти к сообщению: запомнить, откуда, прокрутить, подсветить. Нет на устройстве — ничего. */
internal suspend fun FeedJumps.perform(target: Long, lines: List<ChatLine>, state: LazyListState, record: Boolean = true) {
    val index = lines.indexOfFirst { it.serverId == target }
    if (index < 0) return
    if (record) {
        anchorOf(lines, state)?.takeIf { it != target }?.let { back.add(it); forward.clear() }
    }
    onJump?.invoke(if (record) "to" else "step", back.size + forward.size)
    state.animateScrollToItem(index)
    flash(target)
}

/**
 * Снизу вверх: «вниз», «вперёд», «назад» (§11.4), справа над строкой ввода. «Вниз» — когда
 * человек ушёл от последнего сообщения; «назад» и «вперёд» — когда есть куда.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun JumpButtons(jumps: FeedJumps, lines: List<ChatLine>, state: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val awayFromBottom = state.firstVisibleItemIndex > 2
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
        if (jumps.back.isNotEmpty()) {
            JumpButton("↩", JUMP_BACK_TAG, onLong = jumps::clear) {
                val to = jumps.back.removeAt(jumps.back.lastIndex)
                lines.getOrNull(state.firstVisibleItemIndex)?.serverId?.takeIf { it > 0 }?.let { jumps.forward.add(it) }
                scope.launch { jumps.perform(to, lines, state, record = false) }
            }
        }
        if (jumps.forward.isNotEmpty()) {
            JumpButton("↪", JUMP_FORWARD_TAG) {
                val to = jumps.forward.removeAt(jumps.forward.lastIndex)
                lines.getOrNull(state.firstVisibleItemIndex)?.serverId?.takeIf { it > 0 }?.let { jumps.back.add(it) }
                scope.launch { jumps.perform(to, lines, state, record = false) }
            }
        }
        if (awayFromBottom) {
            JumpButton("↓", DOWN_TAG) { scope.launch { state.animateScrollToItem(0) } }
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
