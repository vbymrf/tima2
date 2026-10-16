package io.tima.shared

import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.SoundRoute
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Экран во время звонка не гаснет, кроме разговора у уха без своей камеры (заказчик
 * 2026-10-16: «Экран должен гаснуть только если включен звук Ухо и выключена камера…
 * Работает динамик — экран не должен гаснуть»).
 */
class ScreenOnTest {

    private val talking = CallState(stage = CallStage.Connected)
    private val atEar = talking.copy(sound = SoundRoute.Earpiece)

    @Test
    fun у_уха_без_своей_камеры_экран_гаснет() {
        assertFalse(screenOnWanted(true, atEar), "голосовой у уха — экран гаснет, как обычно")
        assertFalse(
            screenOnWanted(true, atEar.copy(remoteVideoShown = true)),
            "у уха без своей камеры гаснет, даже если собеседник показывает себя",
        )
    }

    @Test
    fun громкая_связь_держит_экран_и_без_видео() {
        assertTrue(screenOnWanted(true, talking.copy(sound = SoundRoute.Speaker)), "голосовой на громкой связи")
        assertTrue(screenOnWanted(true, talking.copy(sound = SoundRoute.Speaker, remoteVideoShown = true)))
        assertTrue(screenOnWanted(true, talking.copy(sound = SoundRoute.Headset)), "наушники — не «Ухо»")
    }

    @Test
    fun своя_камера_держит_экран_и_у_уха() {
        assertTrue(screenOnWanted(true, atEar.copy(cameraOn = true)))
        assertTrue(screenOnWanted(true, atEar.copy(stage = CallStage.Connecting, cameraOn = true)), "звоним с видео — ещё не ответили")
        assertTrue(screenOnWanted(true, talking.copy(stage = CallStage.Reconnecting, sound = SoundRoute.Speaker)), "связь возвращается")
    }

    @Test
    fun кончившийся_или_чужой_звонок_экран_не_держит() {
        assertFalse(screenOnWanted(true, talking.copy(stage = CallStage.Ended, cameraOn = true)))
        assertFalse(screenOnWanted(false, talking.copy(cameraOn = true)), "звонка у нас нет")
    }
}
