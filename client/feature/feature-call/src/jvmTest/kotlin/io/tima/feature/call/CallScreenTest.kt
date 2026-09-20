package io.tima.feature.call

import androidx.compose.runtime.Composable
import io.tima.core.call.CallEvent
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

    @Test
    fun события_показываются_последним_а_не_списком() {
        // Решение заказчика 2026-09-20: копятся списком, показываются по одному. Если
        // лента однажды развернётся целиком, она отъест экран у видео — а оно здесь во
        // весь кадр.
        val one = capture("звонок-событие-одно", WIDTH, HEIGHT, dark = false) {
            screen(
                CallState(stage = CallStage.Connected),
                incoming = false,
                events = listOf(CallEvent(4, "Собеседник показывает себя")),
            )
        }
        val many = capture("звонок-событий-пять", WIDTH, HEIGHT, dark = false) {
            screen(
                CallState(stage = CallStage.Connected),
                incoming = false,
                events = (1..5).map { CallEvent(it, "Событие номер $it") },
            )
        }
        // Пять событий занимают ровно столько же места, сколько одно: видно последнее.
        assertTrue(
            one.difference(many) < 0.05,
            "лента развернулась списком: пять событий заняли не столько же места, сколько одно",
        )
    }

    @Test
    fun пустая_лента_не_занимает_места() {
        // Полоса, висящая пустой, отнимает строку у видео каждый звонок ради случая,
        // которого может и не быть.
        val without = capture("звонок-без-событий", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connected), incoming = false)
        }
        val with = capture("звонок-с-событием", WIDTH, HEIGHT, dark = false) {
            screen(
                CallState(stage = CallStage.Connected),
                incoming = false,
                events = listOf(CallEvent(4, "Камера не разрешена")),
            )
        }
        assertTrue(without.difference(with) > 0.0, "событие не показано вовсе")
    }

    @Test
    fun скрыть_видео_появляется_только_когда_есть_что_скрывать() {
        // Кнопка «скрыть» в звонке без чужого видео обещала бы то, чего нет, и сообщала
        // бы о видео собеседника раньше, чем оно появилось.
        val silent = capture("звонок-без-чужого-видео", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connected), incoming = false, onRemoteVideo = {})
        }
        val showing = capture("звонок-чужое-видео-идёт", WIDTH, HEIGHT, dark = false) {
            screen(
                CallState(stage = CallStage.Connected, remoteVideoShown = true),
                incoming = false,
                onRemoteVideo = {},
            )
        }
        assertTrue(silent.difference(showing) > 0.0, "кнопка «скрыть видео» не появилась")
    }

    @Test
    fun скрытое_видео_оставляет_кнопку_вернуть() {
        // Пропавшая вместе с картинкой кнопка означала бы, что вернуть её нечем.
        val hidden = capture("звонок-чужое-видео-скрыто", WIDTH, HEIGHT, dark = false) {
            screen(
                CallState(stage = CallStage.Connected, remoteVideoShown = false, remoteVideoTaken = false),
                incoming = false,
                onRemoteVideo = {},
            )
        }
        val plain = capture("звонок-разговор-простой", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connected), incoming = false, onRemoteVideo = {})
        }
        assertTrue(hidden.difference(plain) > 0.0, "скрытое видео не оставило кнопки «показать»")
    }

    @Test
    fun набор_и_разговор_различаются_а_соединение_отдельно() {
        // Три разных состояния, которые раньше были одним. Время идёт ТОЛЬКО в разговоре:
        // «Connected» у комнаты значит «мы вошли», а не «нам ответили», и таймер шёл с
        // момента нажатия «позвонить» (живой прогон 2026-09-20).
        val going = capture("звонок-соединяем", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connecting, inRoom = false), incoming = false)
        }
        val ringing = capture("звонок-звоним", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connecting, inRoom = true), incoming = false)
        }
        val talking = capture("звонок-говорим", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connected, inRoom = true), incoming = false, seconds = 12)
        }
        assertTrue(going.difference(ringing) > 0.0, "«соединяем» и «звоним» выглядят одинаково")
        assertTrue(ringing.difference(talking) > 0.0, "набор и разговор выглядят одинаково")
    }

    @Test
    fun положенная_собеседником_трубка_названа_своими_словами() {
        // «Звонок завершён» не отвечает на вопрос, кто его завершил, — а человеку важно
        // знать, оборвалось ли у него самого.
        val byPeer = capture("звонок-собеседник-положил", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Ended, peerLeft = true), incoming = false, onClose = {})
        }
        val byUs = capture("звонок-кончили-сами", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Ended), incoming = false, onClose = {})
        }
        assertTrue(byPeer.difference(byUs) > 0.0, "ушедший собеседник неотличим от нашей трубки")
    }

    @Test
    fun завершить_не_уезжает_за_край_экрана() {
        // Ряд кнопок раньше не переносился и резал последнюю — «Завершить». Положить
        // трубку в разговоре с видео было нечем (живой прогон 2026-09-20).
        val full = capture("звонок-все-кнопки", WIDTH, HEIGHT, dark = false) {
            screen(
                CallState(stage = CallStage.Connected, microphoneOn = true, cameraOn = true, remoteVideoShown = true),
                incoming = false,
                onRemoteVideo = {},
            )
        }
        val plain = capture("звонок-две-кнопки", WIDTH, HEIGHT, dark = false) {
            screen(CallState(stage = CallStage.Connected), incoming = false)
        }
        // Четыре кнопки занимают БОЛЬШЕ места, чем две: значит они перенеслись на вторую
        // строку, а не срезались по краю. Срезанные дали бы ту же высоту.
        assertTrue(full.difference(plain) > 0.0, "ряд кнопок не перестроился под четыре")
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
            events: List<CallEvent> = emptyList(),
            onRemoteVideo: ((Boolean) -> Unit)? = null,
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
                    events = events,
                    onRemoteVideo = onRemoteVideo,
                )
            },
        )
    }
}
