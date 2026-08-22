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
 * **Что уже работает и чего ещё нет.** Работает переписка на диске: список читается из
 * базы, набранное ложится в очередь, и то и другое переживает закрытие приложения. Сети
 * нет — регистрация устройства и вход это К5.1, — поэтому сообщение остаётся в очереди со
 * отметкой «ждёт». Это видно на экране, и так и должно быть: очередь честно показывает,
 * что сообщение не ушло.
 */
fun main() = application {
    val окружение = remember { Окружение.открыть() }
    val состояниеОкна = rememberWindowState(
        // Планшетный формат по умолчанию: три полосы влезают, и сразу видно, что раскладку
        // решает ширина окна, а не устройство. Окно можно сузить — станет телефонным.
        size = DpSize(1100.dp, 820.dp),
        position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
    )

    Window(
        onCloseRequest = ::exitApplication,
        state = состояниеОкна,
        title = "TIMA",
    ) {
        TimaTheme(dark = isSystemInDarkTheme()) {
            Приложение(окружение)
        }
    }
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
