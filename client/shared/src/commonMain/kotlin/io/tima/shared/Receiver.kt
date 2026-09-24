package io.tima.shared

import io.tima.core.database.SqlChatBook
import io.tima.core.encryption.GroupMessages
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
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
import io.tima.domain.account.Session
import io.tima.domain.chat.AutoReplyBlocked
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.SyncGroupChats
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    /**
     * Кому сказать, что под записью ответили: `(channelId, postId)`.
     *
     * Приёмник не знает ни экранов, ни состояний — он только приносит. По умолчанию
     * ничего: канал обязан работать и там, где страницы на экране нет.
     */
    private val onComment: (String, Long) -> Unit = { _, _ -> },
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
        private set

    /** Ключи подписи по устройству отправителя: спрашиваются один раз на устройство. */
    private val senderKeys = HashMap<String, ByteArray>()

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
                    else -> {
                        notices?.calling(decision.callId, call.initiatorId)
                        onCall(decision.callId, call.initiatorId, if (call.video) "video" else "audio")
                    }
                }
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
    private suspend fun runChannel(): StreamOutcome =
        network.eventChannel()
            .run(
                cursor = null,
                onGroupKeys = { decision -> aboutKeys(decision) },
                onLevelNarrowed = { decision -> aboutLevel(decision) },
                onComment = { decision -> aboutComment(decision) },
                onCall = { decision -> aboutCall(decision) },
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
                    try {
                        runChannel()
                    } finally {
                        breaker.cancel()
                    }
                }
            }
            // Отмена всего приёмника (выход из аккаунта) не должна проглатываться
            // `runCatching` и крутить цикл дальше.
            currentCoroutineContext().ensureActive()
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
            val link = classifyFailure(outcome.exceptionOrNull())
            val pause = retryPause(link, streak)
            Journal.note(
                LogCode.NET_CHANNEL,
                "живой канал оборвался, поднимаю заново",
                "исход" to (lastOutcome ?: "—"),
                "связь" to link.name,
                "подряд" to streak,
                "пауза" to pause,
            )
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
            groupsSync.refresh()
        }
    }

    private fun openGroup(entry: IncomingEntry): OpenOutcome {
        val frame = GroupFrame.parse(entry.envelope)
            ?: return OpenOutcome.Rejected("кадр группы не разбирается")
        // Открытому сообщению (уровни 0…3) ключ не нужен: его читает тот, кому ключа не
        // дадут, — описание личной группы, лента публичной. Подпись при этом проверяется
        // так же строго.
        val plain = frame.gkVersion == 0
        val groupKey = if (plain) ByteArray(0) else {
            groupKeys.key(frame.groupId, frame.gkVersion)
                ?: return OpenOutcome.NoKey("нет ключа версии ${frame.gkVersion}")
        }
        val captionKey = senderKeys[frame.senderDevice]
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
        ).fold(
            onSuccess = { OpenOutcome.Opened(it.body, it.meta.senderId, frame.level, frame.threadRoot) },
            onFailure = { OpenOutcome.Rejected("сообщение группы не открылось: ${it.message}") },
        )
    }

    /** Кадр про групповые ключи: исход остаётся в диагностике, канал не роняется. */
    private suspend fun aboutKeys(decision: EventStreamProtocol.Decision) {
        keyOrchestrator.handle(decision)?.let { lastOutcome = it }
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
        notices?.arrived(chatId, sender?.userId)

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
            val outcome = when {
                sender == null -> OpenOutcome.Rejected("конверт не разбирается")
                key == null -> OpenOutcome.NoKey("ключ подписи отправителя не получен")
                else -> open(entry, key)
            }
            if (outcome is OpenOutcome.Opened) opened = entry.chatId to outcome.senderId
            outcome
        }
        // ── ВТОРАЯ СТАДИЯ: ПОДПИСЬ СОШЛАСЬ, ИМЯ МОЖНО НАЗВАТЬ (У6) ──────────
        //
        // Та же строка по тому же ключу, а не вторая: два уведомления об одном
        // сообщении человек читает как два сообщения.
        opened?.let { (chat, author) -> notices?.opened(chat, author) }

        // Переписка от незнакомого — со своим именем: иначе в списке появится строка без
        // имени, и человек не узнает, кто написал.
        if (sender != null && !environment.chatFacts.knows(chatId)) {
            book.remember(
                chatId = chatId,
                kind = ChatKind.Personal,
                title = network.directory.nameOrNumber(sender.userId),
                peerId = sender.userId,
            )
        }
    }

    private fun open(entry: IncomingEntry, captionKey: ByteArray): OpenOutcome =
        PersonalMessages.open(
            envelopeBytes = entry.envelope,
            myDeviceId = session.deviceId,
            me = identity,
            senderSigningPublic = captionKey,
        ).fold(
            // Записываются БАЙТЫ ТЕЛА, как пришли, а не текст: столбец читается кодеком,
            // и запись текстом означала бы «расшифровано и не читается» — состояние, в
            // котором это и нашлось на живом прогоне.
            // Вид берётся отсюда и только отсюда: подпись сошлась, значит метаданным
            // можно верить. Метку внутри тела отправитель волен сочинить сам, и
            // «системное сообщение от TIMa» рисовал бы кто угодно (Л14).
            onSuccess = { OpenOutcome.Opened(it.body, it.meta.senderId, kind = it.meta.kind) },
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
            SentBy(userId = it.userId, deviceId = it.deviceId, createdAtMs = it.createdAtMs)
        }

    private suspend fun captionKey(userId: String, deviceId: String): ByteArray? {
        senderKeys[deviceId]?.let { return it }
        val outcome = network.keys.devicesOf(userId)
        if (outcome !is DeviceKeysResult.Devices) return null
        for (device in outcome.devices) {
            senderKeys[device.deviceId] = device.signingPub
        }
        return senderKeys[deviceId]
    }

    private class SentBy(val userId: String, val deviceId: String, val createdAtMs: Long)

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
