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

    @Test
    fun журнал_стенда_группового_список_и_карточка_участника() {
        val tiles = stage(3).tiles.mapIndexed { i, t ->
            if (i == 1) t.copy(bench = "h264 960 · H264 720×960 · апп OMX.Exynos.AVC.Encoder · обрезка 16",
                incoming = io.tima.core.call.PeerIncoming("720×960", "H264", 340, "OMX.sprd.h264.decoder", 6.1, 2, 1))
            else t
        }
        val line = BenchLine(at = 3, total = 24, preset = "h264 320")
        val list = capture("групповой-журнал-стенда", 400, 500, dark = false) {
            GroupBenchJournal(line, tiles, onClose = {})
        }
        capture("групповой-полоса-стенда", 400, 80, dark = false) { GroupBenchStrip(line, onJournal = {}) }
        capture("полоса-стенда-динамик-выключен", 400, 100, dark = false) { BenchStrip(line.copy(speakerOff = true)) }
        val empty = capture("групповой-журнал-стенда-пусто", 400, 500, dark = false) {
            GroupBenchJournal(line, emptyList(), onClose = {})
        }
        kotlin.test.assertTrue(list.difference(empty) > 0.01, "участников в журнале стенда не видно")
    }
}
