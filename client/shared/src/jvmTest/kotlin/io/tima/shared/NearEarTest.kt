package io.tima.shared

import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.SoundRoute
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Датчик приближения — вариант 2а (ПЛАН-(В)-ВИДЕО.md В10, решение заказчика 2026-09-29). */
class NearEarTest {

    private val talking = CallState(stage = CallStage.Connected, sound = SoundRoute.Earpiece)

    @Test
    fun голосовой_разговор_в_разговорный_гасит_экран_у_уха() {
        assertTrue(nearEarWanted(active = true, state = talking))
    }

    @Test
    fun видео_громкая_и_наушники_не_гасят() {
        assertFalse(nearEarWanted(true, talking.copy(cameraOn = true)), "видеозвонок к уху не подносят")
        assertFalse(nearEarWanted(true, talking.copy(sound = SoundRoute.Speaker)), "громкая связь")
        assertFalse(nearEarWanted(true, talking.copy(sound = SoundRoute.Headset)), "наушники")
        assertFalse(nearEarWanted(true, talking.copy(sound = SoundRoute.Unknown)), "ПК: куда звук, не знаем")
    }

    @Test
    fun до_ответа_и_после_конца_не_гасит() {
        assertFalse(nearEarWanted(true, talking.copy(stage = CallStage.Connecting)))
        assertFalse(nearEarWanted(true, talking.copy(stage = CallStage.Ended)))
        assertFalse(nearEarWanted(false, talking))
    }
}
