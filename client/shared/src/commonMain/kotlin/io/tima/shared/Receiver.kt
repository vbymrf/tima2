package io.tima.shared

import io.tima.core.network.HistoryApi
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
     * Зовут в групповой звонок (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ ГЗ3): `(callId, fromUserId, kind,
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
    /** Заявка новой личности в группу — решать владельцу или модератору (ДУ6). */
    private val onIdentityClaim: (String) -> Unit = {},
    /**
     * Своё устройство передало историю переписки (ИУ3). Забирать её здесь нельзя: страниц
     * может быть много, а канал всё это время стоял бы.
     */
    private val onHistoryReady: (String) -> Unit = {},
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
    @Volatile
    private var live = false
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
            is EventStreamProtocol.Decision.IdentityClaim -> {
                Journal.note(LogCode.DEVICE_TRUST, "заявка новой личности в группу", "группа" to decision.groupId.take(8))
                onIdentityClaim(decision.groupId)
            }
            is EventStreamProtocol.Decision.HistoryReady -> {
                Journal.note(LogCode.DEVICE_TRUST, "своё устройство передало историю", "переписка" to decision.chatId.take(8))
                onHistoryReady(decision.chatId)
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
                onComment = { decision -> aboutComment(decision) },
                onStoreChanged = { decision -> onStoreChanged(decision.kind, decision.revision) },
                onCall = { decision -> aboutCall(decision) },
                onCallsTop = { top -> callsTop(top) },
                onOpen = { live = true },
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
                        while (true) {
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
            )
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
        notices?.arrived(groupId, parsed?.senderId, ref = "$groupId/$messageId", sentAtMs = parsed?.createdAtUnixMs ?: 0, group = true)

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
        notices?.arrived(chatId, sender?.userId, ref = "$chatId/$messageId", sentAtMs = sender?.createdAtMs ?: 0)

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

    /**
     * История переписки с сервера (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ИУ1, ИУ3).
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
     * Разобрать всё, что ждёт в очереди, — каждую запись своим путём. В отличие от разбора
     * при живом событии, ключ подписи берётся у отправителя ЭТОЙ записи, а не пришедшей.
     */
    private suspend fun drainIncoming() {
        val held = heldChats()
        while (true) {
            environment.incoming.openNext(held) { entry ->
                if (GroupFrame.isGroupFrame(entry.envelope)) {
                    openGroup(entry)
                } else {
                    val s = envelopeSender(entry.envelope)
                    val key = s?.let { senderKeys[it.deviceId] }
                    when {
                        s == null -> OpenOutcome.Rejected("конверт не разбирается")
                        key == null -> OpenOutcome.NoKey("ключ подписи отправителя не получен")
                        else -> open(entry, key)
                    }
                }
            } ?: break
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
        // Подпись принимается только от доверенного устройства (ДУ3): сообщение с устройства
        // вора в строгом режиме не проходит проверку подписи.
        for (device in environment.trustGate.admit(userId, outcome)) {
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
