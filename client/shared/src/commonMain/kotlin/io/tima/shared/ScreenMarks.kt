package io.tima.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode

/**
 * **Состояние экрана для сценариев — две метки** (ПЛАН-(МО)-МЕТОК-ОКОН, 2026-10-09).
 *
 * - `screen:<место>` — окно, его вкладка, подэкран: `screen:Phone.Chats`, `screen:chat`,
 *   `screen:settings.update`, `screen:call.ended`. Место есть всегда и одно.
 * - `popup:<подокно>` — что открыто поверх места и закрывается: `popup:switcher`,
 *   `popup:search`, `popup:pin.lock`. Поверх ничего — **`popup:none`**.
 *
 * Обе метки на экране всегда, у каждой ровно одно значение. Поэтому сценарий проверяет пару
 * **положительно** — «видно `screen:chat` и `popup:none`» отвечает сразу, — и ему не нужна
 * проверка «метки подокна не видно», которая у Maestro ждёт около 8 с (PH-36 навыка телефонов).
 *
 * **Метки вычисляются из состояния, а не ставятся на корни окон.** Большинство подокон у нас
 * подменяет весь экран (ранний `return` в `App`), и окна под ними нет в дереве — метка на его
 * корне пропала бы вместе с ним. Место здесь — то, куда вернёмся, закрыв подокно.
 *
 * Коды те же, что журнал пишет под `SCREEN-OPEN` (`whereCode`, окна, вкладки): одно имя в
 * журнале и в дереве экрана. Списки мест и подокон, как попасть и как закрыть, —
 * `doc_mig/ФУНКЦИОНАЛ/АВТОТЕСТЫ/СЦЕНАРИИ-ПО-ОКНАМ.md`, раздел «Места и подокна».
 */
@Composable
fun ScreenMarks(place: String, popup: String) {
    // Узел не нулевого размера: узел без размера сценарий может не счесть видимым. Ни
    // надписи, ни нажатия — читатель экрана его не озвучивает, касания проходят сквозь.
    Box(Modifier.size(1.dp).testTag(SCREEN_TAG_PREFIX + place))
    Box(Modifier.size(1.dp).testTag(POPUP_TAG_PREFIX + popup))
    LaunchedEffect(place, popup) {
        Journal.note(LogCode.SCREEN_OPEN, "экран", "место" to place, "подокно" to popup)
    }
}

const val SCREEN_TAG_PREFIX: String = "screen:"
const val POPUP_TAG_PREFIX: String = "popup:"

/**
 * Коды подокон — перечнем в одном месте: места вызова берут отсюда, а тест
 * `ScenarioTagsTest` сверяет перечень с учётом сценариев. Код — латиницей и навсегда, как коды
 * журнала: по нему ищут в отчётах.
 */
object PopupCode {
    /** Поверх места ничего нет. */
    const val NONE = "none"

    // Подменяют весь экран — ветки `App` со своим `return`, в порядке проверки.
    const val INVITE = "invite"
    const val CONTACT_NEW = "contact.new"
    const val SECTIONS = "sections"
    const val COMMUNITY_SECTIONS = "community.sections"
    const val COMMUNITY_VIEW = "community.view"
    const val CONTACT_VIEW = "contact.view"
    const val CONTACT_SOUND = "contact.sound"
    const val CALL_SETUP = "call.setup"
    const val CALL_LEDGER = "call.ledger"
    const val CLOSE_QUESTION = "close.question"
    const val ACCOUNT_LEAVING = "account.leaving"
    const val UPDATE_REQUIRED = "update.required"
    const val NOTICE = "notice"
    const val SWITCHER = "switcher"

    // Поверх окна, которое на месте.
    const val PIN_LOCK = "pin.lock"
    const val SEARCH = "search"
    const val CHAT_MENU = "chat.menu"
    const val CHAT_FAILED = "chat.failed"
    const val CHAT_COLOR = "chat.color"
    /** Меню отправленного сообщения (Решения §11.1). */
    const val CHAT_MESSAGE = "chat.message"
    /** Редактор сообщения поверх переписки (Решения §10). */
    const val CHAT_EDITOR = "chat.editor"
}

/**
 * Коды мест, которых нет в коде подэкранов журнала (`whereCode` в `Root.kt`) и которые не
 * «окно.вкладка»: экраны до входа, сканер, стадии окна звонка.
 */
object PlaceCode {
    const val ENTRY = "entry"
    const val DEVICE_REVOKED = "device.revoked"
    const val SCAN_CODE = "scan.code"
    const val CALL_ENDED = "call.ended"
    const val CALL_INCOMING = "call.incoming"
    const val CALL_DIALING = "call.dialing"
    const val CALL_TALK = "call.talk"
}

/**
 * Подокна, которые живут **внутри** окна, а не в ветке `App`: строка поиска «Телефона», листы
 * переписки («•••», свой цвет, неотправленное). Их состояние лежит в самих экранах; экран
 * сообщает о подокне сюда, `App` берёт последнее открытое.
 */
class InnerPopups {
    private val open = mutableStateListOf<String>()

    val top: String? get() = open.lastOrNull()

    fun opened(code: String) {
        open.remove(code)
        open.add(code)
    }

    fun closed(code: String) {
        open.remove(code)
    }
}

val LocalInnerPopups = compositionLocalOf<InnerPopups?> { null }

/** Экран с подокном внутри: пока [shown], подокно [code] — в паре меток. */
@Composable
fun InnerPopup(code: String, shown: Boolean) {
    val popups = LocalInnerPopups.current ?: return
    DisposableEffect(popups, code, shown) {
        if (shown) popups.opened(code)
        onDispose { popups.closed(code) }
    }
}
