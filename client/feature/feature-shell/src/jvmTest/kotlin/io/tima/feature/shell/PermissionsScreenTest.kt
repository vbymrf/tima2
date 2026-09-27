package io.tima.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.tima.core.ui.Tima
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * «Настройки → Разрешения» (заказчик 2026-09-27): пункт, где разрешение не дано, —
 * красным заголовком; всё разрешено — красного на экране нет. «Разрешено» — серым.
 *
 * Красный ищется по пикселям: цвет `alarm` в палитре редкий и намеренный, и
 * если он есть на снимке, его нарисовал именно заголовок.
 */
class PermissionsScreenTest {

    @Composable
    private fun screen(allGiven: Boolean) = PermissionsScreen(
        access = NotifyAccess.Given,
        onAsk = {},
        onBattery = {},
        batteryFree = allGiven,
        callsChannelOn = allGiven,
        onCallsChannel = {},
        fullScreenOn = true,
        onFullScreen = {},
        microphone = true,
        camera = allGiven,
    )

    @Test
    fun не_данное_красным_всё_данное_без_красного() {
        var alarm = Color.Unspecified
        val смешано = capture("разрешения-не-все", 380, 1400, dark = false) {
            alarm = Tima.colors.alarm
            screen(allGiven = false)
        }
        val всё = capture("разрешения-все", 380, 1400, dark = false) { screen(allGiven = true) }
        assertTrue(смешано.has(alarm), "пункт без разрешения не красный")
        assertFalse(всё.has(alarm), "всё разрешено, а красное на экране есть")
    }
}
