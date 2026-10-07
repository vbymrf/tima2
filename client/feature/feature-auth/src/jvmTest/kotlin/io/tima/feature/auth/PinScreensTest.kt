package io.tima.feature.auth

import io.tima.core.ui.TimaColors
import io.tima.domain.account.AccountDevice
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * «Секретная фраза и устройства» по группам и экран пин-кода в снимках (ПЛАН-(ПН), пробы
 * `пробы-пин-код.html`). Проверяются утверждения проб: раздел «Аккаунт» красный, пузырь пин-кода
 * меняется со включением, клавиатура пина рисуется. Картинки — в `build/снимки/`.
 */
class PinScreensTest {

    @Test
    fun экран_по_группам_и_красный_аккаунт() {
        val full = capture("фраза-и-устройства-группы", WIDTH, 1500, dark = false) { grouped(pinOn = false) }
        assertTrue(full.patchHas(TimaColors.light.alarm, side = 2), "красного раздела «Аккаунт» не видно")
    }

    @Test
    fun пузырь_пин_кода_меняется_со_включением() {
        val off = capture("пин-выключен", WIDTH, 500, dark = false) { grouped(pinOn = false) }
        val on = capture("пин-включён", WIDTH, 500, dark = false) { grouped(pinOn = true) }
        assertTrue(off.difference(on) > 0.0, "пузырь «выключен/включён» не отличается")
    }

    @Test
    fun клавиатура_пина_и_пауза() {
        val enter = capture("пин-ввод", WIDTH, 760, dark = false) {
            PinScreen(
                state = PinFlowState(PinMode.Unlock, PinStep.Current, typed = 2),
                onDigit = {}, onErase = {}, onForgot = {}, onPhrase = {}, onChoose = {}, onTick = {}, now = { 0L },
                who = PinWho("Анна", "+799 ••• 01", temporary = false), onOther = {},
            )
        }
        val paused = capture("пин-пауза", WIDTH, 760, dark = false) {
            PinScreen(
                state = PinFlowState(PinMode.Unlock, PinStep.Current, pausedUntil = 30_000),
                onDigit = {}, onErase = {}, onForgot = {}, onPhrase = {}, onChoose = {}, onTick = {}, now = { 0L },
                who = PinWho("Анна", "+799 ••• 01", temporary = false), onOther = {},
            )
        }
        val phrase = capture("пин-только-фраза", WIDTH, 760, dark = false) {
            PinScreen(
                state = PinFlowState(PinMode.Unlock, PinStep.Phrase, phraseOnly = true, message = PinMessage.Offline),
                onDigit = {}, onErase = {}, onForgot = {}, onPhrase = {}, onChoose = {}, onTick = {}, now = { 0L },
                who = PinWho("Работа", "", temporary = true),
            )
        }
        assertTrue(enter.difference(paused) > 0.0, "пауза не отличается от ввода")
        assertTrue(enter.difference(phrase) > 0.05, "экран фразы не заменил клавиатуру")
    }

    private companion object {
        const val WIDTH = 380

        val STATE = DevicesState(
            devices = listOf(
                AccountDevice("d-1", "Redmi Note 12", "01.10.2026", current = true, certified = true),
                AccountDevice("d-2", "Компьютер", "05.10.2026", current = false, certified = true),
                AccountDevice("d-3", "Samsung A54", "02.10.2026", current = false, certified = false),
            ),
            startAnewBanned = false,
        )

        @androidx.compose.runtime.Composable
        fun grouped(pinOn: Boolean) = DeviceScreen(
            state = STATE,
            onAsk = {},
            onConfirm = {},
            onChangedMind = {},
            buildVersion = "2.0.143",
            onSignOut = {},
            onScan = {},
            onStartCopy = {},
            onRotateCopy = {},
            onSendBanCode = {},
            onBanStartAnew = { _, _ -> },
            rereg = ReregView(text = null, canStart = true),
            onStartRereg = {},
            onSendReregCode = {},
            onRereg = { _, _, _, _ -> },
            phoneChange = PhoneChangeView(text = null, canStart = true),
            onSendPhoneCode = {},
            onPhoneChange = { _, _, _ -> },
            pinOn = pinOn,
            onPin = {},
        )
    }
}
