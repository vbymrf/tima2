package io.tima.shared

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.remember
import io.tima.core.diag.Journal
import io.tima.core.notify.platformNotifier
import io.tima.core.notify.soundChoiceOf
import io.tima.core.notify.SoundKeys
import io.tima.feature.chat.BookView
import io.tima.core.diag.LogCode
import io.tima.core.database.TimaDatabase
import io.tima.domain.account.Session
import io.tima.core.encryption.DeviceTokenSignerOverKodium
import io.tima.core.encryption.deviceIdentityFrom

/**
 * Сборка приложения: где конкретные подсистемы соединяются в одно.
 *
 * ── ЗАЧЕМ ОТДЕЛЬНЫЙ ФАЙЛ ────────────────────────────────────────────────────
 *
 * `Root.kt` совмещал четыре обязанности: сборку подсистем, запуск фоновых циклов,
 * навигацию и сборку Store для каждого окна. Пока окон было три, это читалось; на
 * семи стало 598 строк и 46 импортов, и каждое новое окно правило тот же файл, что
 * и все остальные, — то есть очередь на merge там, где по смыслу пересечения нет.
 *
 * Здесь — только «кто из чего состоит». Ни навигации, ни `@Composable`-разметки, ни
 * циклов: они в [Корень] и [ФоновыеЦиклы] соответственно.
 */
class Assembled(
    /** Кто мы для сервера: нужен экранам, которым важен свой идентификатор. */
    val session: Session,
    val environment: Environment,
    val network: Network,
    val sender: Sender,
    /**
     * Второй проход очереди — групповой.
     *
     * Отдельно от [sender], потому что до отправки у них разное всё: личному нужны ключ
     * эпохи escrow и устройства собеседника, групповому — версия ключа группы либо
     * ничего, если сообщение открытое.
     */
    val groupSender: GroupSender,
    val receiver: Receiver,
    /**
     * Уведомления — У5.
     *
     * Здесь, а не в композиции: строку снимает и тот, кто открыл переписку (окно), и тот,
     * кто получил конец звонка (канал). Общее место у них одно — сборка.
     */
    val notices: Notices,
    val keyOrchestrator: GroupKeyOrchestrator,
    /**
     * Штампы отправителей из событий о сообщениях — потоком, по той же причине, что и
     * звонок ниже: карточки людей живут в `Root`, а канал — всё время работы.
     */
    val senderStamps: MutableSharedFlow<SenderStamp> = MutableSharedFlow(extraBufferCapacity = 64),
    /**
     * Звонок «под вашей записью ответили» (ADR-0024, следствие 5).
     *
     * Поток, а не обратный вызов: экран страницы появляется и исчезает, а канал живёт всё
     * время работы приложения. Отдавать ему ссылку на живой экран значило бы держать
     * закрытый экран в памяти ради события, которое ему уже некуда показать.
     *
     * Значение — номер записи, под которой ответили; каждое событие меняет его, и этого
     * достаточно, чтобы открытая страница перечиталась.
     */
    val commentPings: StateFlow<Long>,
    /**
     * Входящий звонок из канала: `callId|fromUserId|kind`, пусто — никто не звонит.
     *
     * Строкой, а не своим типом: сборка не должна знать устройства звонка, а тому, кто
     * его держит, довольно трёх слов сервера. Поток, а не событие: состояние переживает
     * пересборку экрана, а событие потерялось бы ровно в тот момент, когда звонят.
     */
    val callPings: StateFlow<String>,
    /**
     * Сервер отказался работать с этой сборкой: она ниже порога совместимости (О5).
     *
     * Поток, а не событие: ответ не меняется до обновления, и экран, пересобравшись,
     * обязан снова его увидеть. Разбудит он проверку версии — порог показывает она, и
     * два места, решающих «пора обновиться», разошлись бы на первой же правке.
     */
    val outdated: StateFlow<Boolean>,
)

/**
 * Готовая сборка для устройства — та же самая, что у процесса (У2).
 *
 * Сама сборка живёт в [ChannelHost], а не в композиции: канал обязан держаться и когда
 * окна нет вовсе, иначе уведомление приходит ровно тогда, когда оно не нужно.
 * `remember` здесь остаётся только затем, чтобы не спрашивать её на каждую перерисовку.
 */
@Composable
fun assemble(
    entry: Entry,
    device: Entry.Device,
    build: Build = Build(),
    deviceDatabase: (String) -> TimaDatabase,
    firstAccount: String? = null,
): Assembled = remember(device) {
    ChannelHost.assembled(device.session.deviceId) {
        buildAssembled(entry, device, build, deviceDatabase, firstAccount)
    }
}

/**
 * Собрать всё для заведённого устройства.
 *
 * **Не `@Composable` и не `remember`** — У2. Зовёт это [ChannelHost] ровно один раз на
 * устройство, и зовёт не только окно: службе, поднятой после перезагрузки телефона,
 * собирать всё это надо самой, а композиции у неё нет и не будет.
 */
fun buildAssembled(
    entry: Entry,
    device: Entry.Device,
    /** Чем сборка называет себя серверу: без этого «устарело» сказать нечем. */
    build: Build = Build(),
    /**
     * Открыть базу по **имени файла**. Имя считает общий код (`databaseFor`), а не
     * приложение: правило именования одно на все платформы, и два одинаковых правила в
     * двух приложениях однажды разошлись бы.
     */
    deviceDatabase: (String) -> TimaDatabase,
    /**
     * Первый заведённый аккаунт: за ним остаётся прежнее имя базы.
     *
     * Иначе человек, уже пользующийся приложением, после обновления открыл бы пустую
     * базу — переписка осталась бы в файле, которого никто больше не ищет.
     */
    firstAccount: String? = null,
): Assembled =
    run {
        val environment = Environment.open(
            deviceDatabase(databaseFor(device.session.userId, firstAccount)),
            device.secret,
            device.session.userId,
            device.session.deviceId,
        )
        // ── ВЕРНУТЬ В ОЧЕРЕДЬ ТО, ЧТО ЗАСТРЯЛО В ПРОШЛЫЙ РАЗ ────────────────
        //
        // Android убивает приложение когда захочет, в том числе посреди отправки. Запись
        // остаётся в `SENDING` или `SEALED`, а отправитель берёт только `QUEUED` — и она
        // не уходит **никогда**. Очередь при этом не пуста, и человек видит «ждёт».
        //
        // Машина для этого была написана, покрыта проверками и **никем не вызвана**:
        // `recoverOnStart` звал только тестовый стенд. Цена — realme 2026-09-23: одно
        // сообщение стояло пять суток, а журнал каждые пять секунд писал «повтор не
        // помог», не называя причины. В `Outbox` при этом прямо записано, что в v1 такое
        // сообщение «пропадало без следа для человека», — и v2 повторила это в точности,
        // потому что строки вызова не было.
        //
        // Место выбрано так, чтобы забыть было нельзя: сборка одна на аккаунт, и
        // `Assembled` без восстановленной очереди теперь не собирается.
        // ── И РОВНО ОДИН РАЗ НА ПРОЦЕСС ─────────────────────────────────────
        //
        // С У2 сборка кэшируется в `ChannelHost` и строится один раз на устройство за
        // жизнь процесса — то есть набор ниже стал почти избыточен. Почти: службу и окно
        // ничто не мешает поднять «одновременно», и тогда в кэш заглянут двое. Набор
        // держит обещание «ровно один раз» независимо от того, кто спросил первым.
        //
        // Восстановление отвечает на вопрос «что застряло, пока нас убили». Позови его
        // посреди жизни процесса — и оно выдернет из `SENDING` запись, которую прямо
        // сейчас отправляют: отправитель вернётся с результатом, а записи в `SENDING`
        // уже нет.
        //
        // Стоило падения при запуске на realme 2026-09-23 — в первый же день, когда
        // восстановление доехало до телефона: «результат для записи в состоянии QUEUED,
        // а попытка идёт только из SENDING». Два круга запуска видны в дневнике: дважды
        // `APP-START`, дважды сверка групп.
        //
        // Набор по устройству, а не один флаг: смена аккаунта — другое устройство и
        // другая очередь, и её восстановить надо.
        val recovered = if (recoveredDevices.add(device.session.deviceId)) {
            environment.queue.recoverOnStart()
        } else {
            0
        }
        if (recovered > 0) {
            Journal.note(
                LogCode.QUEUE_RECOVERED,
                "очередь восстановлена после обрыва",
                "вернулось" to recovered,
            )
        }

        val identity = deviceIdentityFrom(device.secret)

        // Подпись ключом ЭТОГО устройства — то, чем обновляется просроченный токен
        // (находка 2026-09-06). Ключ выводится из секрета, который здесь уже открыт:
        // второй путь к хранилищу означал бы второе место, где его можно потерять.
        val signer = DeviceTokenSignerOverKodium(identity)
        val network = Network.create(
            session = device.session,
            host = entry.host,
            sign = signer::sign,
            // Новый токен переживает перезапуск: иначе каждый запуск начинался бы с
            // обновления, а первый запрос до него — с 401.
            remember = { session -> entry.rememberSession(session) },
            build = build,
        )

        // Оркестр ключей собирается ЗДЕСЬ, а не внутри приёмника: ему нужны escrow,
        // крипта, сеть и хранилище разом — это работа сборки, а не канала.
        val keyOrchestrator = GroupKeyOrchestrator(
            environment = environment,
            network = network,
            identity = identity,
            msNow = ::msNow,
        )

        // Звонки о комментариях: канал кладёт сюда, страница читает. Заводится здесь,
        // потому что живёт столько же, сколько сборка, — а не столько, сколько экран.
        val senderStamps = MutableSharedFlow<SenderStamp>(extraBufferCapacity = 64)
        val commentPings = MutableStateFlow(0L)
        val callPings = MutableStateFlow("")
        val outdated = MutableStateFlow(false)

        // ── УВЕДОМЛЕНИЯ СОБИРАЮТСЯ ЗДЕСЬ, А НЕ В ОКНЕ (У5) ──────────────────
        //
        // Показ платформенный (`platformNotifier`), правила общие, а зовёт их приёмник —
        // тот, кто первым узнаёт о событии. Собери это в композиции, и уведомление
        // приходило бы только при открытом окне, то есть тогда, когда оно не нужно.
        val notices = Notices(
            notifier = platformNotifier(),
            me = device.session.userId,
            // Строка книги — из местной базы, без сети: уведомление не должно ждать
            // сервера, чтобы назвать знакомого.
            entryOf = { id -> environment.book.everyone().first().firstOrNull { it.userId == id } },
            // Карточка нужна ради НИКА незнакомца, и только. Не нашлась — строка
            // остаётся безымянной, и это правильнее выдуманного имени.
            cardOf = { id -> runCatching { network.directory.cards(listOf(id))?.get(id) }.getOrNull() },
            // Тот же порядок полей, что в списках: заказчик уже распространил
            // «Отображать пользователя как» на журнал звонков 2026-09-19.
            look = { BookView.from(environment.settings.all().first()).look() },
            // Выбор звуков — в настройках устройства, не синхронизируется (§4 плана).
            ringFor = { userId ->
                val all = environment.settings.all().first()
                all[SoundKeys.ringOf(userId)]?.takeIf { it.isNotBlank() }?.let(::soundChoiceOf)
                    ?: soundChoiceOf(all[SoundKeys.RING])
            },
            messageSound = { soundChoiceOf(environment.settings.all().first()[SoundKeys.MESSAGE]) },
        )

        Assembled(
            session = device.session,
            environment = environment,
            network = network,
            sender = Sender(
                environment = environment,
                network = network,
                session = device.session,
                identity = identity,
            ),
            groupSender = GroupSender(
                environment = environment,
                network = network,
                session = device.session,
                identity = identity,
                rotate = keyOrchestrator::rotate,
                stale = keyOrchestrator::keyStale,
            ),
            receiver = Receiver(
                environment = environment,
                network = network,
                session = device.session,
                identity = identity,
                keyOrchestrator = keyOrchestrator,
                onComment = { _, postId -> commentPings.value = postId },
                onCall = { callId, from, kind -> callPings.value = "$callId|$from|$kind" },
                // Со звонком что-то стало. Слово сервера нужно там, где до комнаты не
                // дошло: собеседник отклонил или не смог ответить, а движок SFU про это
                // не знает — в комнату никто не входил.
                //
                // ── КОНЕЦ ОПРЕДЕЛЯЕТСЯ ОТ ОБРАТНОГО ─────────────────────────
                //
                // Здесь стоял перечень «declined или ended», и он **пропускал `missed`** —
                // ровно то слово, которым сервер отвечает на неотвеченный звонок
                // (`calls.go`: состояние было `ringing`, значит `missed`). Собеседник
                // отказывался, сервер честно слал уведомление, клиент его выбрасывал — и
                // звонящий слушал «Звоним…» без конца. Поймано отчётами `PXE8` и `H48Q`
                // 2026-09-20: между `/end` у одного и ручной отменой у другого — двадцать
                // пять секунд тишины.
                //
                // Причём `declined` звонки не посылают вовсе: это слово из другого места
                // сервера. То есть перечень был неверен с обеих сторон сразу.
                //
                // Поэтому теперь наоборот: **продолжается только «answered»**, всё
                // остальное — конец. Свой перечень конечных состояний обещал бы, что мы
                // знаем их все, а сервер волен завести новое, и молчание было бы тем же.
                onCallState = { callId, state ->
                    when (state) {
                        // Мы и так знаем: это наш собственный ответ.
                        "answered" -> Unit
                        // **Ответили на другом нашем устройстве.** Окно надо закрыть, а
                        // звонок — НЕ трогать: он идёт, просто не здесь. Слово отдельное
                        // именно поэтому: попади оно в «конец», сосед оборвал бы живой
                        // разговор запросом `/end`, и выглядело бы это как беда связи.
                        "taken" -> callPings.value = "перехвачен|$callId|$state"
                        else -> callPings.value = "конец|$callId|$state"
                    }
                },
                // Причина конца едет тем же кадром: `busy` — собеседник занят другим
                // разговором, и сказал об этом его собственный телефон.
                // Ушедший из комнаты. Решает не приёмник: **кто именно ушёл**, знает
                // только тот, кто ведёт звонок, — в группе уход одного из пятерых
                // разговора не кончает. Сюда едет идентификатор, а выводы делает `Root`.
                onCallLeft = { callId, userId -> callPings.value = "ушёл|$callId|$userId" },
                onCallUnreachable = { callId -> callPings.value = "недоступен|$callId|-" },
                // Вызов дошёл до телефона собеседника — у звонящего «Звонит» (ВЗ0а).
                onCallDelivered = { callId -> callPings.value = "доставлен|$callId|-" },
                onStamp = { senderStamps.tryEmit(it) },
                onOutdated = { outdated.value = true },
                notices = notices,
            ),
            notices = notices,
            keyOrchestrator = keyOrchestrator,
            commentPings = commentPings,
            callPings = callPings,
            outdated = outdated,
            senderStamps = senderStamps,
        )
    }

/**
 * Устройства, очередь которых уже восстанавливали в этом процессе.
 *
 * Вне состава экрана намеренно: `remember` переживает пересборку, но не переживает смену
 * ключа, а нужно «один раз, пока жив процесс».
 */
private val recoveredDevices = mutableSetOf<String>()
