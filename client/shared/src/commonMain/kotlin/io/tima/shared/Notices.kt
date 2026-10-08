package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.network.ServerClock
import io.tima.core.notify.BackgroundWatch
import io.tima.core.notify.CallAlert
import io.tima.core.notify.Notice
import io.tima.core.notify.NoticeKind
import io.tima.core.notify.Notifier
import io.tima.core.notify.SoundChoice
import io.tima.core.notify.SoundGate
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.BookList
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.NoticeCounts
import io.tima.domain.chat.NoticeFrom
import io.tima.domain.chat.NoticeJournal
import io.tima.domain.chat.NoticeRecord
import io.tima.domain.chat.NoticeTab
import io.tima.domain.chat.NoticeWhat
import io.tima.domain.chat.PersonLook
import io.tima.domain.chat.line
import io.tima.feature.chat.PERSON_FIRST_LINE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Что заслуживает уведомления — ПЛАН-(У)-УВЕДОМЛЕНИЙ.md, У5…У10, и как оно считается и звучит —
 * ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ0…ЖУ6.
 *
 * Правила живут здесь, а не в `core-notify`, потому что они знают то, чего показу знать
 * незачем: кто заблокирован, какое устройство своё и сошлась ли подпись.
 *
 * ── ЖУРНАЛ — ИСТОЧНИК ЧИСЕЛ (ЖУ1) ───────────────────────────────────────────
 *
 * Каждое событие — строка [NoticeJournal]: откуда пришло, что с ним сделали, когда и чем
 * снято. Строка в шторке, число на ней и на значке считаются **из журнала**, а не по
 * событию. Повтор того же события (разрыв ленты, два прохода разом) журнал узнаёт и
 * второй раз не уведомляет (ЖУ0).
 *
 * ── СТРОКА НА ВКЛАДКУ, А НЕ НА СУЩНОСТЬ (ЖУ4) ───────────────────────────────
 *
 * «Сообщения от 2 пользователей», «Пропущенные звонки от redmi» — по строке на вкладку, с
 * числом вкладки (заказчик 2026-09-30: «Сколько в вкладке, столько и в шторке»). Строка с
 * одной сущностью называет её — после проверки подписи, как и раньше.
 *
 * ── ЗВУК — ОДИН НА ПАЧКУ (ЖУ3) ──────────────────────────────────────────────
 *
 * Звучит только **новое в журнале** — уведомление, которого у сущности ещё не было, — и
 * только **свежее**: догонка (запуск, разрыв канала, очередь) молчит. Сигналы разделяет
 * [SoundGate]: перерыв 5 с от конца сигнала, пропущенное не ждёт.
 *
 * ── ДВЕ СТАДИИ ОДНОЙ СТРОКИ (У6) ────────────────────────────────────────────
 *
 * ```
 * конверт пришёл       →  «Новое сообщение»   [arrived]
 * подпись сошлась      →  «Борис»             [opened] — ТА ЖЕ строка, молча
 * подпись не сошлась   →  остаётся безымянной
 * ```
 *
 * Показать имя сразу нельзя — оно лежит в **открытой** части конверта, подписью не
 * покрыто. Ждать разбора тоже нельзя — при недоехавшем ключе человек не узнал бы ничего.
 * До 2026-09-30 вторая стадия звучала второй раз: каждое сообщение — два сигнала.
 *
 * ── ЗВОНОК — НАОБОРОТ, И ЭТО ЗАКОННО ────────────────────────────────────────
 *
 * Кто звонит, говорит **сервер**: строку в `calls` заводит он, а не звонящий. Значит имя
 * показывается сразу.
 */
class Notices(
    private val notifier: Notifier,
    /** Кто я: своё сообщение с другого своего устройства уведомления не заслуживает. */
    private val me: String,
    /** Строка книги про человека; `null` — его в книге нет вовсе. */
    private val entryOf: suspend (String) -> BookEntry?,
    /**
     * Карточка человека со справочника — ради **ника незнакомца** (У9).
     *
     * `null` — не спросили или он не ответил. Тогда строка остаётся безымянной, и это
     * правильнее выдуманного имени.
     */
    private val cardOf: suspend (String) -> ChatPerson?,
    /** Чем звать человека: тот же порядок полей, что в списках (`Вид`). */
    private val look: suspend () -> PersonLook,
    private val words: () -> Words = { CurrentWords.value },
    /**
     * Мелодия звонка от человека: своя у контакта, иначе общая (ВЗ4, ВЗ8). По умолчанию —
     * системная: проверкам и платформам без настроек выбирать не из чего.
     */
    private val ringFor: suspend (String) -> SoundChoice = { SoundChoice.Default },
    /** Общий звук уведомления о сообщении (ВЗ4). */
    private val messageSound: suspend () -> SoundChoice = { SoundChoice.Default },
    /** Журнал уведомлений (ЖУ1). По умолчанию — в памяти: так собираются проверки. */
    private val journal: NoticeJournal = MemoryNoticeJournal(),
    /** Время — серверное (ЖУ3: свежесть события меряется им, а не часами устройства). */
    private val now: () -> Long = { ServerClock.now() },
    /** Название группы — заголовок её строки, когда новое в одной группе. */
    private val groupTitle: suspend (String) -> String? = { null },
    /** Название канала — заголовок строки «Новое в канале» (ПЛАН-(ОУ)). */
    private val channelTitle: suspend (String) -> String? = { null },
    /**
     * Уведомления этой переписки отключены (ПЛАН-(ОУ) решение 5): `(переписка, группа ли)`.
     * Отключённое не звучит, не попадает в шторку и не считается.
     */
    private val mutedOf: (String, Boolean) -> Boolean = { _, _ -> false },
    /** Где снимать уведомления, когда зовут с экрана: база — не на потоке экрана. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * «Не беспокоить, в часы:» (заказчик 2026-10-01): что глушить сейчас — галочками
     * «Звонки» и «Сообщения». Числа и значок копятся как обычно.
     */
    private val quiet: suspend () -> QuietHours = { QuietHours() },
    /** Идёт ли звонок — тогда уведомления о сообщениях без звука (заказчик 2026-10-02). */
    private val inCall: () -> Boolean = { CallKeep.anyBusy() },
) {

    /**
     * Переписка, открытая прямо сейчас; `null` — ни одна.
     *
     * Уведомлять о том, что человек читает глазами, незачем: он уже узнал. Держится
     * здесь, а не проверяется на экране, потому что решает это **приёмник** — он
     * приходит в чужую минуту, и спрашивать у экрана ему не у кого.
     */
    private var openChat: String? = null

    /**
     * Видно ли окно приложения.
     *
     * Без этого открытая переписка молчала бы и **после того, как окно убрали**: человек
     * нажал «Домой» или закрыл окно в трей, а переписка по-прежнему числится открытой.
     */
    private var windowShown: Boolean = true

    /** Журнал, звук и строки — под одним замком: решение «звучать ли» читает журнал. */
    private val lock = Mutex()
    private val gate = SoundGate()

    /**
     * Проверенные имена: переписка → собеседник (после подписи, У6), звонивший → имя.
     * Нужны строке вкладки с одной сущностью: назвать её, а не «Новое сообщение».
     */
    private val named = mutableMapOf<String, String>()

    /** Сколько событий догонки промолчало подряд — строка журнала одна на пачку. */
    private var catchUpRun = 0

    /** Событие без номера (проверки, старые вызовы) — своя ссылка каждому, не повтор. */
    private var unnamedRefs = 0

    /** Число на значке, как его видит человек; `-1` — ещё не ставили. */
    private var badgeShown = -1

    /**
     * Конверт записан, подпись ещё не проверена — У6, первая стадия.
     *
     * @param ref какое сообщение: по нему журнал узнаёт повтор.
     * @param sentAtMs когда написано; старое — догонка, без звука (ЖУ3). `0` — не знаем,
     *   считается свежим.
     * @param group сообщение группы — вкладка «Группы».
     * @return `true`, если уведомление записано. Ложь — уведомлять было не о чем.
     */
    suspend fun arrived(
        chatId: String,
        senderId: String?,
        ref: String = "",
        sentAtMs: Long = 0,
        group: Boolean = false,
    ): Boolean {
        if (!shouldNotify(senderId)) return false
        if (mutedOf(chatId, group)) return false
        val tab = if (group) NoticeTab.Groups else NoticeTab.Chats
        return lock.withLock {
            val at = now()
            val fresh = sentAtMs <= 0 || at - sentAtMs <= FRESH_MS
            val record = NoticeRecord(
                tab = tab,
                entity = chatId,
                what = NoticeWhat.Message,
                ref = ref.ifBlank { "$chatId@$at#" + (++unnamedRefs) },
                atMs = at,
                from = if (fresh) NoticeFrom.Live else NoticeFrom.CatchUp,
            )
            val wasActive = journal.isActive(tab, chatId, NoticeWhat.Message)
            if (!journal.record(record)) return@withLock false
            if (isWatched(chatId)) {
                journal.clearEntity(tab, chatId, "открыта при приходе", at)
                journal.done(NoticeWhat.Message, record.ref, "тишина: переписка открыта")
                return@withLock false
            }
            showTab(tab, decide(record, wasActive, fresh), record)
            true
        }
    }

    /**
     * Новое в сущности «зашли, забрали» — открытой группе или канале (ПЛАН-(ОУ)): строка «Новое
     * в …» без текста. Автора нет — сообщения на телефоне ещё нет, проверять нечего; включены ли
     * уведомления, сервер уже сверил, а здесь — ещё раз по своей копии настройки.
     */
    suspend fun entityNew(kind: String, entityId: String, topId: Long): Boolean {
        val tab = if (kind == "channel") NoticeTab.Channels else NoticeTab.Groups
        if (mutedOf(entityId, true)) return false
        return lock.withLock {
            val at = now()
            val record = NoticeRecord(
                tab = tab,
                entity = entityId,
                what = NoticeWhat.Message,
                ref = "top/$entityId/$topId",
                atMs = at,
                from = NoticeFrom.Live,
            )
            val wasActive = journal.isActive(tab, entityId, NoticeWhat.Message)
            if (!journal.record(record)) return@withLock false
            if (isWatched(entityId)) {
                journal.clearEntity(tab, entityId, "открыта при приходе", at)
                journal.done(NoticeWhat.Message, record.ref, "тишина: открыта")
                return@withLock false
            }
            showTab(tab, decide(record, wasActive, true), record)
            true
        }
    }

    /**
     * Подпись сошлась — У6, вторая стадия: та же строка получает имя, **молча**.
     *
     * Не вторая строка и не второй сигнал: два уведомления об одном сообщении человек
     * читает как два сообщения.
     */
    suspend fun opened(chatId: String, senderId: String) {
        if (isWatched(chatId) || !shouldNotify(senderId)) return
        val name = nameOf(senderId)
        lock.withLock {
            if (name != null) named[chatId] = name
            if (journal.isActive(NoticeTab.Chats, chatId, NoticeWhat.Message)) showTab(NoticeTab.Chats, alert = false)
        }
    }

    /** Нам звонят — У7. Имя сразу: его утверждает сервер, а не звонящий. */
    suspend fun calling(callId: String, fromUserId: String, video: Boolean = false) {
        if (!shouldNotify(fromUserId)) return
        // В журнал — что строку поставили и УВИДЯТ ли её: «звонок пришёл, а телефон молчал»
        // иначе не отличить от «строку не ставили» и от «система её не показала» (ВЗ0в).
        // Сверка фона здесь же: ответ нужен ровно сейчас, и изменение попадёт в журнал.
        val facts = BackgroundWatch.check("входящий звонок")
        if (facts.notices == false || facts.calls == false) {
            Journal.trouble(
                LogCode.CALL, "уведомление о входящем не покажется — уведомления или канал «Звонки» выключены",
                "звонок" to callId.take(8),
            )
        } else {
            Journal.note(LogCode.CALL, "уведомление о входящем поставлено", "звонок" to callId.take(8))
        }
        // «Не беспокоить» со звонками: строка в шторке, без мелодии и полного экрана. Ключ —
        // тот же, что у звонящей строки: конец звонка снимает её так же.
        if (quiet().callsMuted()) {
            Journal.note(LogCode.NOTICE, "не беспокоить — входящий без мелодии", "звонок" to callId.take(8))
            notifier.show(
                Notice(
                    key = CALL_KEY_PREFIX + callId,
                    kind = NoticeKind.Message,
                    who = nameOf(fromUserId),
                    what = words().notices.incomingCall,
                    alert = false,
                ),
            )
            return
        }
        notifier.show(
            Notice(
                key = CALL_KEY_PREFIX + callId,
                kind = NoticeKind.Call,
                who = nameOf(fromUserId),
                what = words().notices.incomingCall,
                // Строка звонит — во весь экран, с кнопками, мелодией по кругу (ВЗ1–ВЗ3).
                call = CallAlert(callId = callId, video = video, ring = ringFor(fromUserId)),
            ),
        )
    }

    /**
     * Пропущенный звонок — уведомлением (решение заказчика 2026-09-26, ВЗ0а).
     *
     * Строка — вкладки «Звонки», одна на всех звонивших; держится, пока человек не откроет
     * «Звонки» на любом своём устройстве (тогда придёт «seen», см. [missedSeen]).
     *
     * **Один звонок — одно уведомление** (ЖУ0): тот же звонок, поднятый снова разрывом
     * ленты или вторым проходом, журнал узнаёт и отбрасывает.
     *
     * @param atMs когда звонок кончился; старое — догонка, без звука.
     * @param from `CatchUp` — поднят из журнала звонков при разрыве ленты.
     */
    suspend fun missed(callId: String, fromUserId: String, atMs: Long = 0, from: NoticeFrom = NoticeFrom.Live) {
        if (!shouldNotify(fromUserId)) return
        lock.withLock {
            val at = now()
            val fresh = from == NoticeFrom.Live && (atMs <= 0 || at - atMs <= FRESH_MS)
            val record = NoticeRecord(
                tab = NoticeTab.Calls,
                entity = fromUserId,
                what = NoticeWhat.Missed,
                ref = callId,
                atMs = at,
                from = if (fresh) NoticeFrom.Live else NoticeFrom.CatchUp,
            )
            val wasActive = journal.isActive(NoticeTab.Calls, fromUserId, NoticeWhat.Missed)
            if (!journal.record(record)) {
                Journal.note(LogCode.NOTICE, "повтор пропущенного отброшен", "звонок" to callId.take(8))
                return@withLock
            }
            Journal.note(LogCode.CALL, "уведомление о пропущенном", "звонок" to callId.take(8))
            // Имя — только для нового уведомления: повтор строки не меняет, а запрос имени
            // стоит похода на сервер (ПК 2026-09-30 — 218 запросов на повторах, 1а).
            nameOf(fromUserId)?.let { named[fromUserId] = it }
            showTab(NoticeTab.Calls, decide(record, wasActive, fresh), record)
        }
    }

    /** Пропущенный просмотрен — на этом или другом устройстве человека (`seen`). */
    fun missedSeen(callId: String) {
        scope.launch {
            lock.withLock {
                if (journal.clearRef(NoticeWhat.Missed, callId, "seen", now()) > 0) showTab(NoticeTab.Calls, alert = false)
            }
        }
    }

    /**
     * Открыли вкладку «Звонки» — её число обнуляется, строка в шторке уходит (ЖУ6,
     * заказчик 2026-09-30): сущностей для просмотра там нет, всё и так увидели.
     */
    fun callsViewed() {
        scope.launch {
            lock.withLock {
                if (journal.clearTab(NoticeTab.Calls, "вкладка открыта", now()) > 0) showTab(NoticeTab.Calls, alert = false)
            }
        }
    }

    /** Звонок кончился — чем бы ни кончился. Строка звонка не переживает звонок. */
    fun callOver(callId: String) {
        notifier.hide(CALL_KEY_PREFIX + callId)
    }

    /**
     * Человек открыл переписку или ушёл из неё — У10, ЖУ6.
     *
     * Открыл — её уведомления сняты в журнале с причиной «просмотрена», у вкладки минус
     * один, строка вкладки и значок пересчитаны. Пока она открыта, новое в ней не
     * уведомляет: человек видит его и так.
     */
    fun watching(chatId: String?) {
        openChat = chatId
        if (chatId != null) viewed(chatId, "просмотрена")
    }

    /**
     * Переписка просмотрена — здесь или на другом устройстве (копия аккаунта, ЖУ9).
     * Вкладка — та, где сущность есть: личная или группа.
     */
    fun viewed(chatId: String, by: String) {
        scope.launch {
            lock.withLock {
                val at = now()
                for (tab in ENTITY_TABS) {
                    if (journal.clearEntity(tab, chatId, by, at) > 0) showTab(tab, alert = false)
                }
            }
        }
    }

    /** Окно показалось или ушло — У4. Убранное окно ничего не показывает глазами. */
    fun windowVisible(visible: Boolean) {
        windowShown = visible
        _shown.value = visible
    }

    private val _shown = kotlinx.coroutines.flow.MutableStateFlow(true)

    /**
     * Видно ли окно — сессия человека: ушло с экрана — конец сессии, копия аккаунта
     * отдаётся; вышло на экран — забирается, если сервер сказал, что есть новее (ЖУ9).
     */
    val shown: kotlinx.coroutines.flow.StateFlow<Boolean> = _shown

    /**
     * Сверка журнала с базой при запуске (ЖУ1).
     *
     * Журнал пуст после обновления, а непрочитанное в базе есть; прочитанное могли отметить
     * мимо журнала. Сверка заводит строки без звука (`source = seed`) и снимает переписки, в
     * которых непрочитанного не осталось, — числа сходятся с базой.
     *
     * **«Звонки» сверка только заводит, не снимает.** Местный журнал звонков обновляется при
     * открытии вкладки и отстаёт от ленты: живая проверка 2026-09-30 — пропущенный, записанный
     * лентой, сверка через секунду сняла, потому что в местном журнале его ещё не было.
     * Снимают «Звонки» открытие вкладки и `seen`.
     *
     * @param unread непрочитанные переписки: переписка → группа ли.
     * @param missed непросмотренные пропущенные: звонок → звонивший.
     */
    suspend fun reconcile(unread: Map<String, Boolean>, missed: Map<String, String>) {
        lock.withLock {
            val at = now()
            var seeded = 0
            var cleared = 0
            for ((chatId, group) in unread) {
                val tab = if (group) NoticeTab.Groups else NoticeTab.Chats
                if (!journal.isActive(tab, chatId, NoticeWhat.Message)) {
                    val ref = "seed:$chatId@$at"
                    if (journal.record(NoticeRecord(tab, chatId, NoticeWhat.Message, ref, at, NoticeFrom.Seed))) {
                        journal.done(NoticeWhat.Message, ref, "тишина: сверка при запуске")
                        seeded++
                    }
                }
            }
            for ((callId, from) in missed) {
                if (journal.record(NoticeRecord(NoticeTab.Calls, from, NoticeWhat.Missed, callId, at, NoticeFrom.Seed))) {
                    journal.done(NoticeWhat.Missed, callId, "тишина: сверка при запуске")
                    seeded++
                }
            }
            val counts = NoticeCounts(journal.activeNow())
            for (tab in ENTITY_TABS) {
                for (entity in counts.entities(tab) - unread.keys) cleared += journal.clearEntity(tab, entity, "прочитано", at)
            }
            val purged = journal.purge(at - KEEP_MS)
            if (seeded + cleared + purged > 0) {
                Journal.note(LogCode.NOTICE, "журнал уведомлений сверен", "заведено" to seeded, "снято" to cleared, "убрано" to purged)
            }
            for (tab in NoticeTab.entries) showTab(tab, alert = false)
        }
    }

    /** Выход из аккаунта: чужих строк в шторке остаться не должно. */
    fun forget() {
        notifier.hideAll()
        notifier.badge(0)
    }

    // ── Решение и показ ─────────────────────────────────────────────────────

    /**
     * Звучать ли этому событию — ЖУ3. Слово решения пишется в журнал строкой события: по
     * нему видно, почему было тихо.
     */
    private fun decide(record: NoticeRecord, wasActive: Boolean, fresh: Boolean): Boolean {
        val at = record.atMs
        val why = when {
            !fresh -> {
                if (catchUpRun++ == 0) Journal.note(LogCode.NOTICE, "догонка без звука", "вкладка" to record.tab.wire)
                "тишина: догонка"
            }
            wasActive -> "тишина: у сущности уже есть"
            else -> {
                if (catchUpRun > 0) {
                    Journal.note(LogCode.NOTICE, "догонка без звука — итог", "сколько" to catchUpRun)
                    catchUpRun = 0
                }
                val skippedBefore = gate.skipped
                if (gate.ask(at, lengthMs = null)) {
                    if (skippedBefore > 0) Journal.note(LogCode.NOTICE, "звук: в прошлой пачке промолчало", "сколько" to skippedBefore)
                    null
                } else {
                    if (gate.skipped == 1) Journal.note(LogCode.NOTICE, "звук пропущен — перерыв после сигнала")
                    "тишина: перерыв"
                }
            }
        }
        journal.done(record.what, record.ref, why ?: "строка+звук")
        return why == null
    }

    /**
     * Строка вкладки в шторке — с числом вкладки; ноль — строки нет. Значок — сумма вкладок.
     *
     * @param alert звучать ли; сигнал разрешило [decide].
     */
    private suspend fun showTab(tab: NoticeTab, alert: Boolean, cause: NoticeRecord? = null) {
        // Идёт звонок — строка есть, звука нет: сообщение не должно звенеть поверх разговора.
        val calling = alert && inCall()
        if (calling) Journal.note(LogCode.NOTICE, "идёт звонок — уведомление без звука", "вкладка" to tab.wire)
        showTabNow(tab, alert && !calling, cause)
    }

    private suspend fun showTabNow(tab: NoticeTab, alert: Boolean, cause: NoticeRecord?) {
        val counts = NoticeCounts(journal.activeNow())
        val n = counts.tab(tab)
        val key = TAB_KEY_PREFIX + tab.wire
        if (n == 0) {
            notifier.hide(key)
        } else {
            val one = counts.entities(tab).singleOrNull()
            val notices = words().notices
            val (who, what) = when (tab) {
                NoticeTab.Chats -> if (n == 1) {
                    val name = one?.let { named[it] }
                    name to (if (name == null) notices.newMessage else notices.wroteToYou)
                } else {
                    null to notices.messagesFrom(n)
                }
                NoticeTab.Groups -> if (n == 1) {
                    (one?.let { groupTitle(it) }) to notices.newInGroup
                } else {
                    null to notices.messagesInGroups(n)
                }
                NoticeTab.Calls -> if (n == 1) {
                    (one?.let { named[it] }) to words().call.missedCall
                } else {
                    null to notices.missedFrom(n)
                }
                NoticeTab.Channels -> if (n == 1) {
                    (one?.let { channelTitle(it) }) to notices.newInChannel
                } else {
                    null to notices.postsInChannels(n)
                }
            }
            // Тихие часы: строку не показываем и не звучим; накопленное покажет первое
            // событие после них. Значок ниже обновляется как обычно.
            val q = quiet()
            if (if (tab == NoticeTab.Calls) q.callsMuted() else q.messagesMuted()) {
                if (alert && cause != null) journal.done(cause.what, cause.ref, "тихие часы — без строки и звука")
                Journal.note(LogCode.NOTICE, "тихие часы — строка не показана", "вкладка" to tab.wire, "число" to n)
                updateBadge(counts.total)
                return
            }
            val start = now()
            val length = notifier.show(
                Notice(
                    key = key,
                    kind = NoticeKind.Message,
                    sound = messageSound(),
                    who = who,
                    what = what,
                    alert = alert,
                    number = n,
                ),
            )
            // Перерыв — от настоящего конца сигнала: платформа знает длину, когда играет.
            if (alert) gate.played(start, length)
            if (alert && cause != null && length == 0L) journal.done(cause.what, cause.ref, "строка; звука нет — тишина телефона")
        }
        updateBadge(counts.total)
    }

    private fun updateBadge(total: Int) {
        if (total != badgeShown) {
            if (badgeShown >= 0) Journal.note(LogCode.NOTICE, "значок сменился", "было" to badgeShown, "стало" to total)
            badgeShown = total
            notifier.badge(total)
        }
    }

    /**
     * Уведомлять ли о человеке — У8.
     *
     * Заблокированный молчит, и проверка здесь **явная**. Своё сообщение с другого своего
     * устройства — тоже молча: человек сам его и написал.
     */
    private suspend fun shouldNotify(userId: String?): Boolean {
        if (userId.isNullOrBlank() || userId == me) return false
        return entryOf(userId)?.list != BookList.Blocked
    }

    /** Человек смотрит на эту переписку прямо сейчас. */
    private fun isWatched(chatId: String): Boolean = windowShown && chatId == openChat

    /**
     * Как назвать человека — У9.
     *
     * Он в книге — зовём тем же порядком полей, что и списки. Его в книге нет — остаётся
     * `@ник`. Ника нет — **строка без имени вовсе** (решение заказчика 2026-09-24):
     * выдуманное имя хуже отсутствующего.
     */
    /**
     * Имя и ник — для строки «Звонок начат: Анна Петрова, @anna» (заказчик 2026-10-02).
     * Ника нет — одно имя; имени нет — один ник.
     */
    internal suspend fun nameAndNick(userId: String): String? {
        val nick = cardOf(userId)?.nick?.takeIf { it.isNotBlank() }?.let { "@$it" }
        val name = nameOf(userId)?.takeIf { it != nick }
        return listOfNotNull(name, nick).joinToString(", ").ifEmpty { null }
    }

    /** Как назвать человека в строке — тем же правилом, что уведомления. */
    internal suspend fun nameOf(userId: String): String? {
        val card = cardOf(userId)
        val entry = entryOf(userId)
        if (entry != null) {
            val person = ChatPerson(
                name = entry.name,
                userName = card?.userName,
                nick = card?.nick,
                phone = entry.phone.ifBlank { card?.phone },
            )
            person.line(look(), PERSON_FIRST_LINE)?.let { return it }
        }
        return card?.nick?.let { "@$it" }
    }

    private companion object {
        /**
         * Ключи звонков и вкладок не должны столкнуться: звонок однажды снял бы строку
         * вкладки.
         */
        const val CALL_KEY_PREFIX = "call:"

        /** Строка вкладки в шторке — одна на вкладку (ЖУ4). */
        const val TAB_KEY_PREFIX = "tab:"

        /** Вкладки, где сущность — переписка. */
        val ENTITY_TABS = listOf(NoticeTab.Chats, NoticeTab.Groups)

        /**
         * Свежее — не старше двух минут по часам сервера. Старше — догонка, без звука (ЖУ3).
         * С запасом на часы отправителя: у Redmi они отставали на 35 с.
         */
        const val FRESH_MS = 120_000L

        /** Снятые строки журнала хранятся месяц — для разбора «куда исчезло». */
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}
