package io.tima.shared

import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Экран во время видеозвонка не гаснет (решение заказчика 2026-09-30). */
class ScreenOnTest {

    private val talking = CallState(stage = CallStage.Connected)

    @Test
    fun видео_своё_или_собеседника_держит_экран() {
        assertTrue(screenOnWanted(true, talking.copy(cameraOn = true)))
        assertTrue(screenOnWanted(true, talking.copy(remoteVideoShown = true)))
        assertTrue(screenOnWanted(true, talking.copy(stage = CallStage.Connecting, cameraOn = true)), "звоним с видео — ещё не ответили")
        assertTrue(screenOnWanted(true, talking.copy(stage = CallStage.Reconnecting, remoteVideoShown = true)), "связь возвращается")
    }

    @Test
    fun голосовой_или_кончившийся_экран_не_держит() {
        assertFalse(screenOnWanted(true, talking), "голосовой — экран гаснет, как обычно")
        assertFalse(screenOnWanted(true, talking.copy(stage = CallStage.Ended, cameraOn = true)))
        assertFalse(screenOnWanted(false, talking.copy(cameraOn = true)), "звонка у нас нет")
    }
}
