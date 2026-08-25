package io.tima.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import io.tima.core.encryption.AccountIdentitiesOverKodium
import io.tima.core.encryption.PersonalChatIdsOverKodium
import io.tima.core.encryption.deviceIdentityFrom
import io.tima.core.database.SqlChatBook
import io.tima.core.database.SqlGroupKeys
import io.tima.core.network.GroupKeyRecoveryOverHttp
import io.tima.core.network.GroupsOverHttp
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.ChatNames
import io.tima.domain.chat.CreateGroupChat
import io.tima.domain.chat.ManageGroupMembers
import io.tima.domain.chat.RequestGroupKeys
import io.tima.feature.group.НоваяГруппаStore
import io.tima.feature.group.СоставStore
import io.tima.feature.group.ЭкранНовойГруппы
import io.tima.feature.group.ЭкранСостава
import io.tima.core.database.TimaDatabase
import io.tima.core.ui.Стан
import io.tima.domain.account.Session
import io.tima.domain.chat.StartPersonalChat
import io.tima.feature.auth.AuthState
import io.tima.feature.auth.AuthStore
import io.tima.feature.auth.ПривязкаStore
import io.tima.feature.auth.ЭкранПривязки
import io.tima.feature.auth.УстройстваStore
import io.tima.feature.auth.ЭкранУстройств
import io.tima.feature.auth.ЭкранВхода
import io.tima.feature.chat.ChatStore
import io.tima.feature.chat.ChatsStore
import io.tima.feature.chat.НоваяПерепискаStore
import io.tima.feature.chat.ЭкранНовойПереписки
import io.tima.feature.chat.ЭкранПереписок
import io.tima.feature.chat.ЭкранЧата

/**
 * Корень приложения — общий для всех платформ.
 *
 * Платформенному входу остаётся окно (или Activity) и открытая база: **правил поведения
 * платформенных не бывает**, и держать их по копии на платформу — это ровно то, из-за чего
 * в v1 Android и Desktop разошлись молча.
 *
 * @param базаУстройства открыть базу этого устройства. На ПК это файл в каталоге данных, на
 *   Android — `androidDatabase(context, имя)`.
 *
 * Развилка ровно одна, и решает её **заведённое устройство**, а не флаг «вошли»: флаг живёт
 * в памяти и врёт после перезапуска. Заведённое означает сессию, а не только секрет —
 * секрет пишется до вызова сервера, и секрет без сессии это незаконченный вход.
 */
@Composable
fun Корень(
    вход: Вход,
    базаУстройства: () -> TimaDatabase,
    кодПривязки: String? = null,
    /** Номер сборки от платформы: общий код его знать не может и не должен. */
    версияСборки: String = "",
) {
    var устройство by remember { mutableStateOf(вход.заведённое()) }

    val текущее = устройство
    if (текущее == null) {
        // Код, пришедший на устройство без аккаунта, ничего не значит: подтверждать
        // привязку нечем — своего ключа у него нет. Показываем обычный вход, а не
        // сообщение о беде: человек, скорее всего, просто отсканировал код не тем
        // приложением.
        Вхождение(вход, версияСборки) { устройство = вход.заведённое() }
        return
    }

    // Сборка живёт в Сборка.kt: здесь навигация, а не «кто из чего состоит».
    val собранное = собрать(вход, текущее, базаУстройства)

    Приложение(собранное, вход.платформа, текущее.секрет, кодПривязки, версияСборки)
}

@Composable
private fun Вхождение(вход: Вход, версияСборки: String, onВошли: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember {
        AuthStore(
            register = вход.регистрация,
            identities = AccountIdentitiesOverKodium,
            scope = scope,
            link = вход.привязка,
            имяУстройства = вход.платформа.имяУстройства,
        )
    }
    val состояние by store.state.collectAsState()

    // Оба конечных состояния означают одно: устройство есть. «Готово» и «уже заведено»
    // различаются только тем, кто его завёл, а приложению дальше всё равно.
    if (состояние is AuthState.Готово || состояние is AuthState.УжеЗаведено) onВошли()

    ЭкранВхода(
        состояние = состояние,
        onНомер = store::номерИзменён,
        onКодСтраны = store::кодСтраныИзменён,
        onКод = store::кодИзменён,
        onЗапросить = store::запроситьКод,
        onПодтвердить = store::подтвердить,
        onНазад = store::назад,
        onФраза = store::фразаИзменена,
        onВойтиПоФразе = store::войтиПоФразе,
        onНачатьЗаново = store::начатьЗаново,
        onФразаСохранена = store::фразаСохранена,
        onПодключиться = store::подключиться,
        версияСборки = версияСборки,
    )
}

/** Что открыто в главной области. Вся навигация верхнего уровня — эти три случая. */
private sealed interface Куда {
    /** Ничего: на телефоне это список, на широком формате — пустая главная область. */
    data object Ничего : Куда

    /** Подокно «новая переписка». */
    data object Новая : Куда

    /** Подокно «новая группа». */
    data object НоваяГруппа : Куда

    /**
     * Подокно «участники группы».
     *
     * Открывается из окна переписки, а не из списка: состав — свойство открытой группы, и
     * попасть в него, не открыв её, значит спрашивать «чей состав».
     */
    data class Состав(val groupId: String, val имя: String?) : Куда

    /** Переписка. Имя хранится здесь, потому что строка в списке появится позже — потоком. */
    data class Переписка(val chatId: String, val имя: String?) : Куда

    /**
     * Подтверждение привязки нового устройства.
     *
     * Приходит **снаружи**: человек навёл штатную камеру на код, та увидела `tima://link/…`
     * и открыла нас. Своего сканера у нас поэтому нет вовсе — и не нужно: чужой уже стоит
     * на каждом телефоне, а свой потребовал бы доступа к камере и объяснений, зачем он.
     */
    data class Привязка(val код: String) : Куда

    /**
     * Свои устройства.
     *
     * Открывается кнопкой настроек в шапке, и это **не подмена**: настроек как экрана
     * (`doc_UI/25`) пока нет, а из всех их разделов существует ровно один — устройства.
     * Экран назван своим именем, поэтому человек видит, куда попал; когда разделов станет
     * больше, между ними встанет список разделов.
     */
    data object Устройства : Куда
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
    собранное: Собранное,
    платформа: Платформа,
    секретУстройства: ByteArray,
    кодПривязки: String?,
    /** Номер сборки — показывается в «Устройствах», см. пояснение там. */
    версияСборки: String,
) {
    val окружение = собранное.окружение
    val сеть = собранное.сеть
    val сессия = собранное.сессия
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

    // Код снаружи открывает подтверждение поверх всего: человек только что навёл камеру и
    // ждёт ответа именно на это.
    LaunchedEffect(кодПривязки) {
        кодПривязки?.let { куда = Куда.Привязка(it) }
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

    // Фоновые циклы — в своём файле: это политика времени, а не навигация.
    ФоновыеЦиклы(собранное, платформа, признакИзменений = состояниеСписка)

    Стан(
        modifier = Modifier.fillMaxSize(),
        колонка = {
            ЭкранПереписок(
                состояние = состояниеСписка,
                onОткрыть = { куда = Куда.Переписка(it.chatId, it.title) },
                onНовая = { куда = Куда.Новая },
                onНоваяГруппа = { куда = Куда.НоваяГруппа },
                onНастройки = { куда = Куда.Устройства },
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

            Куда.Устройства -> {
                {
                    Устройства(
                        сеть = сеть,
                        scope = scope,
                        версияСборки = версияСборки,
                        onНазад = { куда = Куда.Ничего },
                    )
                }
            }

            is Куда.Привязка -> {
                {
                    ПодтверждениеПривязки(
                        сеть = сеть,
                        секретУстройства = секретУстройства,
                        код = текущее.код,
                        scope = scope,
                        onЗакрыть = { куда = Куда.Ничего },
                    )
                }
            }

            is Куда.Переписка -> {
                {
                    Переписка(
                        окружение = окружение,
                        сеть = сеть,
                        chatId = текущее.chatId,
                        // Имя из списка, если строка уже пришла потоком; иначе то, с чем
                        // переписку открыли. Пустое место читалось бы как поломка.
                        имя = состояниеСписка.chats.firstOrNull { it.chatId == текущее.chatId }?.title
                            ?: текущее.имя,
                        scope = scope,
                        onНазад = { куда = Куда.Ничего },
                        onСостав = { куда = Куда.Состав(текущее.chatId, текущее.имя) },
                    )
                }
            }

            Куда.НоваяГруппа -> {
                {
                    НоваяГруппа(
                        окружение = окружение,
                        сеть = сеть,
                        scope = scope,
                        onНазад = { куда = Куда.Ничего },
                        onСоздана = { groupId, название -> куда = Куда.Переписка(groupId, название) },
                    )
                }
            }

            is Куда.Состав -> {
                {
                    Состав(
                        окружение = окружение,
                        сеть = сеть,
                        сессия = сессия,
                        groupId = текущее.groupId,
                        scope = scope,
                        onНазад = { куда = Куда.Переписка(текущее.groupId, текущее.имя) },
                    )
                }
            }
        },
    )
}

@Composable
private fun Переписка(
    окружение: Окружение,
    сеть: ПортыПереписок,
    chatId: String,
    имя: String?,
    scope: kotlinx.coroutines.CoroutineScope,
    onНазад: () -> Unit,
    onСостав: () -> Unit,
) {
    // Групповая ли переписка — решает столбец `kind`, а не догадка по идентификатору.
    // От этого зависит трое: показывать ли автора у реплик, спрашивать ли имена и есть ли
    // вход в состав.
    val группа = remember(chatId) {
        окружение.фактыПереписок.kindOf(chatId) == ChatKind.Group
    }
    // Store живёт столько, сколько открыта переписка: ключ по chatId, чтобы при переходе в
    // другую он пересоздался, а не показал реплики предыдущей.
    val store = remember(chatId) {
        ChatStore(
            chatId = chatId,
            observe = окружение.переписка,
            send = окружение.отправка,
            scope = scope,
            markRead = окружение.прочтение,
            // Запрос недостающего ключа и имена авторов — только у группы: у личной
            // переписки просить не у кого, а собеседник назван в шапке.
            requestKeys = if (группа) {
                RequestGroupKeys(GroupKeyRecoveryOverHttp(сеть.восстановлениеКлючей))
            } else {
                null
            },
            names = if (группа) {
                ChatNames { userId -> сеть.справочник.имяИлиНомер(userId) ?: userId }
            } else {
                null
            },
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
        onЗапроситьКлюч = store::запроситьКлюч,
        onФраза = store::фразаИзменена,
        onСостав = if (группа) onСостав else null,
    )
}


/**
 * Подтверждение привязки: экран и его Store.
 *
 * Store живёт столько, сколько открыт экран, и ключом ему служит сам код: другой код —
 * другое устройство, и остатки прежнего состояния тут были бы опасны.
 */
@Composable
private fun ПодтверждениеПривязки(
    сеть: ПортыУстройств,
    секретУстройства: ByteArray,
    код: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onЗакрыть: () -> Unit,
) {
    val store = remember(код) {
        ПривязкаStore(
            confirm = сеть.подтверждениеПривязки(deviceIdentityFrom(секретУстройства)),
            scope = scope,
            код = код,
        )
    }
    val состояние by store.state.collectAsState()
    ЭкранПривязки(состояние = состояние, onДоверить = store::доверить, onОтмена = onЗакрыть)
}

/** Свои устройства: список, отключение и вопрос перед ним. */
@Composable
private fun Устройства(
    сеть: ПортыУстройств,
    scope: kotlinx.coroutines.CoroutineScope,
    версияСборки: String,
    onНазад: () -> Unit,
) {
    val store = remember { УстройстваStore(сеть.мойПарк, scope) }
    val состояние by store.state.collectAsState()
    ЭкранУстройств(
        состояние = состояние,
        onНазад = onНазад,
        onСпросить = store::спросить,
        onПодтвердить = store::отключить,
        onПередумал = store::передумал,
        версияСборки = версияСборки,
    )
}

/**
 * Новая группа: подокно создания.
 *
 * Собирается здесь, потому что случай использования требует троих сразу — групп на
 * сервере, справочника и книги переписок. Домен их объявляет, а сводит приложение.
 */
@Composable
private fun НоваяГруппа(
    окружение: Окружение,
    сеть: ПортыГрупп,
    scope: kotlinx.coroutines.CoroutineScope,
    onНазад: () -> Unit,
    onСоздана: (String, String) -> Unit,
) {
    val store = remember {
        НоваяГруппаStore(
            создание = CreateGroupChat(
                groups = GroupsOverHttp(сеть.группы),
                directory = сеть.справочник,
                chats = SqlChatBook(окружение.db, окружение.шифр),
            ),
            scope = scope,
        )
    }
    val состояние by store.state.collectAsState()

    // Группа создана — открываем её. Оставаться на экране создания нечем: он своё сделал,
    // а человек ждёт переписку, а не подтверждение.
    LaunchedEffect(состояние.создана) {
        состояние.создана?.let { groupId ->
            // Непозванные показываются на самом экране; если они есть, переход не спешим
            // делать — иначе список номеров мелькнёт и исчезнет.
            if (состояние.непозванные.isEmpty()) {
                onСоздана(groupId, состояние.название)
                store.сброс()
            }
        }
    }

    ЭкранНовойГруппы(
        состояние = состояние,
        onНазвание = store::названиеИзменено,
        onНомер = store::номерИзменён,
        onДобавитьНомер = store::добавитьНомер,
        onУбратьНомер = store::убратьНомер,
        onСоздать = store::создать,
        onНазад = {
            onНазад()
            store.сброс()
        },
    )
}

/**
 * Состав группы: подокно участников.
 *
 * Ротация ключа при смене состава собирается здесь же — ей нужны escrow, крипта, сеть и
 * хранилище разом, то есть ровно то, чего домен не видит.
 */
@Composable
private fun Состав(
    окружение: Окружение,
    сеть: ПортыГрупп,
    сессия: Session,
    groupId: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onНазад: () -> Unit,
) {
    val store = remember(groupId) {
        СоставStore(
            участники = ManageGroupMembers(
                groups = GroupsOverHttp(сеть.группы),
                directory = сеть.справочник,
                rotator = РотацияГрупповогоКлюча(
                    группы = сеть.группы,
                    ключиУстройств = сеть.ключи,
                    escrow = сеть.escrow,
                    ключиГрупп = сеть.ключиГрупп,
                    книга = SqlGroupKeys(окружение.db, окружение.шифр),
                    сейчасМс = ::сейчасМс,
                ),
            ),
            groupId = groupId,
            myUserId = сессия.userId,
            scope = scope,
        )
    }
    val состояние by store.state.collectAsState()

    // Состав спрашивается при открытии: он меняется чужими руками, и показывать
    // вчерашний список значит показывать неправду.
    LaunchedEffect(groupId) { store.обновить() }

    ЭкранСостава(
        состояние = состояние,
        onНомер = store::номерИзменён,
        onПозвать = store::позвать,
        onИсключить = store::исключить,
        onНазад = onНазад,
    )
}
