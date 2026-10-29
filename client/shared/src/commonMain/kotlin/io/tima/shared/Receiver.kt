package io.tima.shared

import io.tima.core.network.HistoryApi
import io.tima.core.network.GroupMessagesApi
import io.tima.core.network.GroupsResult
import io.tima.core.encryption.HistoryFrame
import io.tima.core.database.SqlChatBook
import io.tima.core.encryption.GroupMessages
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.coroutines.sync.withLock
import io.tima.core.network.EventStreamProtocol
import io.tima.core.network.GroupFrame
import io.tima.core.network.GroupsOverHttp
import io.tima.core.network.LinkState
import io.tima.core.network.NetworkState
import io.tima.core.network.NetworkWatches
import io.tima.core.network.StreamOutcome
import io.tima.core.network.classifyFailure
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.PersonalChatIdsOverKodium
import io.tima.core.encryption.PersonalMessages
import io.tima.core.network.DeviceKeysResult
import io.tima.core.network.EventStream
import io.tima.core.outbox.IncomingEntry
import io.tima.core.outbox.OpenOutcome
import kotlinx.coroutines.flow.getAndUpdate
import io.tima.domain.account.Session
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.domain.chat.AutoReplyBlocked
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.SyncGroupsStep
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.SyncGroupChats
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Приём по живому каналу.
 *
 * ── ПОРЯДОК, КОТОРЫЙ ВАЖНЕЕ КОДА ────────────────────────────────────────────
 *
 * 1. **Конверт записывается ДО попытки разбора.** Разбор падает по любой причине — нет
 *    ключа, повреждённые байты, ошибка в нашем коде, — и если сначала разбирать, а
 *    записывать потом, каждое такое падение теряет сообщение безвозвратно: живой канал его
 *    больше не пришлёт. Этим занимается [io.tima.core.outbox.Inbox], здесь только вызовы в
 *    правильном порядке.
 * 2. **Имя отправителя из конверта — подсказка, а не утверждение.** Оно лежит в открытой
 *    части конверта, и до проверки подписи ему верить нельзя. Пользуемся им ровно для
 *    одного: какой ключ спрашивать у сервера. Соври отправитель чужим именем — подпись не
 *    сойдётся, и сообщение станет нечитаемым, а не «чужим».
 * 3. **Переписка от незнакомого номера всё равно появляется.** Строка `chats` заводится по
 *    отправителю: без неё в списке была бы переписка без имени — а человеку надо видеть,
 *    кто написал.
 *
 * **Переподключение решает вызывающий, а не канал.** [EventStream] возвращает исход и
 * заканчивается; политика повторов — здесь, потому что здесь известно, сколько ждать.
 */
class Receiver(
    private val environment: Environment,
    private val network: Network,
    private val session: Session,
    private val identity: DeviceIdentity,
    private val keyOrchestrator: GroupKeyOrchestrator,
    /** «Доставлено», «прочитано», «печатает», «в сети» (ПЛАН-(ОП)); `null` — их нет (проверки). */
    private val liveStates: LiveStates? = null,
    /**
     * Кому сказать, что под записью ответили: `(channelId, postId)`.
     *
     * Приёмник не знает ни экранов, ни состояний — он только приносит. По умолчанию
     * ничего: канал обязан работать и там, где страницы на экране нет.
     */
    private val onComment: (String, Long) -> Unit = { _, _ -> },
    /**
     * Копия аккаунта изменилась на другом устройстве: `(вид, ревизия)` (ЖУ9). Приёмник
     * только приносит номер; забирать ли — решает тот, кто держит копию.
     */
    private val onStoreChanged: (String, Long) -> Unit = { _, _ -> },
    /**
     * Нам звонят: `(callId, fromUserId, kind)`.
     *
     * Приёмник только приносит — кто этот человек и что показать, решает тот, кто держит
     * звонок. По умолчанию ничего: канал обязан работать и там, где звонить нечем (ПК).
     */
    private val onCall: (String, String, String) -> Unit = { _, _, _ -> },
    /** Со звонком что-то стало: `(callId, state)` — словом сервера. */
    private val onCallState: (String, String) -> Unit = { _, _ -> },
    /** Кто-то вышел из комнаты звонка: идентификатор звонка и ушедшего. */
    private val onCallLeft: (String, String) -> Unit = { _, _ -> },
    /**
     * Вызов не забрало ни одно устройство собеседника: `(callId)`.
     *
     * Не конец звонка — слово о том, что сейчас никого нет на связи.
     */
    private val onCallUnreachable: (String) -> Unit = { _ -> },
    /** Вызов дошёл до телефона собеседника — у звонящего «Звонит» (ВЗ0а). */
    private val onCallDelivered: (String) -> Unit = { _ -> },
    /**
     * Зовут в групповой звонок (ПЛАН-(ГЗ)-ГРУППОВЫХ-ЗВОНКОВ ГЗ3): `(callId, fromUserId, kind,
     * groupId)`. Отдельно от [onCall]: принять его — войти в звонок группы, а не ответить.
     */
    private val onGroupCall: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    /** Создатель группового звонка скомандовал: `(callId, action, by)`. */
    private val onCallControl: (String, String, String) -> Unit = { _, _, _ -> },
    /**
     * В группе начался или кончился звонок, или временная группа удалена: `(groupId,
     * state)` — `live`, `ended`, `deleted`. Полоса «Идёт звонок» и список групп.
     */
    private val onGroupEvent: (String, String) -> Unit = { _, _ -> },
    /** Временные группы звонка и когда удалятся — «удалится через N ч» (решение 11). */
    private val onCallGroups: (Map<String, Long>) -> Unit = {},
    /** Создатели временных групп звонка — из той же сверки групп. */
    private val onCallOwners: (Map<String, String>) -> Unit = {},
    /** С номера начали заново (ДУ6) — событие «это вы? отменить». */
    private val onIdentityReplaced: () -> Unit = {},
    /** К своей личности добавилось другое устройство — платформа (Р48). */
    private val onDeviceAdded: (String) -> Unit = {},
    /** Перерегистрация (ДУ9): извещение стороне. */
    private val onRereg: (EventStreamProtocol.Decision.Rereg) -> Unit = {},
    /** Копия ключей (модель Matrix, М2): открытое сообщение — в копию. */
    private val keyCopy: KeyCopyService? = null,
    /** Заявка новой личности в группу — решать владельцу или модератору (ДУ6). */
    private val onIdentityClaim: (String) -> Unit = {},
    /**
     * Своё устройство передало историю переписки (ИУ3). Забирать её здесь нельзя: страниц
     * может быть много, а канал всё это время стоял бы.
     */
    private val onHistoryReady: (String) -> Unit = {},
    /** Просьба о ключах переписки (`recovery.msg_request`) — ответ после проверки заверения. */
    private val chatKeyHelper: ChatKeyHelper? = null,
    /** Словарь — ссылкой: строки звонка пишутся словами на момент события. */
    private val words: () -> Words = { CurrentWords.value },
    /**
     * Штамп отправителя из обёртки события (сервер 0052/0053): кто, счётчик его профиля,
     * группа и цвет. Наружу, а не в базу: это подсказка карточкам людей, а не сообщение.
     */
    private val onStamp: (SenderStamp) -> Unit = {},
    /**
     * Сервер отказался работать с этой сборкой: она ниже порога совместимости.
     *
     * Наружу, а не решение здесь: приёмник экранов не знает. Тот, кто знает, поднимет
     * порог обновления — он и так умеет это показывать.
     */
    private val onOutdated: () -> Unit = {},
    /**
     * Уведомления — У5…У8. По умолчанию не показывает ничего: канал обязан работать и
     * там, где показывать нечем (проверки, платформы без уведомлений).
     */
    private val notices: Notices? = null,
) {

    /**
     * Файлы почтальона (Решения §6): забрать сразу при получении, загрузить снова по просьбе,
     * отметить «получатель забрал». Своя область: забор идёт в фоне и не держит разбор очереди.
     */
    val postman = PostmanFiles(
        media = { network.media },
        folder = environment.media,
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
        // «Качать файлы по мобильной сети» («Память и трафик», заказчик 2026-10-22): по умолчанию да.
        mayDownload = {
            io.tima.core.network.NetworkWatches.current.kind() != "mobile" ||
                environment.settings.all().first()[PostmanFiles.MOBILE_KEY] != "0"
        },
    ).also { files ->
        // Сеть сменилась или появилась после потери — отложенное до сети без счёта трафика забирается.
        val watch = io.tima.core.network.NetworkWatches.current
        val around = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        around.launch { watch.switched.collect { files.resume() } }
        around.launch { watch.state.collect { if (it == io.tima.core.network.NetworkState.AVAILABLE) files.resume() } }
    }

    /**
     * Кого человек заблокировал — по `user_id` (Л8, Л9, Л17).
     *
     * Спрашивается у книги на каждом событии, а не держится в поле: блокировка меняется
     * человеком, и устаревший список означал бы, что разблокированный молчит до
     * перезапуска. Запрос местный, к маленькой таблице, а события приходят со скоростью
     * человека, а не сети.
     */
    private suspend fun blocked(): Set<String> =
        runCatching { environment.book.blocked().first() }.getOrDefault(emptySet())

    /** Строка книги про человека; `null` — его в книге нет вовсе. */
    private suspend fun bookRow(userId: String): BookEntry? = runCatching {
        environment.book.everyone().first().firstOrNull { it.userId == userId }
    }.getOrNull()

    /** Автоответ заблокированному — Л14. Правила целиком живут в [AutoReplyBlocked]. */
    private val autoReply = AutoReplyBlocked(
        facts = environment.chatFacts,
        send = environment.send,
        nowMs = { msNow() },
    )

    /** Переписки заблокированных: их конверты записываются, но не открываются (Л9). */
    private suspend fun heldChats(): List<String> = blocked()
        .map { PersonalChatIdsOverKodium.personalChatId(session.userId, it) }

    /** Что случилось с каналом в последний раз. Для диагностики, не для решений. */
    var lastOutcome: String? = null

    /** Канал сейчас открыт — для сторожа попытки. */
    @kotlin.concurrent.Volatile
    private var live = false
        private set

    /**
     * Ключи подписи по устройству отправителя: память поверх местной базы (план КК, КК13 — «ключи подписи
     * хранить»). В сеть — один раз на устройство, и с отозванными.
     */
    private val senderKeys = HashMap<String, io.tima.core.database.SenderKey>()

    /** Ключи личных сообщений на устройстве (КК9): источник для своих устройств и копии. */
    private val messageKeys = io.tima.core.database.SqlMessageKeys(environment.db, environment.cipher)

    /** Ключи подписи на устройстве: переживают перезапуск и отзыв устройства автора. */
    private val storedKeys = io.tima.core.database.SqlSenderKeys(environment.db)

    /**
     * Устройства, о которых сервер ответил, но ключа не дал вовсе — ни среди действующих, ни среди отозванных:
     * строка устройства стёрта. Их сообщения открываются без проверки подписи с пометкой «Не подтверждено»
     * (Р10). Промах сети сюда не попадает: тогда сообщение ждёт, как раньше.
     */
    private val absentSenders = HashSet<String>()

    private val book = SqlChatBook(environment.db, environment.cipher)

    /**
     * Сверка групп с сервером — за настоящим названием. До 2026-09-18 она не была
     * подключена никуда: группа, куда меня позвали, узнавалась по первому сообщению и
     * навсегда оставалась «Группа», хотя у сервера название было. Так и разошлись шапки
     * «gruppa» у создателя и «Группа» у приглашённого.
     */
    private val groupsSync = SyncGroupChats(GroupsOverHttp(network.groups), book)

    // ── Групповые ключи ──────────────────────────────────────────────────────
    //
    // Собираются НЕ здесь: канал только приносит кадры, а выполняет их работа, которой
    // нужны escrow, крипта, сеть и хранилище разом. Приёмник получает готовый оркестр.
    private val groupKeys get() = keyOrchestrator.keys

    /**
     * Держать канал, пока приложение живо.
     *
     * Бесконечный цикл здесь на месте: канал — это и есть «пока живо». Пауза между
     * попытками берётся из состояния связи, а не из общего «подождём пять секунд».
     */
    /**
     * Кадр звонка из канала.
     *
     * Ничего не решает и никуда не ходит: звонок — событие живое, и вся работа по нему у
     * того, кто им владеет. Приёмник лишь переносит слова сервера наружу.
     */
    private suspend fun aboutCall(decision: EventStreamProtocol.Decision) {
        when (decision) {
            // ── ЗВОНОК ОТ ЗАБЛОКИРОВАННОГО МОЛЧИТ (Л17) ─────────────────────
            //
            // Молчит **телефон**, а не сервер: вызов доходит, строка в журнале звонков
            // остаётся. Блокировка прячет, а не отменяет — то же правило, по которому
            // сохраняется переписка. Не ответив, мы оставляем звонок в пропущенных, и
            // разблокировав, человек увидит, что ему звонили.
            //
            // Отдельного слова «вас заблокировали» звонящему нет и заводить его не надо:
            // автоответ в переписке (Л14) уже делает это, а второе такое место было бы
            // вторым оракулом для рассыльщика.
            is EventStreamProtocol.Decision.CallIncoming ->
                if (decision.from in blocked()) {
                    Journal.note(LogCode.NET_CHANNEL, "звонок от заблокированного — не звоним", "звонок" to decision.callId)
                } else {
                    // Имя сразу: его утверждает сервер, а не звонящий (У7).
                    notices?.calling(decision.callId, decision.from)
                    onCall(decision.callId, decision.from, decision.kind)
                }
            // ── ПОДСКАЗКА РАЗРЕШАЕТСЯ РУЧКОЙ ────────────────────────────────
            //
            // Кадр вызова больше не несёт состояния — несёт идентификатор. Спросить
            // обязан клиент, и это единственное место, где он это делает: ответ ручки
            // верен на момент вопроса, а тело было верным на момент записи.
            //
            // Три исхода, и все три названы:
            //   `null`       — до сервера не дошли. Молчим: сказать «кончился» значило
            //                  бы не позвонить человеку из-за моргнувшей сети.
            //   не `ringing` — звонок уже кончился, пока подсказка ехала. Ровно тот
            //                  класс бед, ради которого подсказка и заведена.
            //   `ringing`    — звоним, и теми же словами, что раньше слал сервер.
            is EventStreamProtocol.Decision.CallPoke -> {
                val call = network.calls.snapshot(decision.callId)
                when {
                    call == null ->
                        Journal.note(LogCode.NET_CHANNEL, "подсказка о звонке: сервер не ответил", "звонок" to decision.callId)
                    !call.ringing ->
                        Journal.note(LogCode.NET_CHANNEL, "подсказка о звонке опоздала", "состояние" to call.state)
                    call.initiatorId in blocked() ->
                        Journal.note(LogCode.NET_CHANNEL, "звонок от заблокированного — не звоним", "звонок" to decision.callId)
                    call.group -> {
                        notices?.calling(decision.callId, call.initiatorId, call.video)
                        onGroupCall(decision.callId, call.initiatorId, if (call.video) "video" else "audio", call.groupId)
                    }
                    else -> {
                        notices?.calling(decision.callId, call.initiatorId)
                        onCall(decision.callId, call.initiatorId, if (call.video) "video" else "audio")
                    }
                }
            }
            is EventStreamProtocol.Decision.CallControl ->
                onCallControl(decision.callId, decision.action, decision.by)
            is EventStreamProtocol.Decision.GroupCall -> {
                onGroupEvent(decision.groupId, decision.state)
                callLine(decision)
                // Звонок двигает срок временной группы — «удалится через» обновляется; и
                // позванный узнаёт, что группа временная, — она встаёт в «Чаты».
                syncGroups()
            }
            is EventStreamProtocol.Decision.GroupDeleted -> {
                wipeGroup(decision.groupId)
                onGroupEvent(decision.groupId, "deleted")
            }
            is EventStreamProtocol.Decision.IdentityReplaced -> {
                Journal.trouble(LogCode.DEVICE_TRUST, "с номера аккаунта начали заново", "новая" to decision.newUserId.take(8))
                onIdentityReplaced()
            }
            is EventStreamProtocol.Decision.DeviceAdded -> {
                // Своё событие о себе не показываем: новое устройство и так знает, что оно новое.
                if (decision.deviceId != session.deviceId) {
                    Journal.note(LogCode.DEVICE_TRUST, "к аккаунту добавлено устройство", "устройство" to decision.deviceId.take(8), "платформа" to decision.platform)
                    onDeviceAdded(decision.platform)
                }
            }
            is EventStreamProtocol.Decision.Rereg -> {
                Journal.note(LogCode.DEVICE_TRUST, "перерегистрация", "что" to decision.kind, "исход" to decision.outcome.ifEmpty { "—" })
                onRereg(decision)
            }
            is EventStreamProtocol.Decision.IdentityClaim -> {
                Journal.note(LogCode.DEVICE_TRUST, "заявка новой личности в группу", "группа" to decision.groupId.take(8))
                onIdentityClaim(decision.groupId)
            }
            is EventStreamProtocol.Decision.HistoryReady -> {
                Journal.note(LogCode.DEVICE_TRUST, "своё устройство передало историю", "переписка" to decision.chatId.take(8))
                onHistoryReady(decision.chatId)
            }
            is EventStreamProtocol.Decision.MsgRequest -> {
                Journal.note(LogCode.DEVICE_TRUST, "просьба о ключах переписки", "переписка" to decision.chatId.take(8), "своё" to decision.own)
                // Не в канале: перезаворачивание — страницы истории и запросы, канал всё это
                // время стоял бы.
                chatKeyHelper?.later(decision)
            }
            is EventStreamProtocol.Decision.CallState -> {
                // Строка звонка не переживает звонок — чем бы он ни кончился (У7).
                // «answered» тоже конец для уведомления: трубку взяли, звать больше
                // некуда, а висящая строка выглядит как второй звонок.
                notices?.callOver(decision.callId)
                onCallState(decision.callId, decision.state)
            }
            is EventStreamProtocol.Decision.CallLeft ->
                onCallLeft(decision.callId, decision.userId)
            is EventStreamProtocol.Decision.CallUnreachable ->
                onCallUnreachable(decision.callId)
            else -> Unit
        }
    }

    /**
     * Сколько ждать перед новой попыткой — У11.
     *
     * Основание берётся у состояния связи, а рост — от числа неудач подряд: одно
     * мигание сети и стена у оператора выглядят одинаково ровно первую секунду, и
     * различает их только то, что стена не проходит.
     *
     * Потолок не опускает того, что сказало состояние: у `BLOCKED` своя пауза больше
     * потолка, и урезать её значило бы вернуться к долблению в стену.
     */
    /**
     * Один подъём живого канала — до его конца.
     *
     * Вынесен из [hold], чтобы тот читался как политика: когда поднимать, когда рвать,
     * сколько ждать. Здесь — только что делать с тем, что канал принёс.
     */
    /**
     * Лента звонков — ВЗ0а. Сервер сказал вершину (приветствием или подсказкой); забираем
     * всё после своего номера, применяем по порядку и возвращаем, до чего применили, —
     * это канал и подтвердит.
     *
     * `null` — подтверждать нечего: нового нет или до сервера не дошли. Номер при неудаче
     * не двигается: следующая подсказка или переподключение спросят с того же места.
     */
    internal suspend fun callsTop(top: Long): Long? = callsLock.withLock { callsTopOnce(top) }

    /**
     * Проходы ленты — **по одному** (ЖУ0). 2026-09-30 на ПК приветствие канала и подсказка
     * запустили два прохода разом, оба от нуля, — и те же пропущенные уведомлялись дважды.
     */
    private val callsLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun callsTopOnce(top: Long): Long? {
        // Раньше ленты: звонок, который уже звонит, лента после перезапуска не принесёт.
        if (!ringingAsked) ringingAsked = ringingNow()
        val saved = runCatching { environment.settings.all().first() }.getOrDefault(emptyMap())
        // ── НОМЕР ЛЕНТЫ — УСТРОЙСТВА, А НЕ АККАУНТА (ЖУ0) ────────────────────
        //
        // Лента звонков своя у каждого устройства. Номер лежал в настройках аккаунта и
        // пережил повторный вход (ПК 2026-09-30: свой 26 при вершине 23) — и каждое новое
        // событие начинало ленту «заново», с разрывом и всеми старыми пропущенными. Прежний
        // общий ключ берётся, только если он не выше вершины: значит, писало это устройство.
        val legacy = saved[CALLS_CTS]?.toLongOrNull()?.takeIf { it <= top }
        val own = saved[ctsKey()]?.toLongOrNull() ?: legacy ?: 0
        var mine = callsFrom(top, own) { network.calls.updates(own)?.top } ?: return null
        var mark = saved[markKey()]?.toLongOrNull() ?: (if (legacy != null) saved[CALLS_MISSED_MARK]?.toLongOrNull() else null) ?: 0
        var applied: Long? = null
        while (true) {
            val page = network.calls.updates(mine)
            if (page == null) {
                Journal.trouble(LogCode.CALL, "ленту звонков не забрали — сервер не ответил", "после" to mine)
                return applied
            }
            if (page.gap) {
                // Начало ленты вычищено (живёт сутки): пропущенные — из журнала звонков.
                Journal.note(LogCode.CALL, "разрыв ленты звонков — пропущенные из журнала", "после" to mine)
                mark = missedFromHistory(mark)
            }
            for (action in CallLedger.actions(session.userId, page.updates)) apply(action)
            page.updates.maxOfOrNull { it.atMs }?.let { if (it > mark) mark = it }
            val reached = page.updates.lastOrNull()?.cts ?: page.top
            if (reached > mine) mine = reached
            applied = mine
            runCatching {
                environment.settings.put(ctsKey(), mine.toString())
                environment.settings.put(markKey(), mark.toString())
            }
            if (!page.more || page.updates.isEmpty()) break
        }
        Journal.note(LogCode.CALL, "лента звонков применена", "до" to mine)
        return applied
    }

    /** Когда это устройство вошло в аккаунт — см. [deviceSince]; читается один раз. */
    private var sinceCached: Long? = null

    /** Спросили ли в этом процессе, не звонят ли прямо сейчас. См. [ringingNow]. */
    private var ringingAsked = false

    /**
     * Входящий, который звонит прямо сейчас, — при первом подъёме канала в этом процессе
     * (заказчик 2026-09-27).
     *
     * **Лента такой звонок после перезапуска не принесёт.** Номер ленты сохраняется, как
     * только событие «звонят» применено, — а применено оно до того, как человек успел
     * ответить. Процесс умер между этими двумя мгновениями (Redmi 2026-09-27: вылет в
     * секунду входящего), и после запуска лента продолжает **после** звонка: он звонит
     * ещё пятнадцать секунд, а телефон о нём не знает, и строка уведомления от мёртвого
     * процесса ведёт в никуда.
     *
     * Спрашивается обычный журнал звонков — у каждой строки есть состояние, и `ringing`
     * значит ровно «звонят сейчас». Повтор не опасен: тот же звонок, пришедший и лентой,
     * `CallHost.ring` узнаёт и второй раз не показывает.
     *
     * Старше двух сроков звонка — не звоним: строку закрывает уборщик сервера, и
     * переживший его перезапуск `ringing` означал бы звонок в пустую комнату.
     *
     * @return `false` — до сервера не дошли; спросим при следующем подъёме.
     */
    private suspend fun ringingNow(): Boolean {
        val page = network.callHistory.page(limit = RINGING_PAGE) ?: return false
        for (ring in CallLedger.stillRinging(session.userId, page.records, msNow(), RINGING_FRESH_MS)) {
            Journal.note(LogCode.CALL, "входящий подхвачен при запуске — ещё звонит", "звонок" to ring.callId.take(8))
            apply(ring)
        }
        return true
    }

    /**
     * Пропущенные из журнала звонков — когда лента не помнит так далеко (разрыв).
     *
     * **Только уведомление, звонить нельзя**: из журнала приходят законченные, а идущий
     * звонок придёт свежей подсказкой. Отметка [mark] не даёт показать один пропущенный
     * дважды: в журнале номера нет, и без неё после каждого разрыва человек получал бы
     * все пропущенные заново.
     */
    private suspend fun missedFromHistory(mark: Long): Long {
        val page = network.callHistory.page(limit = MISSED_PAGE) ?: return mark
        var newest = mark
        val me = session.userId
        // Звонки старше входа этого устройства в аккаунт не уведомляются (ЖУ0): их
        // пропустили задолго до того, как устройство появилось, — остаются журналом звонков.
        val since = deviceSince()
        var older = 0
        for (record in page.records) {
            if (record.createdAt <= mark || record.outcome(me) != io.tima.domain.chat.CallOutcome.Missed) continue
            if (record.initiatorId in blocked()) continue
            if (record.createdAt > newest) newest = record.createdAt
            if (record.createdAt < since) {
                older++
                continue
            }
            notices?.missed(record.callId, record.initiatorId, atMs = record.createdAt, from = io.tima.domain.chat.NoticeFrom.CatchUp)
        }
        if (older > 0) Journal.note(LogCode.NOTICE, "пропущенные старше входа устройства не уведомлены", "сколько" to older)
        return newest
    }

    /**
     * Когда это устройство вошло в аккаунт — первый запуск с ним. Запоминается один раз;
     * у обновлённого устройства это время обновления, и старое тоже не зазвучит.
     */
    private suspend fun deviceSince(): Long {
        sinceCached?.let { return it }
        val key = "device.since." + session.deviceId
        val saved = runCatching { environment.settings.all().first()[key] }.getOrNull()?.toLongOrNull()
        if (saved != null) return saved.also { sinceCached = it }
        val now = io.tima.core.network.ServerClock.now()
        runCatching { environment.settings.put(key, now.toString()) }
        sinceCached = now
        return now
    }

    /** Номер ленты звонков этого устройства (ЖУ0). */
    private fun ctsKey() = CALLS_CTS + "." + session.deviceId

    /** «О пропущенных уведомил до…» этого устройства. */
    private fun markKey() = CALLS_MISSED_MARK + "." + session.deviceId

    /** Одно действие ленты — туда же, куда раньше шли кадры звонка. */
    private suspend fun apply(action: CallLedger.Action) {
        when (action) {
            is CallLedger.Action.Ring ->
                // Звонок от заблокированного молчит (Л17): строка в журнале звонков
                // остаётся, телефон не звонит.
                if (action.fromId in blocked()) {
                    Journal.note(LogCode.NET_CHANNEL, "звонок от заблокированного — не звоним", "звонок" to action.callId)
                } else if (action.groupId.isNotEmpty()) {
                    notices?.calling(action.callId, action.fromId, action.video)
                    onGroupCall(action.callId, action.fromId, if (action.video) "video" else "audio", action.groupId)
                } else {
                    notices?.calling(action.callId, action.fromId, action.video)
                    onCall(action.callId, action.fromId, if (action.video) "video" else "audio")
                }
            is CallLedger.Action.AnsweredHere -> notices?.callOver(action.callId)
            is CallLedger.Action.Taken -> {
                notices?.callOver(action.callId)
                onCallState(action.callId, "taken")
            }
            is CallLedger.Action.End -> {
                notices?.callOver(action.callId)
                onCallState(action.callId, action.why)
            }
            is CallLedger.Action.Missed -> when {
                action.fromId in blocked() -> Unit
                // Пропущенный до входа устройства в аккаунт — только журнал звонков, без
                // уведомления (заказчик 2026-09-30, 2а). Лента помнит сутки, и новое
                // устройство, начав её с нуля, уведомляло о звонках, сделанных до него:
                // ПК 2026-09-30 — 14 строк сразу после входа по QR.
                missedBeforeDevice(action.atMs, deviceSince()) ->
                    Journal.note(LogCode.NOTICE, "пропущенный старше входа устройства не уведомлён", "звонок" to action.callId.take(8))
                else -> notices?.missed(action.callId, action.fromId, atMs = action.atMs)
            }
            is CallLedger.Action.MissedSeen -> notices?.missedSeen(action.callId)
            is CallLedger.Action.Delivered -> onCallDelivered(action.callId)
            is CallLedger.Action.Unreachable -> onCallUnreachable(action.callId)
        }
    }

    private suspend fun runChannel(): StreamOutcome =
        network.eventChannel()
            .run(
                cursor = null,
                onGroupKeys = { decision -> aboutKeys(decision) },
                onLevelNarrowed = { decision -> aboutLevel(decision) },
                // Удалили у всех (Решения §8): «Сообщение удалено», содержимое и правки стёрты.
                onMessageDeleted = { decision ->
                    environment.removal.markDeleted(decision.chatId, decision.messageId)
                    // Удалённое закреплённое открепляется само: сервер снял его в самом удалении.
                    pins.deleted(decision.chatId, decision.messageId)
                },
                onMessagePinned = { decision -> aboutPin(decision) },
                // Файл почтальона: просьба загрузить снова, «получатель забрал» (Решения §6).
                onMedia = { decision -> postman.event(decision.wanted, decision.mediaId) },
                onComment = { decision -> aboutComment(decision) },
                onStoreChanged = { decision -> onStoreChanged(decision.kind, decision.revision) },
                onCall = { decision -> aboutCall(decision) },
                onCallsTop = { top -> callsTop(top) },
                onStateTop = { top -> liveStates?.top(top) },
                outgoing = liveStates?.frames,
                onOpen = {
                    live = true
                    // Экономичный режим (заказчик 2026-10-06): журнал отвечает на вопрос,
                    // рвётся ли канал при редкой перекличке и на какой сети.
                    if (io.tima.core.network.ChannelPing.economy) {
                        Journal.note(
                            LogCode.NET_CHANNEL,
                            "канал открыт — экономичный режим",
                            "перекличка" to "${io.tima.core.network.ChannelPing.economySeconds} с",
                            "сеть" to NetworkWatches.current.kind().ifEmpty { "?" },
                        )
                    }
                },
                // Разрыв называется полосой, а не «что-то потерялось». Строка
                // в дневнике — единственное место, где это видно человеку,
                // который разбирает отчёт о проблеме.
                onLaneGap = { lane, missed ->
                    Journal.note(
                        LogCode.SYNC_LANE_GAP,
                        "в полосе пропущено — догоняю",
                        "полоса" to lane,
                        "сколько" to missed,
                    )
                },
            ) { event ->
                accept(event.chatId, event.messageId, event.envelope)
                stamp(event)
            }

    suspend fun hold() {
        /** Сколько подъёмов подряд кончились ничем. Сбрасывается, когда канал пожил. */
        var streak = 0
        while (true) {
            // ── СЕТИ НЕТ — НЕ ПРОБУЕМ ВОВСЕ (У17) ───────────────────────────
            //
            // Система сказала, что сети нет: попытка кончится `UnknownHost`, разбудит
            // радио и ничего не даст. Ждём, пока появится, — но не дольше потолка:
            // прошивка, однажды не приславшая `onAvailable`, иначе оставила бы канал
            // мёртвым навсегда.
            val watch = NetworkWatches.current
            if (watch.state.value == NetworkState.LOST) {
                if (waitBeforeRetry(watch, pauseMs = RETRY_CEILING_MS, ceilingMs = RETRY_CEILING_MS)) streak = 0
            }
            val startedAt = msNow()
            val outcome = runCatching {
                // ── СЕТЬ СМЕНИЛАСЬ — КАНАЛ РВЁТСЯ САМ (У17) ─────────────────
                //
                // После смены сети сокет остаётся привязан к прежней и полуоткрыт, и
                // закрывать его некому. До У17 это замечал только пинг — до 36 секунд,
                // а звонок живёт сорок пять. Теперь система говорит о смене сама, и
                // канал рвётся в ту же секунду.
                coroutineScope {
                    val breaker = launch {
                        networkBrokeChannel(watch)
                        throw NetworkSwitched()
                    }
                    // ── СТОРОЖ ПОПЫТКИ ────────────────────────────────────────
                    //
                    // Попытка, которая не открыла канал за [STUCK_MS] при живой сети, —
                    // зависла. 2026-09-26 после включения VPN ПК минутами не держал ни
                    // соединения, и ни пинг, ни пауза этого не замечали: пинг живёт внутри
                    // открытого канала, а паузы ограничены — зависнуть можно было только
                    // внутри попытки. Сторож рвёт её и начинает заново.
                    val guard = launch {
                        // Канал открылся — сторожить попытку больше нечего, и будить телефон
                        // раз в 15 с до конца жизни канала незачем (заказчик 2026-10-06, батарея).
                        while (!live) {
                            delay(STUCK_CHECK_MS)
                            val lost = NetworkWatches.current.state.value == NetworkState.LOST
                            if (!live && !lost && msNow() - startedAt > STUCK_MS) throw ChannelStuck()
                        }
                    }
                    try {
                        runChannel()
                    } finally {
                        live = false
                        breaker.cancel()
                        guard.cancel()
                    }
                }
            }
            // Отмена всего приёмника (выход из аккаунта) не должна проглатываться
            // `runCatching` и крутить цикл дальше.
            currentCoroutineContext().ensureActive()
            if (outcome.exceptionOrNull() is ChannelStuck) {
                lastOutcome = "попытка висела"
                Journal.trouble(
                    LogCode.NET_CHANNEL,
                    "канал не поднялся за отведённое время — начинаю заново",
                    "ждали с" to STUCK_MS / 1000,
                )
                continue
            }
            if (outcome.exceptionOrNull() is NetworkSwitched) {
                // Прежние неудачи были про прежнюю сеть: считать их против новой нечестно.
                streak = 0
                lastOutcome = "сеть сменилась"
                Journal.note(LogCode.NET_CHANNEL, "сеть сменилась — поднимаю канал заново")
                continue
            }
            lastOutcome = outcome.fold(
                onSuccess = { it.toString() },
                onFailure = { "канал упал: ${it::class.simpleName}: ${it.message}" },
            )
            // Канал пожил — значит подняться получилось, и счёт неудач начинается
            // заново. Без этого одна долгая ночь без сети навсегда оставила бы паузу
            // на потолке, и утром сообщения ждали бы минуту вместо секунды.
            streak = if (msNow() - startedAt >= LIVED_LONG_ENOUGH_MS) 0 else streak + 1
            // ── СБОРКА НИЖЕ ПОРОГА: ПЕРЕПОДКЛЮЧАТЬСЯ НЕЧЕГО ─────────────────
            //
            // Ответ будет тот же, а телефон тем временем выглядит работающим — это и
            // есть та беда, ради которой кадр заведён. Цикл прерывается: дальше дело
            // экрана, а не канала.
            val outdated = outcome.getOrNull() as? StreamOutcome.AppOutdated
            if (outdated != null) {
                Journal.note(
                    LogCode.NET_CHANNEL,
                    "сервер не работает с этой сборкой — нужно обновиться",
                    "порог" to outdated.minClient,
                    "на сервере" to outdated.versionName,
                )
                onOutdated()
                return
            }
            // ── ТОКЕН ИСТЁК — ОБНОВИТЬ И ПОДНЯТЬСЯ СРАЗУ ────────────────────
            //
            // В фоне обычных ручек нет, и `401`, который обновлял токен, не случался:
            // канал сутками ходил к серверу с истёкшим и получал отказ (realme и Redmi
            // 2026-10-08 — звонки до них не доходили). Не обновилось — обычная пауза ниже.
            if (outcome.getOrNull() is StreamOutcome.TokenRefused) {
                val fresh = network.tokenKeeper?.renew() == true
                Journal.trouble(LogCode.NET_CHANNEL, "канал отказал по токену — обновляю", "обновлён" to fresh)
                if (fresh) {
                    streak = 0
                    continue
                }
            }
            // ── ПАДЕНИЕ КАНАЛА ПИШЕТСЯ В ЖУРНАЛ ─────────────────────────────
            //
            // Раньше исход оставался только в `lastOutcome` — поле, которое читает
            // отладчик в руках. На realme 2026-09-23 это стоило двух суток: телефон не
            // получал событий вовсе, и в журнале не было ни строки об этом. Отличить
            // «канал молча не поднимается» от «по каналу просто нечего слать» было нечем.
            // ── ПАУЗА БЕРЁТСЯ У СОСТОЯНИЯ СВЯЗИ, А НЕ ИЗ ЧИСЛА (У11) ────────
            //
            // Здесь стояли жёсткие две секунды. В тоннеле это **тридцать попыток в
            // минуту** без конца и края, каждая будит радио и начинает TLS. Пока канал
            // жил вместе с окном, это было безвредно: окно открыто — телефон и так не
            // спит. Служба живёт сутками, и это стало бы главным пожирателем батареи.
            //
            // Политика при этом давно написана и выведена из настоящих журналов
            // испытаний — `LinkState.retryDelayMs`: сеть мигает и возвращается быстро
            // (5 с), а стена у оператора стоит часами (120 с). Её просто никто не звал.
            // Связь — из исхода канала, если он её назвал: исключения здесь нет, канал
            // ловит его сам и отдаёт `Disconnected` с уже определённой связью.
            val link = (outcome.getOrNull() as? StreamOutcome.Disconnected)?.link
                ?: classifyFailure(outcome.exceptionOrNull())
            val pause = retryPause(link, streak)
            // ── ВТОРОЙ ОБРЫВ ПОДРЯД — БЕДА, И ОНА ЛОЖИТСЯ НА ДИСК СРАЗУ ──────
            //
            // Одиночный обрыв — жизнь сети, запись о нём ждёт своей пачки. Второй подряд
            // уже значит «канал не поднимается», и такая строка обязана пережить процесс:
            // 2026-09-26 на ПК после включения VPN канал не вставал минутами, а все строки
            // о попытках остались в несброшенной пачке и пропали с перезапуском.
            val fields = arrayOf<Pair<String, Any?>>(
                "исход" to (lastOutcome ?: "—"),
                "связь" to link.name,
                "подряд" to streak,
                "пауза" to pause,
            ) + if (io.tima.core.network.ChannelPing.economy) {
                // Сколько прожил канал при редкой перекличке и на какой сети — ради этого
                // экономичный режим и пишет журнал (заказчик 2026-10-06).
                arrayOf<Pair<String, Any?>>(
                    "жил" to "${(msNow() - startedAt) / 1000} с",
                    "перекличка" to "${io.tima.core.network.ChannelPing.economySeconds} с",
                    "сеть" to NetworkWatches.current.kind().ifEmpty { "?" },
                )
            } else {
                emptyArray()
            }
            if (streak >= 1) {
                Journal.trouble(LogCode.NET_CHANNEL, "живой канал оборвался, поднимаю заново", *fields)
            } else {
                Journal.note(LogCode.NET_CHANNEL, "живой канал оборвался, поднимаю заново", *fields)
            }
            // Ждём паузу ИЛИ событие сети — что раньше (У17). Сеть появилась или
            // сменилась — пробуем сразу: прежние неудачи были про прежнюю сеть.
            if (waitBeforeRetry(watch, pauseMs = pause, ceilingMs = RETRY_CEILING_MS)) streak = 0
        }
    }

    /**
     * Под нашей записью ответили (ADR-0024, следствие 5).
     *
     * **Что делает клиент.** Записывает в журнал и зовёт [onComment] — того, кто сейчас
     * показывает страницу. Открытая страница перечитывается, и счётчик под записью
     * меняется сам.
     *
     * **Чего он не делает, и это названо, а не забыто.** Полки уведомлений в приложении
     * нет вовсе: ни списка событий, ни значка на окне, ни push. Уведомление автору —
     * решение ADR-0024, и оно выполнено настолько, насколько у клиента есть куда его
     * показать. Значок и push заводятся вместе с полкой уведомлений, отдельной работой.
     */
    private fun aboutComment(decision: EventStreamProtocol.Decision.CommentArrived) {
        environment.journal.note(
            chatId = decision.channelId,
            key = "comment/${decision.channelId}/${decision.postId}/${decision.commentId}",
            text = "Под вашей записью ответили",
            atMs = msNow(),
        )
        onComment(decision.channelId, decision.postId)
    }

    /**
     * Сообщение группы: записать кадр, потом попытаться открыть.
     *
     * **Нет ключа этой версии — не поломка.** Сообщение отправлено до нашего прихода в
     * группу либо ключ ещё не доехал; строка остаётся нечитаемой, и человек видит на
     * экране «сообщение недоступно» с предложением запросить ключ. Именно поэтому здесь
     * `NoKey`, а не `Rejected`: первое означает «попробуем ещё», второе — «никогда».
     */
    private suspend fun acceptGroup(groupId: String, messageId: Long, frame: ByteArray) {
        val parsed = GroupFrame.parse(frame)
        // Время написания — из кадра: по нему переписка на всех устройствах в одном порядке.
        environment.incoming.receive(groupId, messageId, frame, sentAtMs = parsed?.createdAtUnixMs ?: 0)

        // Сообщение группы — вкладка «Группы» журнала уведомлений (ЖУ1, ЖУ4). До 2026-09-30
        // группы не уведомляли вовсе. Своё и от заблокированного молчит — решает `Notices`.
        // Правка — без уведомления (ADR-0045 §5): она ложится на уже показанное сообщение.
        if ((parsed?.editOf ?: 0L) == 0L) {
            notices?.arrived(groupId, parsed?.senderId, ref = "$groupId/$messageId", sentAtMs = parsed?.createdAtUnixMs ?: 0, group = true)
        }

        // Ключ подписи спрашивается до разбора: сам разбор синхронный, и ходить за ним
        // изнутри нельзя. Промах кэша означает лишь, что сообщение откроется следующей
        // попыткой — оно уже записано и не потеряется.
        if (parsed != null) captionKey(parsed.senderId, parsed.senderDevice)

        environment.incoming.openNext { entry ->
            if (!GroupFrame.isGroupFrame(entry.envelope)) {
                // Очередь отдала не групповую запись: её откроет свой путь. Причина
                // называется словами, чтобы это не выглядело потерей ключа.
                OpenOutcome.NoKey("запись не групповая — ждёт своего разбора")
            } else {
                openGroup(entry)
            }
        }

        // Группа в списке переписок: без строки человек не увидит, куда пришло сообщение.
        // Сначала — как есть, чтобы строка была даже без сети; следом — настоящее
        // название с сервера, оно перекроет заглушку.
        if (!environment.chatFacts.knows(groupId)) {
            book.remember(chatId = groupId, kind = ChatKind.Group, title = "Группа", peerId = null)
            syncGroups()
        }
    }

    /**
     * Строка звонка в переписке группы — её рисует сам телефон и хранит у себя, в переписку
     * ничего не уходит (заказчик 2026-10-01, 8б). «Начат» и «завершён» видят все участники
     * группы: сервер рассылает им это событие. Ключ — по звонку: событие, приехавшее дважды
     * (живым каналом и догоном), строку не удвоит.
     */
    private suspend fun callLine(d: EventStreamProtocol.Decision.GroupCall) {
        if (d.callId.isEmpty()) return
        val w = words().groupCall
        val text = when (d.state) {
            // Одно сообщение (заказчик 2026-10-02): «Звонок начат: имя, ник» и следующей
            // строкой «Участники: …; …» — кого позвали, без меня, все. Кого позвали, говорит
            // звонок группы на сервере (у участника отметка «позван»).
            "live" -> {
                val who = when {
                    d.by.isEmpty() -> null
                    d.by == session.userId -> w.stateSelf
                    else -> notices?.nameAndNick(d.by)
                }
                val called = runCatching { network.calls.groupCall(d.groupId) }.getOrNull()?.call
                    ?.takeIf { it.callId == d.callId }
                    ?.members.orEmpty()
                    .filter { it.invited && it.userId != session.userId && it.userId != d.by }
                    .map { notices?.nameOf(it.userId) ?: words().chat.nameless }
                val first = w.lineStarted(who ?: words().chat.nameless)
                if (called.isEmpty()) first else first + "\n" + w.lineParticipants(called.joinToString("; "))
            }
            "ended" -> w.lineEnded
            else -> return
        }
        runCatching { environment.journal.note(d.groupId, "call:" + d.callId + ":" + d.state, text, msNow()) }
    }

    /** Сверка групп с сервером — и сроки временных групп звонка наружу (решение 11). */
    internal suspend fun syncGroups() {
        val step = groupsSync.refresh()
        if (step is SyncGroupsStep.Synced) {
            onCallGroups(step.callGroups)
            onCallOwners(step.callOwners)
        }
    }

    /**
     * Временная группа звонка удалена сервером (срок вышел, решение 1) — стираем её и здесь:
     * переписку, строку списка и ключи. «Удаляется вместе с перепиской и всем» относится и
     * к копии на устройстве, а не только к серверу.
     */
    private fun wipeGroup(groupId: String) {
        runCatching { book.wipe(groupId) }.onFailure { Journal.trouble(LogCode.NET_CHANNEL, "временная группа звонка не стёрта", "группа" to groupId.take(8), "причина" to (it.message ?: "?")) }
        Journal.note(LogCode.NET_CHANNEL, "временная группа звонка удалена — стёрта и здесь", "группа" to groupId.take(8))
    }

    private fun openGroup(entry: IncomingEntry): OpenOutcome {
        val frame = GroupFrame.parse(entry.envelope)
            ?: return OpenOutcome.Rejected("кадр группы не разбирается")
        return openGroupFrame(frame)
    }

    /** Кадр группы — подпись по метаданным, ключ по версии. Общий для ленты и окна «Описание». */
    private fun openGroupFrame(frame: GroupFrame): OpenOutcome {
        // Открытому сообщению (уровни 0…3) ключ не нужен: его читает тот, кому ключа не
        // дадут, — описание личной группы, лента публичной. Подпись при этом проверяется
        // так же строго.
        val plain = frame.gkVersion == 0
        val groupKey = if (plain) ByteArray(0) else {
            groupKeys.key(frame.groupId, frame.gkVersion)
                ?: return OpenOutcome.NoKey("нет ключа версии ${frame.gkVersion}")
        }
        val sender = senderKeys[frame.senderDevice]
        // Ключа нет ни у кого — открыть без проверки подписи, с пометкой «Не подтверждено» (Р10, КК13).
        if (sender == null && frame.senderDevice in absentSenders) {
            return GroupMessages.openUnverified(
                groupId = frame.groupId,
                senderId = frame.senderId,
                senderDevice = frame.senderDevice,
                kind = frame.kind,
                createdAtUnixMs = frame.createdAtUnixMs,
                threadRoot = frame.threadRoot,
                replyTo = frame.replyTo,
                gkVersion = frame.gkVersion,
                payload = frame.payload,
                signature = frame.signature,
                groupKey = groupKey,
                keyCommitment = frame.keyCommitment,
                editOf = frame.editOf,
            ).fold(
                onSuccess = {
                    Journal.note(LogCode.DEVICE_TRUST, "открыто без проверки подписи — ключа автора нет", "группа" to frame.groupId.take(8), "устройство" to frame.senderDevice.take(8))
                    OpenOutcome.Opened(it.body, it.meta.senderId, frame.level, frame.threadRoot, editOf = frame.editOf, onlyContinuation = frame.onlyContinuation, unverified = true)
                },
                onFailure = { OpenOutcome.Rejected("сообщение группы не открылось: ${it.message}") },
            )
        }
        val captionKey = sender?.signingPub
            ?: return OpenOutcome.NoKey("ключ подписи отправителя не получен")

        // Метаданные собирает фасад: их раскладка входит в подписываемые байты, и
        // собирать её здесь значило бы держать копию правила вдали от него самого.
        return GroupMessages.open(
            groupId = frame.groupId,
            senderId = frame.senderId,
            senderDevice = frame.senderDevice,
            kind = frame.kind,
            createdAtUnixMs = frame.createdAtUnixMs,
            threadRoot = frame.threadRoot,
            replyTo = frame.replyTo,
            gkVersion = frame.gkVersion,
            payload = frame.payload,
            signature = frame.signature,
            senderSigningPublic = captionKey,
            groupKey = groupKey,
            keyCommitment = frame.keyCommitment,
            editOf = frame.editOf,
        ).fold(
            // Номер исправляемого — из кадра: подпись версии 3 сошлась, значит он тот, что подписал автор.
            // Подпись отозванного по краже сошлась, но автора не подтверждает (Р11): до отзыва писать мог и вор.
            onSuccess = {
                OpenOutcome.Opened(it.body, it.meta.senderId, frame.level, frame.threadRoot, editOf = frame.editOf, onlyContinuation = frame.onlyContinuation, unverified = !sender.confirms)
            },
            onFailure = { OpenOutcome.Rejected("сообщение группы не открылось: ${it.message}") },
        )
    }

    /**
     * Кадр про групповые ключи: исход остаётся в диагностике, канал не роняется.
     *
     * Приехали ключи группы — недоступное разбирается заново (план КК, КК2): прежде повтора не
     * было, и сообщение, разобранное до прихода ключа, оставалось «недоступным» навсегда (беда
     * 2026-10-24).
     */
    private suspend fun aboutKeys(decision: EventStreamProtocol.Decision) {
        keyOrchestrator.handle(decision)?.let { lastOutcome = it }
        if (decision is EventStreamProtocol.Decision.KeysArrived) runCatching { reopenUnreadable() }
    }

    /** Закреплённое (ПЛАН-(ЗО) ЗО3): снимок на устройстве — открытым перепискам и событиям. */
    val pins = io.tima.domain.chat.ChatPins(network.pins)

    /**
     * Закрепили или сняли (ПЛАН-(ЗО) ЗО3): полоса под шапкой обновляется сама, в ленте — служебная
     * строка словами читающего, **без уведомления** (заказчик 2026-10-18). Ключ строки — номер события:
     * кадр приезжает и живым каналом, и догоном, а строка должна остаться одна.
     */
    private suspend fun aboutPin(d: EventStreamProtocol.Decision.MessagePinned) {
        pins.applied(d.chatId, d.messageId)
        val mine = d.by == session.userId
        val who = if (mine) null else (d.by.takeIf { it.isNotEmpty() }?.let { notices?.nameOf(it) } ?: words().chat.someone)
        runCatching {
            environment.journal.note(
                chatId = d.chatId,
                key = "pin/${d.chatId}/${d.eventId ?: d.messageId}",
                text = words().showcase.pinLine(who, d.messageId > 0),
                atMs = msNow(),
            )
        }
        Journal.note(LogCode.PIN_MSG, "событие", "закреплено" to (d.messageId > 0), "моё" to mine, "переписка" to d.chatId.take(8))
    }

    /**
     * Сообщение по номеру (Решения §11.3–§11.4): сначала база устройства, потом сервер. Группа —
     * кадром с той же проверкой подписи и ключом, что лента; канал — открытым текстом; личное, которого
     * нет на устройстве, сервер отдаёт только обёрткой для этого устройства — его догонит история, а
     * здесь оно «недоступно».
     */
    val finder = io.tima.domain.chat.MessageFinder { kind, chatId, serverId ->
        environment.localMessages.line(chatId, serverId)?.let { return@MessageFinder io.tima.domain.chat.FoundMessage.Found(it) }
        val answer = network.byNumber.find(kind, chatId, serverId)
        val found = answered(chatId, serverId, answer)
        // Полученное — на устройство, закрытым ключом устройства (ПЛАН-(УК) УК1): «Указатели» берут оттуда.
        // Сервер сказал «нет» — прежняя копия уходит.
        when {
            found is io.tima.domain.chat.FoundMessage.Found -> runCatching {
                when (answer) {
                    is io.tima.core.network.MessageByNumberOverHttp.Answer.GroupFrame -> environment.refCache.put(chatId, serverId, REF_FRAME, answer.stored)
                    is io.tima.core.network.MessageByNumberOverHttp.Answer.Open -> environment.refCache.put(chatId, serverId, REF_OPEN, openBytes(answer))
                    else -> Unit
                }
            }
            found is io.tima.domain.chat.FoundMessage.Unavailable -> runCatching { environment.refCache.drop(chatId, serverId) }
        }
        found
    }

    /**
     * Сообщение по номеру для «Указателей» (ПЛАН-(УК)): база устройства, сохранённая копия с сервера,
     * и только потом сервер.
     */
    val pointerFinder = io.tima.domain.chat.MessageFinder { kind, chatId, serverId ->
        environment.localMessages.line(chatId, serverId)?.let { return@MessageFinder io.tima.domain.chat.FoundMessage.Found(it) }
        val kept = runCatching { environment.refCache.get(chatId, serverId) }.getOrNull()
        val fromKept = when (kept?.first) {
            REF_FRAME -> answered(chatId, serverId, io.tima.core.network.MessageByNumberOverHttp.Answer.GroupFrame(kept.second))
            REF_OPEN -> openOf(kept.second)?.let { answered(chatId, serverId, it) }
            else -> null
        }
        fromKept as? io.tima.domain.chat.FoundMessage.Found ?: finder.find(kind, chatId, serverId)
    }

    /** Ответ сервера по номеру — строка или «недоступно». */
    private suspend fun answered(
        chatId: String,
        serverId: Long,
        answer: io.tima.core.network.MessageByNumberOverHttp.Answer,
    ): io.tima.domain.chat.FoundMessage = when (answer) {
        is io.tima.core.network.MessageByNumberOverHttp.Answer.GroupFrame -> run {
            val frame = GroupFrame.parse(answer.stored)
                ?: return@run io.tima.domain.chat.FoundMessage.Unavailable("missing")
            captionKey(frame.senderId, frame.senderDevice)
            val opened = openGroupFrame(frame) as? OpenOutcome.Opened
                ?: return@run io.tima.domain.chat.FoundMessage.Unavailable("closed")
            val line = io.tima.core.encryption.TextBodyCodec.decodeLine(opened.body)
                ?: return@run io.tima.domain.chat.FoundMessage.Unavailable("closed")
            io.tima.domain.chat.FoundMessage.Found(remoteLine(chatId, serverId, frame.senderId, line, frame.createdAtUnixMs, frame.level.toInt()))
        }
        is io.tima.core.network.MessageByNumberOverHttp.Answer.Open -> {
            val line = io.tima.core.encryption.ComposedBodiesOverKodium.readOpen(answer.nodes, answer.markup)
            if (line == null) io.tima.domain.chat.FoundMessage.Unavailable("missing")
            else io.tima.domain.chat.FoundMessage.Found(remoteLine(chatId, serverId, answer.authorId, line, answer.atMs, 0))
        }
        io.tima.core.network.MessageByNumberOverHttp.Answer.Personal -> io.tima.domain.chat.FoundMessage.Unavailable("closed")
        is io.tima.core.network.MessageByNumberOverHttp.Answer.Unavailable -> io.tima.domain.chat.FoundMessage.Unavailable(answer.reason, answer.level)
        io.tima.core.network.MessageByNumberOverHttp.Answer.Offline -> io.tima.domain.chat.FoundMessage.Offline
    }

    /** Открытая запись — в байты для хранения: автор, время, разметка и узлы, каждое строкой Base64. */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun openBytes(a: io.tima.core.network.MessageByNumberOverHttp.Answer.Open): ByteArray {
        val b64 = kotlin.io.encoding.Base64
        val fields = listOf(a.authorId.orEmpty(), a.atMs.toString(), a.markup) + a.nodes
        return fields.joinToString("\n") { b64.encode(it.encodeToByteArray()) }.encodeToByteArray()
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun openOf(bytes: ByteArray): io.tima.core.network.MessageByNumberOverHttp.Answer.Open? = runCatching {
        val f = bytes.decodeToString().split('\n').map { kotlin.io.encoding.Base64.decode(it).decodeToString() }
        io.tima.core.network.MessageByNumberOverHttp.Answer.Open(f[0].ifEmpty { null }, f.drop(3), f[2], f[1].toLong())
    }.getOrNull()

    private fun remoteLine(chatId: String, serverId: Long, sender: String?, line: io.tima.domain.chat.DecodedLine, atMs: Long, level: Int) =
        io.tima.domain.chat.ChatLine(
            dedupKey = "remote:$chatId:$serverId", chatId = chatId, display = io.tima.domain.chat.MessageDisplay.RECEIVED,
            text = line.text, rich = line.rich, outgoing = sender != null && sender == session.userId, atMs = atMs,
            localId = 0, serverId = serverId, senderId = sender, level = level,
        )

    /**
     * Описание (ПЛАН-(ЗО) ЗО4): у группы — кадрами с той же проверкой подписи, что и в ленте; у канала
     * и сообщества — открытым текстом. Нигде не хранится: окно забирает его при каждом открытии.
     */
    val showcase: io.tima.domain.chat.ShowcaseSource = object : io.tima.domain.chat.ShowcaseSource {
        override suspend fun page(of: io.tima.domain.chat.ShowcaseOf) = showcasePage(of)
        override suspend fun setSuper(of: io.tima.domain.chat.ShowcaseOf, serverId: Long) = network.showcase.setSuper(of, serverId)
    }

    private suspend fun showcasePage(of: io.tima.domain.chat.ShowcaseOf): io.tima.domain.chat.ShowcasePage? {
        val raw = network.showcase.page(of) ?: return null
        val codec = io.tima.core.encryption.TextBodyCodec
        if (of.kind != "group") {
            return io.tima.domain.chat.ShowcasePage(
                raw.superId,
                raw.open.mapNotNull { item ->
                    io.tima.core.encryption.ComposedBodiesOverKodium.readOpen(item.nodes, item.markup)
                        ?.let { io.tima.domain.chat.ShowcaseLine(item.id, item.authorId, it) }
                },
            )
        }
        class Opened(val frame: GroupFrame, val body: ByteArray)
        val opened = raw.frames.mapNotNull { stored ->
            val frame = GroupFrame.parse(stored) ?: return@mapNotNull null
            // Описание открыто по замыслу (уровень 0): ключ группы ему не нужен.
            if (frame.gkVersion != 0) return@mapNotNull null
            captionKey(frame.senderId, frame.senderDevice) ?: return@mapNotNull null
            (openGroupFrame(frame) as? OpenOutcome.Opened)?.let { Opened(frame, it.body) }
        }
        // Правки ложатся на исходное, как в переписке (ADR-0045): в выдаче они рядом с ним.
        val edits = opened.filter { it.frame.editOf > 0 }.groupBy { it.frame.editOf }
        val lines = opened.filter { it.frame.editOf == 0L }.mapNotNull { o ->
            val mine = edits[o.frame.messageId].orEmpty().map { it.body }
            val line = (if (mine.isEmpty()) codec.decodeLine(o.body) else codec.decodeEdited(o.body, mine)) ?: return@mapNotNull null
            io.tima.domain.chat.ShowcaseLine(o.frame.messageId, o.frame.senderId, line, onlyContinuation = o.frame.onlyContinuation)
        }
        return io.tima.domain.chat.ShowcasePage(raw.superId, lines)
    }

    /**
     * Круг сообщения сузили: метку у реплики поменять, и сказать словами почему.
     *
     * **Оба действия обязательны, и второе важнее.** Одна метка объясняет только тому, кто
     * включил их показ; всем остальным реплика просто пропадает из чужих лент, и это
     * выглядит как поломка. Поэтому в группу ложится строка — там же, где живёт само
     * сообщение, и там же, где админ может объяснить причину.
     *
     * Ключ строки собран из идентификатора события: тот же кадр приезжает и живым каналом,
     * и догоном истории, а строк об одном сужении должно остаться ровно одна.
     */
    private fun aboutLevel(decision: EventStreamProtocol.Decision.LevelNarrowed) {
        environment.journal.levelChanged(decision.groupId, decision.messageId, decision.level)
        val circle = MessageCircle.of(decision.level)
        environment.journal.note(
            chatId = decision.groupId,
            key = "level/${decision.groupId}/${decision.messageId}/${decision.level}",
            text = "Круг сообщения сузили: теперь «${circle.title}». ${circle.about}",
            atMs = msNow(),
        )
    }

    /**
     * Одно событие: записать конверт, потом попытаться разобрать.
     *
     * Возвращается **только после записи**: подтверждение уходит сразу после нас, а
     * подтверждённое сервер больше не пришлёт.
     */
    /** Штамп отправителя — если сервер его прислал. Автор берётся из самого кадра. */
    private fun stamp(event: EventStreamProtocol.IncomingEvent) {
        if (event.senderProfileRev == null && event.senderHue == null) return
        val group = GroupFrame.isGroupFrame(event.envelope)
        val sender = if (group) GroupFrame.parse(event.envelope)?.senderId else envelopeSender(event.envelope)?.userId
        if (sender.isNullOrBlank() || sender == session.userId) return
        onStamp(SenderStamp(sender, event.senderProfileRev, if (group) event.chatId else null, event.senderHue))
    }

    private suspend fun accept(chatId: String, messageId: Long, envelope: ByteArray) {
        // Групповое сообщение приходит тем же путём, но открывается иначе: у него нет
        // конверта, а подпись считается по метаданным вместе с payload.
        if (GroupFrame.isGroupFrame(envelope)) {
            acceptGroup(chatId, messageId, envelope)
            return
        }

        val sender = envelopeSender(envelope)

        // Своя копия с ЭТОГО ЖЕ устройства — не входящее сообщение, а эхо: сервер
        // рассылает конверт по всем обёрткам ключа, включая нашу собственную. Записать её
        // значит показать человеку своё сообщение дважды — один раз своим, второй чужим.
        // Именно так и выглядело на живом прогоне, на обоих устройствах сразу.
        //
        // Подтверждение серверу при этом уходит: событие обработано, повторять его не
        // надо. Молча пропустить и не подтвердить значило бы получать его вечно.
        if (ownCopy(sender?.deviceId)) {
            lastOutcome = "эхо своего сообщения пропущено"
            return
        }

        environment.incoming.receive(chatId, messageId, envelope, sentAtMs = sender?.createdAtMs ?: 0)

        // ── ПЕРВАЯ СТАДИЯ СТРОКИ: БЕЗ ИМЕНИ (У6) ────────────────────────────
        //
        // Здесь, а не после разбора: при недоехавшем ключе человек иначе не узнал бы
        // ничего, а это как раз тот случай, когда узнать надо. Имя при этом не
        // называется — открытая часть конверта подписью не покрыта.
        //
        // Кого не уведомлять (заблокированный, своё с другого устройства), решает
        // `Notices`: правило живёт в одном месте, а не расходится по вызовам.
        // Правка — без уведомления (ADR-0045 §5): она ложится на уже показанное сообщение.
        if ((sender?.editOf ?: 0L) == 0L) {
            notices?.arrived(chatId, sender?.userId, ref = "$chatId/$messageId", sentAtMs = sender?.createdAtMs ?: 0)
        }

        // ── ОТ ЗАБЛОКИРОВАННОГО: ЗАПИСАТЬ, НО НЕ ОТКРЫВАТЬ (Л9) ─────────────
        //
        // Кто прислал, известно БЕЗ расшифровки — из открытой части конверта. Этого
        // довольно, чтобы решить, разбирать ли сейчас.
        //
        // А вот **не записать нельзя**: курсор уже шагнул, подтверждение уйдёт сразу
        // после нас, и через срок хранения сервер событие сотрёт. Человек зашёл бы в чат
        // через «Социум» — и там пусто, и вернуть неоткуда.
        //
        // Конверт ждёт в очереди и разберётся сам, как только блокировку снимут: это
        // обычное `RECEIVED`, а не особое состояние.
        val held = heldChats()
        if (chatId in held) {
            lastOutcome = "конверт от заблокированного записан и придержан"
            // Знакомого — предупредить, не чаще раза в неделю. Незнакомец остаётся в
            // неведении: иначе автоответ становится оракулом «этот меня заблокировал»
            // и чистит список рассыльщику (Л14).
            if (sender != null && autoReply.replyTo(chatId, bookRow(sender.userId))) {
                Journal.note(LogCode.NET_CHANNEL, "заблокированному ушёл автоответ")
            }
            return
        }

        // Разбор — уже после записи. Упадёт — сообщение останется на повтор.
        val key = sender?.let { captionKey(it.userId, it.deviceId) }

        // Разобралась может не та запись, которую мы сейчас записали: очередь берётся
        // с головы. Поэтому переписка и автор берутся у РАЗОБРАННОЙ, а не отсюда.
        var opened: Pair<String, String>? = null
        environment.incoming.openNext(held) { entry ->
            // Ключ — отправителя РАЗОБРАННОЙ записи: очередь берётся с головы (КК13 — с пометкой непроверенного).
            val own = envelopeSender(entry.envelope)
            val entryKey = own?.let { senderKeys[it.deviceId] } ?: key.takeIf { own?.deviceId == sender?.deviceId }
            val outcome = when {
                sender == null || own == null -> OpenOutcome.Rejected("конверт не разбирается")
                entryKey != null -> open(entry, entryKey.signingPub, unverified = !entryKey.confirms)
                own.deviceId in absentSenders -> open(entry, null, unverified = true)
                else -> OpenOutcome.NoKey("ключ подписи отправителя не получен")
            }
            if (outcome is OpenOutcome.Opened) opened = entry.chatId to outcome.senderId
            noted(entry, outcome)
        }
        // ── ВТОРАЯ СТАДИЯ: ПОДПИСЬ СОШЛАСЬ, ИМЯ МОЖНО НАЗВАТЬ (У6) ──────────
        //
        // Та же строка по тому же ключу, а не вторая: два уведомления об одном
        // сообщении человек читает как два сообщения.
        opened?.let { (chat, author) -> notices?.opened(chat, author) }

        // Переписка от незнакомого — со своим именем: иначе в списке появится строка без
        // имени, и человек не узнает, кто написал.
        //
        // Своё с другого своего устройства собеседника не называет: автор там — сам человек.
        // Прежде строка заводилась с собеседником «я», и на ПК, где почти не пишут, переписки
        // показывались как переписка с собой (отчёт X63M, 2026-10-23). Собеседник такой — по
        // списку личных переписок сервера; не ответил — строку заведёт следующее сообщение.
        if (sender != null && !environment.chatFacts.knows(chatId)) {
            val peer = if (sender.userId == session.userId) ownPeer(chatId) else sender.userId
            if (peer != null) {
                book.remember(
                    chatId = chatId,
                    kind = ChatKind.Personal,
                    title = network.directory.nameOrNumber(peer),
                    peerId = peer,
                )
                if (sender.userId == session.userId) Journal.note(LogCode.CHAT_PEER, "переписка заведена", "откуда" to "своё с другого устройства")
            }
        }
    }

    /** Собеседник личной переписки, где пришло своё: переписка с собой — я, иначе — по списку сервера. */
    private suspend fun ownPeer(chatId: String): String? {
        val me = session.userId
        if (chatId == io.tima.core.encryption.PersonalChatIdsOverKodium.personalChatId(me, me)) return me
        return network.history.personalChats()?.firstOrNull { it.chatId == chatId }?.peerId?.takeIf { it.isNotBlank() }
    }

    /**
     * Починка строк, заведённых своим сообщением с другого устройства (отчёт X63M, 2026-10-23):
     * личная переписка с собеседником «я», хотя это не переписка с собой. Собеседник — по
     * списку личных переписок сервера. Чинить нечего — сеть не трогается.
     *
     * @return сколько строк исправлено; `null` — сервер не ответил, повторится при следующем запуске.
     */
    suspend fun repairSelfPeers(): Int? {
        val me = session.userId
        if (me.isBlank()) return 0
        val self = io.tima.core.encryption.PersonalChatIdsOverKodium.personalChatId(me, me)
        val wrong = io.tima.core.database.SqlChatRehome(environment.db).personalPeers().filter { (chat, peer) -> peer == me && chat != self }
        if (wrong.isEmpty()) return 0
        val chats = network.history.personalChats(allIdentities = true) ?: return null
        var fixed = 0
        for ((chatId, _) in wrong) {
            val peer = chats.firstOrNull { it.chatId == chatId }?.peerId?.takeIf { it.isNotBlank() && it != me } ?: continue
            book.remember(chatId = chatId, kind = ChatKind.Personal, title = network.directory.nameOrNumber(peer), peerId = peer)
            fixed++
        }
        Journal.note(LogCode.CHAT_PEER, "переписке дописан собеседник", "откуда" to "починка своего", "было" to wrong.size, "исправлено" to fixed)
        return fixed
    }

    /**
     * История переписки с сервера (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ИУ1, ИУ3).
     *
     * Сервер отдаёт страницами сообщения, для которых у этого устройства есть обёртка: свои,
     * пришедшие после привязки, и те, что перезавернуло своё доверенное устройство. Каждое
     * записывается в очередь входящих тем же путём, что пришедшее живым каналом, и
     * открывается тем же разбором: подпись и обязательство проверяются по исходному
     * конверту. Уведомлений нет — это прошлое, а не новость.
     *
     * @param peerId собеседник, если известен (из списка переписок); строку списка без
     *   него назвать нечем, пока не придёт сообщение собеседника.
     * @return сколько сообщений записано впервые.
     */
    suspend fun pullHistory(chatId: String, peerId: String?): Int {
        var before = 0L
        var added = 0
        var peer = peerId
        while (true) {
            val page = network.history.page(chatId, before) ?: break
            if (page.isEmpty()) break
            for (item in page) {
                val stored = item.wrapEphemeral?.takeIf { it.size == 32 }
                    ?.let { HistoryFrame.toStored(it, item.envelope) } ?: item.envelope
                val sender = envelopeSender(stored) ?: continue
                if (ownCopy(sender.deviceId)) continue
                if (peer == null && sender.userId != session.userId) peer = sender.userId
                captionKey(sender.userId, sender.deviceId)
                if (environment.incoming.receive(chatId, item.messageId, stored, sentAtMs = sender.createdAtMs)) added++
            }
            if (page.size < HistoryApi.PAGE) break
            before = page.last().messageId
        }
        if (peer != null && !environment.chatFacts.knows(chatId)) {
            book.remember(chatId = chatId, kind = ChatKind.Personal, title = network.directory.nameOrNumber(peer), peerId = peer)
        }
        if (added > 0) drainIncoming()
        Journal.note(LogCode.DEVICE_TRUST, "история переписки забрана", "переписка" to chatId.take(8), "новых" to added)
        return added
    }

    /** Все свои личные переписки (ИУ1): строки списка и то, что уже можно прочесть. */
    suspend fun pullAllHistory(): Int? {
        val chats = network.history.personalChats() ?: return null
        var added = 0
        for (chat in chats) added += pullHistory(chat.chatId, chat.peerId.takeIf { it != session.userId })
        return added
    }

    /**
     * История группы с сервера (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ИУ3, групповая часть).
     *
     * Сервер отдаёт страницы тем же кадром, что живой канал, и ровно то, что этой роли
     * положено видеть (ADR-0019). Каждый кадр ложится в очередь входящих тем же путём,
     * что пришедший живым, и открывается тем же разбором. Ключи групп новое устройство
     * получает при привязке (`handOver`); сообщение версии, ключа которой нет, остаётся
     * «недоступным» с кнопкой «Запросить ключ» — как у пришедшего живым. Уведомлений нет:
     * это прошлое, а не новость.
     *
     * @return сколько сообщений записано впервые.
     */
    suspend fun pullGroupHistory(groupId: String): Int {
        var before = 0L
        var added = 0
        while (true) {
            val page = network.groupMessages.page(groupId, before) ?: break
            if (page.isEmpty()) break
            for (item in page) {
                val frame = GroupFrame.parse(item.stored) ?: continue
                captionKey(frame.senderId, frame.senderDevice)
                if (environment.incoming.receive(groupId, item.messageId, item.stored, sentAtMs = item.sentAtMs)) added++
            }
            if (page.size < GroupMessagesApi.PAGE) break
            before = page.last().messageId
        }
        if (added > 0) drainIncoming()
        Journal.note(LogCode.DEVICE_TRUST, "история группы забрана", "группа" to groupId.take(8), "новых" to added)
        return added
    }

    /**
     * Новое в открытой группе — «зашли, забрали» (ПЛАН-(ОУ) ОУ6): страницы с сервера от свежих к
     * старым, пока страница приносит новое. В отличие от [pullGroupHistory] не тянет всю историю
     * каждый раз: открыли группу — забрали только то, чего нет.
     *
     * @return номер позднейшего сообщения группы на сервере (для отметки «прочитал до»); 0 — не
     *   узнали (нет связи) или группа пуста.
     */
    suspend fun pullGroupNew(groupId: String): Long {
        var before = 0L
        var added = 0
        var newest = 0L
        while (true) {
            val page = network.groupMessages.page(groupId, before) ?: break
            if (page.isEmpty()) break
            if (newest == 0L) newest = page.maxOf { it.messageId }
            var fresh = 0
            for (item in page) {
                // Уже есть у себя под этим номером — прежде всего своё отправленное: записать его
                // снова значило бы показать своё сообщение дважды (беда 2026-10-21).
                if (environment.localMessages.line(groupId, item.messageId) != null) continue
                val frame = GroupFrame.parse(item.stored) ?: continue
                captionKey(frame.senderId, frame.senderDevice)
                if (environment.incoming.receive(groupId, item.messageId, item.stored, sentAtMs = item.sentAtMs)) fresh++
            }
            added += fresh
            if (fresh == 0 || page.size < GroupMessagesApi.PAGE) break
            before = page.last().messageId
        }
        if (added > 0) drainIncoming()
        // Приём мог обогнать ответ сервера на свою отправку — номер у исходящей появился позже.
        val echoes = runCatching { io.tima.core.database.SqlMessageRepair(environment.db).dropOwnEchoes(groupId) }.getOrDefault(0L)
        Journal.note(LogCode.STATES, "открытая группа — забрали новое", "группа" to groupId.take(8), "новых" to added, "своих двойников убрано" to echoes)
        return newest
    }

    /**
     * История из копии ключей (модель Matrix, М3) — на свежем устройстве с фразой.
     *
     * Сначала ключи групп всех версий (тогда и история групп откроется), потом личные
     * переписки: страница копии → своя обёртка ([KeyCopy.restoreFor]) → очередь входящих → тот
     * же разбор, что у живых. Срока в 90 дней здесь нет: копия живёт столько же, сколько сами
     * сообщения на сервере (Р45).
     *
     * @return сколько личных сообщений записано впервые; `null` — копию не прочитать.
     */
    suspend fun pullFromCopy(copy: io.tima.core.encryption.DeviceIdentity): Int? {
        val groupKeys = network.keyCopy.groupKeys() ?: return null
        var keys = 0
        for (item in groupKeys) {
            val key = io.tima.core.encryption.KeyCopy.openGroupKey(copy, item.wrapped) ?: continue
            environment.groupKeyBook.put(item.groupId, item.gkVersion, key)
            keys++
        }
        // Переписки всех личностей аккаунта (М6, Р55): после перерегистрации копия прежней
        // перенесена в свою, её переписки склеиваются с перепиской новой.
        val chats = network.history.personalChats(allIdentities = true) ?: return null
        val prior = chats.map { it.ownerId }.filter { it.isNotBlank() && it != session.userId }.toSet()
        prior.forEach(environment.priorIdentities::add)
        var added = 0
        val rehome = mutableListOf<Pair<String, String>>()
        for (chat in chats) {
            var before = 0L
            val me = setOf(session.userId) + prior
            var peer = chat.peerId.takeIf { it !in me }
            while (true) {
                val page = network.keyCopy.page(chat.chatId, before) ?: break
                if (page.isEmpty()) break
                for (item in page) {
                    val stored = io.tima.core.encryption.KeyCopy.restoreFor(
                        item.envelope, item.wrapEphemeral, copy, session.deviceId, identity.encryptionPublic,
                    ) ?: continue
                    val sender = envelopeSender(stored) ?: continue
                    if (peer == null && sender.userId !in me) peer = sender.userId
                    captionKey(sender.userId, sender.deviceId)
                    if (environment.incoming.receive(chat.chatId, item.messageId, stored, sentAtMs = sender.createdAtMs)) added++
                }
                if (page.size < io.tima.core.network.KeyCopyApi.PAGE) break
                before = page.last().messageId
            }
            if (peer != null && !environment.chatFacts.knows(chat.chatId)) {
                book.remember(chatId = chat.chatId, kind = ChatKind.Personal, title = network.directory.nameOrNumber(peer), peerId = peer)
            }
            if (peer != null && chat.ownerId.isNotBlank() && chat.ownerId != session.userId) rehome += chat.chatId to peer
        }
        if (added > 0) drainIncoming()
        // Переписка прежней личности — под переписку новой с тем же собеседником: человек один.
        if (rehome.isNotEmpty()) {
            val move = io.tima.core.database.SqlChatRehome(environment.db)
            for ((chatId, peer) in rehome) {
                runCatching { move.rehome(chatId, io.tima.core.encryption.PersonalChatIdsOverKodium.personalChatId(session.userId, peer), peer, peer) }
            }
        }
        Journal.note(LogCode.DEVICE_TRUST, "история из копии забрана", "ключей групп" to keys, "сообщений" to added)
        return added
    }

    /** Все свои группы (ИУ3): строки списка — сверкой групп, затем история каждой. */
    suspend fun pullAllGroupHistory(): Int? {
        val groups = (network.groups.mine() as? GroupsResult.Groups)?.groups ?: return null
        syncGroups()
        var added = 0
        for (group in groups) added += pullGroupHistory(group.groupId)
        return added
    }

    /**
     * Разобрать всё, что ждёт в очереди, — каждую запись своим путём. В отличие от разбора
     * при живом событии, ключ подписи берётся у отправителя ЭТОЙ записи, а не пришедшей.
     */
    private suspend fun drainIncoming() {
        val held = heldChats()
        while (true) {
            environment.incoming.openNext(held) { entry ->
                val outcome = if (GroupFrame.isGroupFrame(entry.envelope)) {
                    openGroup(entry)
                } else {
                    val s = envelopeSender(entry.envelope)
                    val key = s?.let { senderKeys[it.deviceId] }
                    when {
                        s == null -> OpenOutcome.Rejected("конверт не разбирается")
                        key != null -> open(entry, key.signingPub, unverified = !key.confirms)
                        // Ключа нет ни у кого — без проверки подписи, «Не подтверждено» (Р10, КК13).
                        s.deviceId in absentSenders -> open(entry, null, unverified = true)
                        else -> OpenOutcome.NoKey("ключ подписи отправителя не получен")
                    }
                }
                noted(entry, outcome)
            } ?: break
        }
    }

    /**
     * @param captionKey ключ подписи автора; `null` — его нет ни у кого, открыть без проверки подписи (КК13).
     * @param unverified пометить «Не подтверждено»: без проверки или подпись отозванного по краже (Р11).
     */
    private fun open(entry: IncomingEntry, captionKey: ByteArray?, unverified: Boolean = false): OpenOutcome =
        (if (captionKey == null) {
            PersonalMessages.openUnverified(entry.envelope, session.deviceId, identity)
        } else {
            PersonalMessages.open(
                envelopeBytes = entry.envelope,
                myDeviceId = session.deviceId,
                me = identity,
                senderSigningPublic = captionKey,
            )
        }).fold(
            // Записываются БАЙТЫ ТЕЛА, как пришли, а не текст: столбец читается кодеком,
            // и запись текстом означала бы «расшифровано и не читается» — состояние, в
            // котором это и нашлось на живом прогоне.
            // Вид берётся отсюда и только отсюда: подпись сошлась, значит метаданным
            // можно верить. Метку внутри тела отправитель волен сочинить сам, и
            // «системное сообщение от TIMa» рисовал бы кто угодно (Л14).
            onSuccess = {
                // Пришедшее из истории или из самой копии в копии уже есть — второй раз не подаём,
                // иначе восстановление давало бы по запросу на каждое сообщение.
                if (!io.tima.core.encryption.HistoryFrame.isHistory(entry.envelope)) keyCopy?.feedMessage(entry.chatId, entry.envelope)
                // Ключ сообщения — на устройство (КК9): отдать своему и положить в копию после срока обёрток.
                keepKey(entry.chatId, entry.messageId, entry.envelope)
                // Фото и файлы вне пузыря — забрать сразу, «приди и получи» (Решения §6).
                postman.received(io.tima.core.encryption.TextBodyCodec.decodeLine(it.body)?.rich)
                // Правка, заменившая фото (ADR-0052), — новое фото забирается так же сразу.
                if (it.meta.editOf > 0u) postman.receivedMedia(io.tima.core.encryption.TextBodyCodec.editedMedia(it.body))
                OpenOutcome.Opened(it.body, it.meta.senderId, kind = it.meta.kind, editOf = it.meta.editOf.toLong(), unverified = unverified)
            },
            // Подпись не сошлась или обёртки для нас нет — разные беды, и причина
            // доносится дословно: человеку видно «не читается», нам — почему.
            onFailure = { OpenOutcome.NoKey(it.message ?: "не открылось") },
        )

    /**
     * Своё ли это эхо — конверт, отправленный **с этого самого устройства**.
     *
     * Отдельная функция с именем, а не условие в потоке: правило продукта, и его надо
     * читать. Копия с ДРУГОГО своего устройства — не эхо, а настоящее сообщение, которое
     * человек написал сам с телефона и хочет видеть на ПК; показывать его надо своим, а не
     * чужим, и это отдельная работа (привязка второго устройства, К5.1).
     */
    internal fun ownCopy(senderDeviceId: String?): Boolean = senderDeviceId == session.deviceId

    /** Кто прислал — по открытой части конверта. Доверенным станет после проверки подписи. */
    private fun envelopeSender(envelope: ByteArray): SentBy? =
        PersonalMessages.peekSender(envelope)?.let {
            SentBy(userId = it.userId, deviceId = it.deviceId, createdAtMs = it.createdAtMs, editOf = it.editOf)
        }

    /** О каком сообщении и причине уже сказано за этот запуск — повтор разбора не дублирует строку. */
    private val unreadableSaid = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /**
     * Не открылось — причину в журнал (`MSG-UNREADABLE`, 2026-10-06): без неё «сообщение
     * недоступно» в отчёте разобрать нечем. Подпись не сошлась — беда: возможна подмена.
     */
    internal fun noted(entry: IncomingEntry, outcome: OpenOutcome): OpenOutcome {
        val (reason, forged) = when (outcome) {
            is OpenOutcome.NoKey -> outcome.reason to false
            is OpenOutcome.Rejected -> outcome.reason to true
            else -> return outcome
        }
        val said = "${entry.chatId}/${entry.messageId}/$reason"
        val first = said !in unreadableSaid.getAndUpdate { it + said }
        if (!first) return outcome
        val fields = arrayOf(
            "переписка" to entry.chatId.take(8),
            "сообщение" to entry.messageId,
            "попытка" to entry.attempts + 1,
            "причина" to reason,
            // Первая причина рядом с последней (беда 2026-10-24, вариант 5): по ней видно, с
            // чего началось, когда повтор упёрся уже в другое.
            "первая причина" to (entry.firstReason ?: reason),
        )
        if (forged) {
            Journal.trouble(LogCode.MSG_UNREADABLE, "сообщение не открылось — проверка не прошла", *fields)
        } else {
            Journal.note(LogCode.MSG_UNREADABLE, "сообщение не открылось", *fields)
        }
        return outcome
    }

    /**
     * Разобрать недоступное заново (заказчик 2026-10-06): сервер ответил на просьбу о ключах,
     * что обёртка у этого устройства уже есть, — значит, дело не в ключе сообщения. Чаще всего
     * не было ключа подписи отправителя: его устройство не было заверено, а в «требовать»
     * подпись незаверенного не принимается. Заверили — ключ подписи теперь дадут.
     *
     * До этого повтор не вызывался нигде: однажды недоступное оставалось таким навсегда.
     *
     * @return сколько записей открылось.
     */
    suspend fun reopenUnreadable(): Int = reopenLock.withLock {
        // Заодно — непроверенное (КК13): поводы те же — пришли ключи, ответили на просьбу.
        runCatching { reverifyUnconfirmed() }
        val stuck = environment.incoming.undecryptable()
        if (stuck.isEmpty()) return@withLock 0
        // Ключ подписи — и у кадра группы (план КК, КК2; беда 2026-10-24, вариант 1): до этого
        // группа пропускалась, и её недоступное с причиной «ключ подписи не получен» не
        // открывалось ни повтором, ни «Запросить ключ».
        for (entry in stuck) {
            if (GroupFrame.isGroupFrame(entry.envelope)) {
                GroupFrame.parse(entry.envelope)?.let { captionKey(it.senderId, it.senderDevice) }
                continue
            }
            val s = envelopeSender(entry.envelope) ?: continue
            captionKey(s.userId, s.deviceId)
        }
        environment.incoming.retryUndecryptable()
        drainIncoming()
        // Чем кончился каждый кадр (вариант 5): одна строка на сообщение, а не только итог.
        var opened = 0
        for (entry in stuck) {
            val now = environment.incoming.entry(entry.chatId, entry.messageId) ?: continue
            if (now.state != io.tima.core.outbox.IncomingState.UNDECRYPTABLE) opened++
            Journal.note(
                LogCode.MSG_UNREADABLE, "повтор разбора",
                "переписка" to entry.chatId.take(8), "сообщение" to entry.messageId,
                "исход" to if (now.state == io.tima.core.outbox.IncomingState.UNDECRYPTABLE) "не открылось" else "открылось",
                "причина" to (now.undecryptableReason ?: "-"), "первая причина" to (now.firstReason ?: entry.undecryptableReason ?: "-"),
            )
        }
        Journal.note(LogCode.DEVICE_TRUST, "недоступное разобрано заново", "было" to stuck.size, "осталось" to stuck.size - opened)
        opened
    }

    /** Повтор идёт из канала (ключи группы), из «Запросить ключ» и с экрана — не вперемешку. */
    private val reopenLock = kotlinx.coroutines.sync.Mutex()

    /**
     * Ключ подписи устройства автора: память → местная база → сервер, с отозванными (план КК, КК13).
     *
     * Записанный ключ закрепляется: другой ключ у того же устройства — тревога, принимается прежний.
     * Сервер ответил, а устройства нет ни среди действующих, ни среди отозванных — оно в [absentSenders].
     */
    private suspend fun captionKey(userId: String, deviceId: String): io.tima.core.database.SenderKey? {
        senderKeys[deviceId]?.let { return it }
        runCatching { storedKeys.get(deviceId) }.getOrNull()?.let {
            senderKeys[deviceId] = it
            return it
        }
        val outcome = network.keys.devicesOf(userId, withRevoked = true)
        if (outcome !is DeviceKeysResult.Devices) return null
        // Подпись принимается только от доверенного устройства (ДУ3): сообщение с устройства
        // вора в строгом режиме не проходит проверку подписи.
        val admitted = environment.trustGate.admit(userId, outcome).map { io.tima.core.database.SenderKey(it.deviceId, userId, it.signingPub) } +
            environment.trustGate.admitRevoked(userId, outcome).map {
                io.tima.core.database.SenderKey(it.device.deviceId, userId, it.device.signingPub, it.revokedAtMs, it.reason)
            }
        for (key in admitted) {
            val same = runCatching { storedKeys.put(key) }.getOrDefault(true)
            if (!same) {
                Journal.trouble(LogCode.DEVICE_TRUST, "ключ подписи устройства не тот, что при первой встрече — сервер подменил?", "кто" to userId.take(8), "устройство" to key.deviceId.take(8))
                continue
            }
            senderKeys[key.deviceId] = runCatching { storedKeys.get(key.deviceId) }.getOrNull() ?: key
        }
        val listed = outcome.devices.any { it.deviceId == deviceId } || outcome.revoked.any { it.device.deviceId == deviceId }
        if (!listed) absentSenders += deviceId
        return senderKeys[deviceId]
    }

    /**
     * «Открыть через фразу» (план КК, КК1 и КК4): пара копии выводится из слов этого запуска, ключи берутся из
     * копии, пара забывается. Личная — страница копии переписки → своя обёртка → очередь → обычный разбор с
     * проверкой подписи и обязательства; группа — версии ключа из копии → повтор недоступного.
     */
    suspend fun openFromCopy(chatId: String, words: List<String>, group: Boolean): io.tima.domain.chat.PhraseOpen {
        val published = when (val c = network.keyCopy.current()) {
            is io.tima.core.network.KeyCopyApi.Current.Published -> c.key
            io.tima.core.network.KeyCopyApi.Current.Missing -> return io.tima.domain.chat.PhraseOpen.NoCopy
            io.tima.core.network.KeyCopyApi.Current.Unknown -> return io.tima.domain.chat.PhraseOpen.Offline
        }
        val copy = io.tima.core.encryption.KeyCopy.fromWords(words, published.epoch) ?: return io.tima.domain.chat.PhraseOpen.WrongPhrase
        if (!copy.encryptionPublic.contentEquals(published.pub)) return io.tima.domain.chat.PhraseOpen.WrongPhrase
        val before = environment.incoming.undecryptable().count { it.chatId == chatId }
        if (group) {
            val items = network.keyCopy.groupKeys() ?: return io.tima.domain.chat.PhraseOpen.Offline
            for (item in items.filter { it.groupId == chatId }) {
                val key = io.tima.core.encryption.KeyCopy.openGroupKey(copy, item.wrapped) ?: continue
                environment.groupKeyBook.put(item.groupId, item.gkVersion, key)
            }
            reopenUnreadable()
        } else {
            val stuck = environment.incoming.undecryptable().filter { it.chatId == chatId }.map { it.messageId }.toHashSet()
            var cursor = 0L
            while (stuck.isNotEmpty()) {
                val page = network.keyCopy.page(chatId, cursor) ?: return io.tima.domain.chat.PhraseOpen.Offline
                if (page.isEmpty()) break
                for (item in page) {
                    if (item.messageId !in stuck) continue
                    val stored = io.tima.core.encryption.KeyCopy.restoreFor(
                        item.envelope, item.wrapEphemeral, copy, session.deviceId, identity.encryptionPublic,
                    ) ?: continue
                    sender(stored)?.let { captionKey(it.userId, it.deviceId) }
                    if (environment.incoming.replaceUndecryptable(chatId, item.messageId, stored)) stuck -= item.messageId
                }
                if (page.size < io.tima.core.network.KeyCopyApi.PAGE) break
                cursor = page.last().messageId
            }
            drainIncoming()
        }
        val opened = before - environment.incoming.undecryptable().count { it.chatId == chatId }
        Journal.note(LogCode.DEVICE_TRUST, "недоступное из копии по фразе", "переписка" to chatId.take(8), "было" to before, "открылось" to opened)
        return io.tima.domain.chat.PhraseOpen.Opened(opened)
    }

    private fun sender(stored: ByteArray) = envelopeSender(stored)

    /** Ключ сообщения из своей обёртки — на устройство (КК9). Не развернулся — ничего: текст уже открыт. */
    private fun keepKey(chatId: String, messageId: Long, stored: ByteArray) {
        val key = io.tima.core.encryption.HistoryKeys.keyOfStored(stored, session.deviceId, identity) ?: return
        runCatching { messageKeys.put(chatId, messageId, key) }
    }

    /**
     * Собрать ключи уже прочитанного из своих живых обёрток на сервере — один раз после обновления (план КК,
     * КК9). До КК9 устройство ключей не хранило; без сбора стирание доставленных обёрток (КК10) унесло бы
     * последний источник ключа старой переписки.
     *
     * @return сколько ключей собрано; `null` — список переписок не получен, повторить позже.
     */
    suspend fun harvestMessageKeys(): Int? {
        val chats = network.history.personalChats(allIdentities = true) ?: return null
        var kept = 0
        var pages = 0
        for (chat in chats) {
            var before = 0L
            while (true) {
                val page = network.history.page(chat.chatId, before) ?: break
                pages++
                if (page.isEmpty()) break
                for (item in page) {
                    if (runCatching { messageKeys.get(chat.chatId, item.messageId) }.getOrNull() != null) continue
                    val key = io.tima.core.encryption.HistoryKeys.keyOf(item.envelope, item.wrapEphemeral, session.deviceId, identity) ?: continue
                    runCatching { messageKeys.put(chat.chatId, item.messageId, key) }.onSuccess { kept++ }
                }
                if (page.size < io.tima.core.network.HistoryApi.PAGE) break
                before = page.last().messageId
            }
        }
        Journal.note(LogCode.DEVICE_TRUST, "ключи прочитанного собраны на устройство", "переписок" to chats.size, "страниц" to pages, "ключей" to kept)
        return kept
    }

    /**
     * Проверить подпись у открытого без проверки (план КК, КК13): ключ пришёл — сошлась — пометка снимается;
     * не сошлась — содержимое прячется, строка снова нечитаемая. Ключа всё ещё нет или он от отозванного по
     * краже — ждёт дальше.
     *
     * @return сколько подтверждено.
     */
    suspend fun reverifyUnconfirmed(): Int {
        val stash = runCatching { environment.incoming.unverifiedEnvelopes() }.getOrDefault(emptyList())
        var confirmed = 0
        for (item in stash) {
            val group = if (GroupFrame.isGroupFrame(item.envelope)) GroupFrame.parse(item.envelope) else null
            val sender = if (group != null) group.senderId to group.senderDevice else envelopeSender(item.envelope)?.let { it.userId to it.deviceId }
            if (sender == null) continue
            val key = captionKey(sender.first, sender.second) ?: continue
            if (!key.confirms) continue
            val matches = if (group != null) {
                GroupMessages.signatureMatches(
                    groupId = group.groupId, senderId = group.senderId, senderDevice = group.senderDevice, kind = group.kind,
                    createdAtUnixMs = group.createdAtUnixMs, threadRoot = group.threadRoot, replyTo = group.replyTo,
                    gkVersion = group.gkVersion, payload = group.payload, signature = group.signature,
                    senderSigningPublic = key.signingPub, keyCommitment = group.keyCommitment, editOf = group.editOf,
                )
            } else {
                PersonalMessages.signatureMatches(item.envelope, key.signingPub)
            }
            if (matches) {
                environment.incoming.verified(item.chatId, item.messageId)
                confirmed++
            } else {
                environment.incoming.forged(item.chatId, item.messageId, item.envelope, "подпись не сходится")
                Journal.trouble(LogCode.MSG_UNREADABLE, "подпись непроверенного не сошлась — содержимое скрыто", "переписка" to item.chatId.take(8), "сообщение" to item.messageId)
            }
        }
        if (stash.isNotEmpty()) Journal.note(LogCode.DEVICE_TRUST, "непроверенное проверено заново", "было" to stash.size, "подтверждено" to confirmed)
        return confirmed
    }

    private class SentBy(val userId: String, val deviceId: String, val createdAtMs: Long, val editOf: Long = 0)

    internal companion object {
        /**
         * Сколько ждать перед новой попыткой — У11.
         *
         * Основание берётся у состояния связи, а рост — от числа неудач подряд: одно
         * мигание сети и стена у оператора выглядят одинаково ровно первую секунду, и
         * различает их только то, что стена не проходит.
         *
         * Потолок не опускает того, что сказало состояние: у `BLOCKED` своя пауза
         * больше потолка, и урезать её значило бы вернуться к долблению в стену.
         */
        internal fun retryPause(link: LinkState, streak: Int): Long {
            val base = link.retryDelayMs
            val grown = base shl (streak - 1).coerceIn(0, 6)
            return maxOf(base, minOf(grown, RETRY_CEILING_MS))
        }

        /**
         * Потолок паузы между подъёмами — У11.
         *
         * Минута: дольше означало бы, что вернувшаяся сеть ждёт до минуты, а человек
         * за это время успевает решить, что приложение сломано.
         */
        const val RETRY_CEILING_MS = 60_000L

        /** Сколько попытка может не открыть канал при живой сети, прежде чем сторож её оборвёт. */
        const val STUCK_MS = 90_000L

        /** Как часто сторож смотрит на попытку. */
        const val STUCK_CHECK_MS = 15_000L

        /**
         * Сколько канал должен прожить, чтобы счёт неудач обнулился.
         *
         * Полминуты: короче — и обнуление сработает на канале, который поднялся и сразу
         * упал, то есть счёт неудач перестанет считать неудачи.
         */
        const val LIVED_LONG_ENOUGH_MS = 30_000L
    }
}

/** Что сервер приложил к сообщению об отправителе: счётчик профиля и цвет в группе. */
data class SenderStamp(val userId: String, val profileRev: Int?, val groupId: String?, val hue: Int?)

/** Где лежит свой номер ленты звонков (ВЗ0а). У каждого устройства свой. */
private const val CALLS_CTS = "calls.cts"

/** «О пропущенных уведомил до…» — время сервера, мс. Для дороги через журнал звонков. */
private const val CALLS_MISSED_MARK = "calls.missedMark"

/** Сколько строк журнала смотреть при разрыве: за сутки больше не пропускают. */
private const val MISSED_PAGE = 50

/** Звонящих сразу больше пары не бывает: хватает первых строк журнала. */
private const val RINGING_PAGE = 5

/** Два срока звонка на сервере (`ringDeadline`, 50 с) — дальше `ringing` уже неправда. */
private const val RINGING_FRESH_MS = 100_000L

/**
 * С какого номера забирать ленту звонков; `null` — забирать нечего.
 *
 * **Подсказка младше своего номера — почти всегда опоздавшая, а не сброс** (заказчик
 * 2026-09-30, 1а). Открыли «Звонки» с 14 пропущенными — сервер пишет 14 изменений `seen` и
 * шлёт 14 подсказок, каждую со своим номером. Первая забирает ленту до конца, остальные
 * приходят с номерами ниже. До 2026-09-30 каждая такая считалась «сервер начал ленту
 * заново»: номер в ноль, полный проход и запрос имени на каждый пропущенный — ПК за две
 * минуты сделал 15 проходов и 218 запросов имён.
 *
 * Поэтому младшая подсказка сверяется с вершиной у самого сервера ([realTop] — один
 * короткий запрос). Ниже своего номера она бывает, только если сервер и правда начал ленту
 * заново (данные стёрты, Plan §0.0 решение 2) — тогда с нуля, иначе ждать своего номера
 * пришлось бы вечно.
 */
internal suspend fun callsFrom(hint: Long, mine: Long, realTop: suspend () -> Long?): Long? {
    if (hint > mine) return mine
    if (hint == mine) return null
    val real = realTop() ?: return null
    return when {
        real < mine -> {
            Journal.note(LogCode.CALL, "лента звонков начата заново сервером", "мой" to mine, "вершина" to real)
            0
        }
        real > mine -> mine
        else -> null
    }
}

/** Пропущенный кончился до входа устройства в аккаунт (время неизвестно — не до). */
internal fun missedBeforeDevice(atMs: Long, since: Long): Boolean = atMs in 1 until since

/** Вид сохранённого ответа сервера по номеру (ПЛАН-(УК) УК1): кадр группы или открытая запись. */
private const val REF_FRAME = "frame"
private const val REF_OPEN = "open"
