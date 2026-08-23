package io.tima.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.tima.core.ui.Стан
import io.tima.core.ui.TimaTheme
import io.tima.domain.chat.ChatSummary
import io.tima.feature.auth.AuthState
import io.tima.feature.auth.AuthStore
import io.tima.feature.auth.ЭкранВхода
import io.tima.feature.chat.ChatStore
import io.tima.feature.chat.ChatsStore
import io.tima.feature.chat.ЭкранПереписок
import io.tima.feature.chat.ЭкранЧата

/**
 * Вход для ПК.
 *
 * Здесь и только здесь разрешено знать о платформе как о платформе (Plan.md §1.3): окно,
 * тема системы, каталоги. Всё остальное — общий код, тот же, что поедет на телефон.
 *
 * **Что работает и чего ещё нет.** Работает вход по телефону против живого сервера,
 * переписка на диске, ввод и очередь. Не работает отправка в сеть: насос очереди
 * подключается вместе с транспортом, следующим срезом. Поэтому сообщение честно висит с
 * отметкой «ждёт» — так и должно быть, очередь не скрывает, что оно не ушло.
 */
fun main() = application {
    val вход = remember { Вход.создать() }
    val состояниеОкна = rememberWindowState(
        // Планшетный формат по умолчанию: три полосы влезают, и сразу видно, что раскладку
        // решает ширина окна, а не устройство. Окно можно сузить — станет телефонным.
        size = DpSize(1100.dp, 820.dp),
        position = WindowPosition.Aligned(Alignment.Center),
    )

    Window(
        onCloseRequest = ::exitApplication,
        state = состояниеОкна,
        title = "TIMA",
    ) {
        TimaTheme(dark = isSystemInDarkTheme()) {
            Корень(вход)
        }
    }
}

/**
 * Корень: вход или приложение.
 *
 * Развилка ровно одна, и решает её **заведённое устройство**, а не флаг «вошли»: флаг
 * живёт в памяти и врёт после перезапуска. Заведённое означает сессию, а не только секрет
 * — секрет пишется до вызова сервера, и секрет без сессии это незаконченный вход.
 */
@Composable
private fun Корень(вход: Вход) {
    var секрет by remember { mutableStateOf(вход.секретЗаведённого()) }

    val текущий = секрет
    if (текущий == null) {
        Вхождение(вход) { секрет = вход.секретЗаведённого() }
        return
    }

    // remember по секрету: другое устройство — другая база и другой ключ покоя.
    val окружение = remember(текущий) { Окружение.открыть(текущий) }
    Приложение(окружение)
}

@Composable
private fun Вхождение(вход: Вход, onВошли: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember { AuthStore(вход.регистрация, scope) }
    val состояние by store.state.collectAsState()

    // Оба конечных состояния означают одно: устройство есть. «Готово» и «уже заведено»
    // различаются только тем, кто его завёл, а приложению дальше всё равно.
    if (состояние is AuthState.Готово || состояние is AuthState.УжеЗаведено) onВошли()

    ЭкранВхода(
        состояние = состояние,
        onНомер = store::номерИзменён,
        onКод = store::кодИзменён,
        onЗапросить = store::запроситьКод,
        onПодтвердить = store::подтвердить,
        onНазад = store::назад,
    )
}

/**
 * Окно приложения: стан из полос, в нём список и открытая переписка.
 *
 * Навигация верхнего уровня здесь ровно одна — какая переписка открыта. Больше сейчас и
 * не нужно: на телефоне подокно заменяет список, на широком формате стоит рядом, и **это
 * решает [Стан]**, а не код навигации. Полноценная навигация по семи окнам — К5.4.
 */
@Composable
private fun Приложение(окружение: Окружение) {
    val scope = rememberCoroutineScope()
    val список = remember { ChatsStore(окружение.переписки, scope) }
    var открытая by remember { mutableStateOf<ChatSummary?>(null) }

    val состояниеСписка by список.state.collectAsState()

    Стан(
        modifier = Modifier.fillMaxSize(),
        колонка = {
            ЭкранПереписок(
                состояние = состояниеСписка,
                onОткрыть = { открытая = it },
            )
        },
        главная = открытая?.let { переписка ->
            {
                // Store переписки живёт столько, сколько открыта переписка: ключ по
                // chatId, чтобы при переходе в другую переписку он пересоздался, а не
                // показал реплики предыдущей.
                val store = remember(переписка.chatId) {
                    ChatStore(
                        chatId = переписка.chatId,
                        observe = окружение.переписка,
                        send = окружение.отправка,
                        scope = scope,
                    )
                }
                val состояние by store.state.collectAsState()
                ЭкранЧата(
                    состояние = состояние,
                    собеседник = переписка.title ?: "Без имени",
                    onНабор = store::draftChanged,
                    onОтправить = { store.sendPressed() },
                    onНазад = { открытая = null },
                    onЗакрытьСообщение = store::noticeDismissed,
                )
            }
        },
    )
}
