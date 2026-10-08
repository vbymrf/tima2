package io.tima.feature.shell

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf

/**
 * Вкладки окон — одно место на все окна (беда 2026-10-08 «вкладка окна сбрасывается при
 * переходе»).
 *
 * Окно при переходе в другое закрывается, и вкладка, жившая внутри него, пропадала:
 * «Свободное общение» и «Личная страница» возвращались на первую. «Телефон» и «Социум» помнили
 * свою, потому что их вкладки по отдельности вынесли наружу ради своих подокон. Здесь — для
 * всех окон сразу, и новое окно получает это само, через [WindowWithTabs].
 *
 * Только вкладка: прокрутка, открытый раздел и явный переход в нужное место — план хранения
 * местонахождения (заказчик 2026-10-08: «пока нет полного дерева всех подокон»).
 *
 * Помнится, пока приложение запущено; в настройки не пишется.
 */
class WindowTabs {
    private val chosen = mutableStateMapOf<Window, WindowTab>()

    /**
     * Вкладка окна: запомненная, если она есть среди [tabs], иначе первая. Вкладку могут
     * убрать или выключить — окно не открывается на несуществующей.
     */
    fun of(window: Window, tabs: List<WindowTab>): WindowTab = chosen[window]?.takeIf { it in tabs } ?: tabs.first()

    fun choose(window: Window, tab: WindowTab) {
        chosen[window] = tab
    }

    /** Вкладка окна, у которого ряд вкладок свой (`PhoneWindow`): `var tab by tabs.state(…)`. */
    fun state(window: Window, first: WindowTab): MutableState<WindowTab> = object : MutableState<WindowTab> {
        override var value: WindowTab
            get() = chosen[window] ?: first
            set(tab) {
                chosen[window] = tab
            }

        override fun component1(): WindowTab = value

        override fun component2(): (WindowTab) -> Unit = { value = it }
    }
}

/** Вкладки окон для [WindowWithTabs]; `null` — окно держит вкладку само (снимки, проверки). */
val LocalWindowTabs = compositionLocalOf<WindowTabs?> { null }
