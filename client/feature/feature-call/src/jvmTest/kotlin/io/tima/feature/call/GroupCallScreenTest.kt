package io.tima.feature.call

import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/** Окно 0 группового звонка (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ ГЗ4): сетка, счётчик, пауза. */
class GroupCallScreenTest {

    private fun tile(i: Int, name: String, mic: Boolean = true, speaking: Boolean = false) =
        GroupTile("k$i", name, name.take(1), video = null, microphoneOn = mic, speaking = speaking, paused = false, self = i == 0)

    private fun stage(count: Int, paused: Boolean = false) = GroupStage(
        title = "Планёрка",
        tiles = listOf("Вы", "Анна", "Борис", "Вера", "Галина", "Дмитрий", "Елена", "Жанна", "Зоя").take(count)
            .mapIndexed { i, n -> tile(i, n, mic = i != 2, speaking = i == 1).copy(videoTrouble = "Видео не приходит".takeIf { i == 3 }) },
        count = count,
        max = 25,
        paused = paused,
        mine = true,
        onParticipants = {},
        onStopAll = {},
    )

    private fun screen(count: Int, paused: Boolean = false) = capture("групповой-окно-$count${if (paused) "-пауза" else ""}", 400, 760, dark = false) {
        CallScreen(
            state = CallState(stage = CallStage.Connected, microphoneOn = true, cameraOn = true, roomPaused = paused),
            peer = "Планёрка",
            incoming = false,
            onAccept = {},
            onDecline = {},
            onHangUp = {},
            onMicrophone = {},
            onCamera = {},
            seconds = 312,
            group = stage(count, paused),
        )
    }

    @Test
    fun сетка_на_четверых_и_на_девятерых_разная() {
        val four = screen(4)
        val nine = screen(9)
        assertTrue(four.difference(nine) > 0.02, "сетка не меняется от числа участников")
    }

    @Test
    fun пауза_видна() {
        val on = screen(3, paused = true)
        val off = screen(3)
        assertTrue(on.difference(off) > 0.0, "пауза на экране не видна")
    }
}
