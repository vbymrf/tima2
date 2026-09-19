package io.tima.feature.call

import androidx.compose.runtime.Composable
import io.tima.core.call.CallQuality
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.ui.Stage
import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.bothThemes
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Окно звонка в снимках (С2).
 *
 * Проверяются **решения**, а не картинка: входящий и исходящий различаются кнопками,
 * разговор показывает время, погашенное видео объяснено словами. Всё это легко потерять
 * при следующей правке экрана, и тогда потеря будет тихой.
 */
class CallScreenTest {

    @Test
    fun экран_заливает_свой_фон() {
        // Снимается на краске, которой в палитре нет: видно её — значит экран показывает
        // то, что под ним. Подложка цветом темы такого не поймала бы.
        val shots = bothThemes("звонок-фон", WIDTH, HEIGHT, backdrop = FOREIGN_BACKGROUND) {
            screen(CallState(stage = CallStage.Connected), incoming = false)
        }
        for ((name, shot) in shots) {
            assertTrue(!shot.has(FOREIGN_BACKGROUND), "$name: сквозь экран видна подложка")
        }
    }

    @Test
    fun входящий_и_исходящий_выглядят_по_разному() {
        // Их не различает SFU: у него оба — Connecting. Различает то, кто начал, и это
        // приходит снаружи. Если различие потеряется, человек увидит «Принять» на звонке,
        // который сам же и начал.
        val incoming = capture("звонок-входящий", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connecting), incoming = true)
        }
        val outgoing = capture("звонок-исходящий", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connecting), incoming = false)
        }
        assertTrue(incoming.difference(outgoing) > 0.0, "входящий и исходящий нарисовались одинаково")
    }

    @Test
    fun разговор_показывает_время_а_не_слова() {
        val short = capture("звонок-разговор-5с", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connected), incoming = false, seconds = 5)
        }
        val long = capture("звонок-разговор-272с", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connected), incoming = false, seconds = 272)
        }
        assertTrue(short.difference(long) > 0.0, "время разговора не показано: снимки совпали")
    }

    @Test
    fun погашенное_видео_объяснено_словами() {
        // allow_pause: true на сервере с 2026-09-19 — картинка может пропасть сама.
        // Молча пропавшая картинка читается как поломка, поэтому объяснение обязательно.
        val quiet = capture("звонок-видео-идёт", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connected, cameraOn = true), incoming = false)
        }
        val paused = capture("звонок-видео-погашено", WIDTH, HEIGHT, dark = false) {
            screen(
                CallState(stage = CallStage.Connected, cameraOn = true, videoPaused = true),
                incoming = false,
            )
        }
        assertTrue(paused.difference(quiet) > 0.0, "про погашенное видео на экране ничего не сказано")
    }

    @Test
    fun качество_связи_молчит_пока_его_не_сказали() {
        // «Связь выясняем» на каждом звонке было бы шумом: почти всегда она в порядке.
        val unknown = capture("звонок-качество-неизвестно", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connected), incoming = false)
        }
        val poor = capture("звонок-качество-плохое", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connected, quality = CallQuality.Poor), incoming = false)
        }
        assertTrue(poor.difference(unknown) > 0.0, "оценка связи не показана")
    }

    @Test
    fun завершённый_звонок_предлагает_перезвонить() {
        val ended = capture("звонок-завершён", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Ended), incoming = false, onCallAgain = {}, onClose = {})
        }
        val talking = capture("звонок-разговор", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connected), incoming = false)
        }
        assertTrue(ended.difference(talking) > 0.0, "завершённый звонок выглядит как разговор")
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800

        @Composable
        fun screen(
            state: CallState,
            incoming: Boolean,
            seconds: Int = 0,
            onCallAgain: (() -> Unit)? = null,
            onClose: (() -> Unit)? = null,
        ) = Stage(
            column = {
                CallScreen(
                    state = state,
                    peer = "Аня Борисова",
                    incoming = incoming,
                    seconds = seconds,
                    onAccept = {},
                    onDecline = {},
                    onHangUp = {},
                    onMicrophone = {},
                    onCamera = {},
                    onCallAgain = onCallAgain,
                    onClose = onClose,
                )
            },
        )
    }
}
