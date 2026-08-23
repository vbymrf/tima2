package io.tima.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.tima.core.database.SqlChatBook
import io.tima.core.encryption.PersonalChatIdsOverKodium
import io.tima.core.encryption.deviceIdentityFrom
import io.tima.core.ui.Стан
import io.tima.core.ui.TimaTheme
import io.tima.domain.account.Session
import io.tima.domain.chat.StartPersonalChat
import io.tima.feature.auth.AuthState
import io.tima.feature.auth.AuthStore
import io.tima.feature.auth.ЭкранВхода
import io.tima.feature.chat.ChatStore
import io.tima.feature.chat.ChatsStore
import io.tima.feature.chat.НоваяПерепискаStore
import io.tima.feature.chat.ЭкранНовойПереписки
import io.tima.feature.chat.ЭкранПереписок
import io.tima.feature.chat.ЭкранЧата
import kotlinx.coroutines.delay

/**
 * Вход для ПК.
 *
 * Здесь и только здесь разрешено знать о платформе как о платформе (Plan.md §1.3): окно,
 * тема системы, каталоги. Всё остальное — общий код, тот же, что поедет на телефон.
 *
 * **Что работает.** Вход по телефону против живого сервера, переписка на диске, новая
 * переписка по номеру, отправка в сеть. Чего нет: приёма по живому каналу — он приезжает
 * следующим срезом.
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
 * Развилка ровно одна, и решает её **заведённое устройство**, а не флаг «вошли»: флаг живёт
 * в памяти и врёт после перезапуска. Заведённое означает сессию, а не только секрет —
 * секрет пишется до вызова сервера, и секрет без сессии это незаконченный вход.
 */
@Composable
private fun Корень(вход: Вход) {
    var устройство by remember { mutableStateOf(вход.заведённое()) }

    val текущее = устройство
    if (текущее == null) {
        Вхождение(вход) { устройство = вход.заведённое() }
        return
    }

    // remember по устройству: другое устройство — другая база и другой ключ покоя.
    val окружение = remember(текущее) { Окружение.открыть(текущее.секрет) }
    val сеть = remember(текущее) { Сеть.создать(текущее.сессия) }
    val отправитель = remember(текущее) {
        Отправитель(
            окружение = окружение,
            сеть = сеть,
            сессия = текущее.сессия,
            личность = deviceIdentityFrom(текущее.секрет),
        )
    }
    Приложение(окружение, сеть, отправитель, текущее.сессия)
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

/** Что открыто в главной области. Вся навигация верхнего уровня — эти три случая. */
private sealed interface Куда {
    /** Ничего: на телефоне это список, на широком формате — пустая главная область. */
    data object Ничего : Куда

    /** Подокно «новая переписка». */
    data object Новая : Куда

    /** Переписка. Имя хранится здесь, потому что строка в списке появится позже — потоком. */
    data class Переписка(val chatId: String, val имя: String?) : Куда
}

/**
 * Окно приложения: стан из полос, в нём список и то, что открыто.
 *
 * **Навигация здесь — три случая, и ни одного стека.** Стек на этом этапе был бы механизмом
 * без нужды: из подокна выходят «назад» в список, из списка открывают одно. На телефоне
 * подокно ЗАМЕНЯЕТ список, на широком стоит рядом — и это решает [Стан], а не код
 * навигации. Семь окон и рейка — К5.4.
 */
@Composable
private fun Приложение(
    окружение: Окружение,
    сеть: Сеть,
    отправитель: Отправитель,
    сессия: Session,
) {
    val scope = rememberCoroutineScope()
    val список = remember { ChatsStore(окружение.переписки, scope) }
    var куда by remember { mutableStateOf<Куда>(Куда.Ничего) }

    val новая = remember {
        НоваяПерепискаStore(
            start = StartPersonalChat(
                directory = сеть.справочник,
                chats = SqlChatBook(окружение.db, окружение.шифр),
                ids = PersonalChatIdsOverKodium,
            ),
            myUserId = сессия.userId,
            scope = scope,
        )
    }

    val состояниеСписка by список.state.collectAsState()
    val состояниеНовой by новая.state.collectAsState()

    // Переписка начата — открываем её. Один и тот же признак ведёт и к открытию, и к
    // закрытию подокна: иначе они однажды разойдутся.
    LaunchedEffect(состояниеНовой.начата) {
        val chatId = состояниеНовой.начата ?: return@LaunchedEffect
        куда = Куда.Переписка(chatId, состояниеНовой.номер)
        новая.сброс()
    }

    // Отправка идёт ОТ ИЗМЕНЕНИЙ, а не по таймеру: список приходит потоком из базы, и
    // новое сообщение в очереди — это его изменение. Опрос по таймеру давал бы в v1 и
    // задержку, и лишние пробуждения.
    LaunchedEffect(состояниеСписка) {
        отправитель.проход()
    }

    // Медленное биение — только ради ПОВТОРОВ: сроки у них свои (секунда, пять, две
    // минуты), и наступают они без изменений в базе. Это единственная причина, по которой
    // здесь вообще есть таймер.
    LaunchedEffect(Unit) {
        while (true) {
            delay(ПОВТОРЫ_КАЖДЫЕ_МС)
            отправитель.проход()
        }
    }

    Стан(
        modifier = Modifier.fillMaxSize(),
        колонка = {
            ЭкранПереписок(
                состояние = состояниеСписка,
                onОткрыть = { куда = Куда.Переписка(it.chatId, it.title) },
                onНовая = { куда = Куда.Новая },
            )
        },
        главная = when (val текущее = куда) {
            Куда.Ничего -> null

            Куда.Новая -> {
                {
                    ЭкранНовойПереписки(
                        состояние = состояниеНовой,
                        onНомер = новая::номерИзменён,
                        onНайти = новая::найти,
                        onНазад = {
                            куда = Куда.Ничего
                            новая.сброс()
                        },
                    )
                }
            }

            is Куда.Переписка -> {
                {
                    Переписка(
                        окружение = окружение,
                        chatId = текущее.chatId,
                        // Имя из списка, если строка уже пришла потоком; иначе то, с чем
                        // переписку открыли. Пустое место читалось бы как поломка.
                        имя = состояниеСписка.chats.firstOrNull { it.chatId == текущее.chatId }?.title
                            ?: текущее.имя,
                        scope = scope,
                        onНазад = { куда = Куда.Ничего },
                    )
                }
            }
        },
    )
}

@Composable
private fun Переписка(
    окружение: Окружение,
    chatId: String,
    имя: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    onНазад: () -> Unit,
) {
    // Store живёт столько, сколько открыта переписка: ключ по chatId, чтобы при переходе в
    // другую он пересоздался, а не показал реплики предыдущей.
    val store = remember(chatId) {
        ChatStore(
            chatId = chatId,
            observe = окружение.переписка,
            send = окружение.отправка,
            scope = scope,
        )
    }
    val состояние by store.state.collectAsState()
    ЭкранЧата(
        состояние = состояние,
        собеседник = имя ?: "Без имени",
        onНабор = store::draftChanged,
        onОтправить = { store.sendPressed() },
        onНазад = onНазад,
        onЗакрытьСообщение = store::noticeDismissed,
    )
}

/**
 * Как часто проверять сроки повторов.
 *
 * Пять секунд — не «магическое число»: самый короткий срок повтора в очереди равен
 * секунде, а самый частый случай — «сеть вернулась». Чаще пяти секунд смысла нет, реже —
 * человек успевает заметить задержку.
 */
private const val ПОВТОРЫ_КАЖДЫЕ_МС = 5_000L
