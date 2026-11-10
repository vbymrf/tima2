package io.tima.shared

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Поле ввода и клавиатура — в журнал (заказчик 2026-11-10, Honor: «клавиатура появилась не сразу»).
 *
 * Разбор упёрся в то, что журнал не знал, **просили ли мы клавиатуру**: по системному журналу было видно, что окно
 * клавиатуры гасилось, а наше ли поле её звало — нет. Теперь видно обе стороны: поле попросило ввод (любое из
 * полей приложения — перехват общий, на корне), клавиатура показалась или нет, через сколько и какой высоты.
 *
 * Только где клавиатура экранная ([soft]): на ПК поле просит ввод на каждом нажатии, а показывать нечего.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KeyboardWatch(soft: Boolean, content: @Composable () -> Unit) {
    if (!soft) {
        content()
        return
    }
    val scope = rememberCoroutineScope()
    val log = remember(scope) { KeyboardLog(scope) }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    LaunchedEffect(log, density) {
        snapshotFlow { ime.getBottom(density) > 0 }.distinctUntilChanged().collect { up ->
            if (up) {
                // Высота — когда клавиатура доехала: на первом кадре она ещё растёт.
                delay(KeyboardLog.SETTLE_MS)
                log.shown(with(density) { ime.getBottom(density).toDp().value.toInt() })
            } else {
                log.hidden()
            }
        }
    }
    val interceptor = remember(log) {
        object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(
                request: PlatformTextInputMethodRequest,
                nextHandler: PlatformTextInputSession,
            ): Nothing {
                log.asked()
                try {
                    nextHandler.startInputMethod(request)
                } finally {
                    log.released()
                }
            }
        }
    }
    InterceptPlatformTextInput(interceptor) { content() }
}

/** Что было с полем и клавиатурой — и что из этого стоит строки журнала. */
internal class KeyboardLog(
    private val scope: CoroutineScope,
    private val clock: () -> Long = { msNow() },
    private val note: (String, Array<out Pair<String, Any?>>) -> Unit = { text, details -> Journal.note(LogCode.KEYBOARD, text, *details) },
    private val trouble: (String, Array<out Pair<String, Any?>>) -> Unit = { text, details -> Journal.trouble(LogCode.KEYBOARD, text, *details) },
) {
    private var askedAt = 0L
    private var asking = false
    private var up = false
    private var waiting: Job? = null

    fun asked() {
        asking = true
        askedAt = clock()
        note("поле ввода просит клавиатуру", arrayOf("клавиатура уже видна" to up))
        if (up) return
        waiting?.cancel()
        waiting = scope.launch {
            delay(LATE_MS)
            if (asking && !up) trouble("поле ввода в фокусе, клавиатуры нет", arrayOf("ждали мс" to LATE_MS))
        }
    }

    fun released() {
        asking = false
        waiting?.cancel()
        note("поле ввода отпустило клавиатуру", arrayOf("клавиатура видна" to up))
    }

    fun shown(heightDp: Int) {
        up = true
        waiting?.cancel()
        note("клавиатура показана", arrayOf("высота dp" to heightDp, "мс после просьбы" to if (asking) clock() - askedAt else "без просьбы"))
    }

    fun hidden() {
        if (!up) return
        up = false
        note("клавиатура скрыта", arrayOf("поле держит ввод" to asking))
    }

    companion object {
        /** Сколько ждать клавиатуру, прежде чем записать «нет»: обычно она выезжает за 0,2–0,4 с. */
        const val LATE_MS = 1_500L

        /** Сколько дать клавиатуре доехать, прежде чем мерить высоту. */
        const val SETTLE_MS = 400L
    }
}
