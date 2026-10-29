package io.tima.feature.chat

import io.tima.domain.chat.CopyWatch
import io.tima.domain.chat.DraftSteps
import io.tima.domain.chat.FoundMessage
import io.tima.domain.chat.GroupKind
import io.tima.domain.chat.MessageFinder
import io.tima.domain.chat.PhraseOpen
import io.tima.domain.chat.references
import io.tima.domain.chat.ChatPins
import io.tima.domain.chat.PinStep
import io.tima.core.words.CurrentWords
import io.tima.domain.account.PhraseWords
import io.tima.core.words.Words
import io.tima.core.words.RussianWords
import io.tima.core.words.ChatWords
import io.tima.core.media.FileRules
import io.tima.core.media.PhotoPrep
import io.tima.domain.chat.AttachResult
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.ComposedResult
import io.tima.domain.chat.SendComposed
import io.tima.domain.chat.SendAttachments
import io.tima.domain.chat.DeleteMessage
import io.tima.domain.chat.DeleteResult
import io.tima.domain.chat.EditMessage
import io.tima.domain.chat.EditResult
import io.tima.domain.chat.MessagePicks
import io.tima.domain.chat.PickResult
import io.tima.domain.chat.NickReply
import io.tima.domain.chat.ReplyTarget
import io.tima.domain.chat.HealGroupKey
import io.tima.domain.chat.HealStep
import io.tima.domain.chat.MessageCircle
import io.tima.domain.chat.NarrowMessageLevel
import io.tima.domain.chat.NarrowStep
import io.tima.domain.chat.MarkRead
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.ChatFaces
import io.tima.domain.chat.ChatPeople
import io.tima.domain.chat.DeadMessages
import io.tima.domain.chat.ChatPerson
import io.tima.core.media.decodeImage
import androidx.compose.ui.graphics.ImageBitmap
import io.tima.domain.chat.RequestGroupKeys
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.RequestKeysStep
import io.tima.domain.chat.SendMessage
import io.tima.domain.chat.SendMessageResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.withContext

/**
 * Состояние окна переписки — К4.4.
 *
 * **Что здесь есть и чего нет.** Есть правила поведения экрана: что делать с набранным
 * текстом при удаче и при отказе, что показывать человеку. Нет ни сети, ни базы, ни
 * криптографии — только два случая использования из `domain-chat`. Это проверяется
 * архитектурным правилом, а не договорённостью.
 *
 * **Главное правило здесь одно: набранное человеком не теряется.** При удаче поле ввода
 * очищается — сообщение уже видно в списке, и оставленный текст выглядел бы как второе.
 * При **любом** отказе текст остаётся в поле: он написан человеком, а не нами, и
 * восстановить его нам нечем. В v1 поле очищалось до подтверждения, и сообщение,
 * отвергнутое по размеру, исчезало вместе с набранным.
 */
class ChatStore(
    private val chatId: String,
    observe: ObserveChat,
    private val send: SendMessage,
    private val scope: CoroutineScope,
    /**
     * Отметить прочитанным. `null` — не отмечать: так делают проверки, которым интересна
     * только отправка.
     *
     * Правило: **открытая переписка прочитана**. Отмечается не только при открытии, но и
     * на каждом обновлении списка реплик — пока переписка на экране, приходящее человек
     * видит, и держать при этом янтарную точку значит врать ему.
     */
    private val markRead: MarkRead? = null,
    /**
     * Переписка уже видна человеку при открытии. `false` — отметку «прочитано» включит [seen]: окно
     * на экране, без замка и звонка поверх, дольше 3 секунд (заказчик 2026-10-20: «пользователь
     * реально не видит, а мы доставляем — прочитано»).
     */
    seenAtStart: Boolean = true,
    /**
     * Запрос недостающих версий группового ключа. `null` — переписка личная: там нечего
     * запрашивать, и кнопки на экране быть не должно.
     */
    private val requestKeys: RequestGroupKeys? = null,
    /**
     * Имена авторов. `null` — переписка личная: там собеседник один, и подписывать
     * каждую его реплику именем значит шуметь.
     */
    private val names: ChatPeople? = null,
    /** Аватары авторов — только в группе, по одному походу на автора. */
    private val faces: ChatFaces? = null,
    /**
     * Чья карточка сменилась (счётчик профиля с сервера). Открытая переписка держит свой
     * снимок имён и лиц, и без этого потока смена аватара доехала бы только при повторном
     * открытии.
     */
    private val peopleChanges: Flow<String>? = null,
    /** Повтор и удаление отказанного. `null` — нечем: крестик остаётся без действий. */
    private val dead: DeadMessages? = null,
    /**
     * Сужение круга у уже отправленного сообщения. `null` — переписка личная: там у
     * сообщений кругов нет, и предлагать сужение нечему.
     */
    private val narrow: NarrowMessageLevel? = null,
    /**
     * Есть ли у этого устройства хоть одна версия ключа группы. `null` — спрашивать не у
     * кого: переписка личная.
     *
     * **Пустая группа и группа без ключа выглядят одинаково**, и это та же ошибка, что
     * была с «устройств нет»: молчание экрана человек читает как «здесь ничего нет», а на
     * деле здесь всё есть и ничего не открывается. Устройство, подключённое по QR-коду, не
     * имеет ни одной версии ключа, и до ближайшей смены группа для него нема.
     */
    private val anyKey: (() -> Boolean)? = null,
    /**
     * Лечение группы без ключа. `null` — переписка личная либо лечить нечем (тесты).
     *
     * Зовётся один раз за жизнь store, при первом «ключа нет»: забрать обёртки, а если
     * ключа не было ни у кого — выпустить первый. Публичной группе сообщает, что ключ ей
     * не положен, и баннер снимается. Подробности — `HealGroupKey`.
     */
    private val heal: HealGroupKey? = null,
    /** Сколько строк держать на экране. Столько же просит и запрос к базе. */
    pageSize: Int = ObserveChat.DEFAULT_PAGE,
    /**
     * Словарь надписей — **ссылкой, а не значением** (ПЛАН-(Я)-ЯЗЫКА, Я2-беды).
     *
     * Store не `@Composable`, и `Tima.words` ему недоступен. Лямбда зовётся в момент
     * беды, поэтому язык всегда текущий: переданный значением, он запомнился бы на всю
     * жизнь store, и после смены языка беда пришла бы на прежнем.
     */
    private val words: () -> Words = { CurrentWords.value },
    /**
     * Где делается работа с базой. По умолчанию — фоновый пул.
     *
     * **Зависимостью, а не литералом внутри.** Сборщик потока живёт в scope экрана, то
     * есть на главном потоке, и ходить оттуда в базу нельзя (БЕДЫ/2026-09-17). Но
     * прибитый гвоздями `Dispatchers.Default` уводит работу из планировщика теста: тест с
     * виртуальным временем её не дожидается и видит пустое состояние. Так и вышло —
     * четыре теста покраснели ровно на этом.
     */
    private val io: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Попросить ключи личной переписки у своих устройств и собеседника (заказчик 2026-10-06:
     * «сообщение недоступно, запросить»). Слова фразы — для незаверенного устройства: оно
     * подписывает просьбу ключом личности; `null` — просим без подписи. Номера — недоступные
     * сообщения на экране, новые первыми: сервер разберёт, что вернуть, а что потеряно. `null`
     * вместо всего — переписка не личная.
     */
    private val askChatKeys: (suspend (List<String>?, List<Long>) -> RequestKeysStep)? = null,
    /**
     * Сообщения, к которым ключа не осталось ни у кого, — по ответу сервера на прежние просьбы.
     * Их не просят ни нажатием, ни сами. `null` — не помним (группа).
     */
    private val lostKeys: (suspend () -> Set<Long>)? = null,
    /**
     * Можно ли сейчас попросить самим, без нажатия, — один раз на переписку за срок, который
     * помнит вызывающий; и в личной, и в группе (заказчик 2026-10-06). `null` — сами не просим.
     */
    private val autoAskAllowed: (suspend () -> Boolean)? = null,
    /**
     * «Открыть через фразу» (план КК, КК4): копия открывается словами этого запуска и забывается. `null` —
     * пункта нет (проверки).
     */
    private val openByPhrase: (suspend (List<String>) -> PhraseOpen)? = null,
    /** ПК: в окне недоступного есть «Передать с телефона» — просьба своему телефону (КК4). */
    private val fromPhoneMay: Boolean = false,
    /** Правка своего (ADR-0045). `null` — «Изменить» нечем: так собирают экран в проверках. */
    private val edit: EditMessage? = null,
    /** Удаление своего: у себя и у всех (Решения §8). `null` — нечем. */
    private val deleter: DeleteMessage? = null,
    /** Подборка сообщений (Решения §11.2). `null` — нечем. */
    private val picks: MessagePicks? = null,
    /** Чья подборка у этой переписки — `MessagePicks.levelOf`. */
    private val pickLevel: String = MessagePicks.PERSONAL,
    /** Черновики (Решения §9): у переписки свой, зашифрованный, переживает перезапуск. `null` — не храним. */
    private val drafts: io.tima.domain.chat.Drafts? = null,
    /** Вложения (Решения §2, §3, §6). `null` — нечем: скрепки и галереи нет. */
    private val attach: SendAttachments? = null,
    /** Собеседник личной переписки — получатель почтальона; `null` — группа. */
    private val peerId: String? = null,
    /** Переписка с собой: файл себе — до 50 МБ. */
    private val selfChat: Boolean = false,
    /** Собранное в редакторе (Решения §10). `null` — редактора нет: кнопка не показывается. */
    private val composer: SendComposed? = null,
    /** Закреплённое (ПЛАН-(ЗО) ЗО3). `null` — закреплять нечем: полосы и пунктов меню нет. */
    private val pins: ChatPins? = null,
    /** Могу ли закреплять: личная — любой из двух, группа — модератор и выше (решает вызывающий по роли). */
    private val pinMay: Boolean = false,
    /** Поставить суперсообщение описания по номеру (ПЛАН-(ЗО) ЗО5); `null` — не владелец. */
    private val setSuper: (suspend (Long) -> Boolean)? = null,
    /**
     * Сообщение по номеру — вставленное, подкладка, ответ из другой переписки или не загруженной
     * части (Решения §11.3–§11.4). `null` — искать негде: показывается «Сообщение из другой переписки».
     */
    private val finder: MessageFinder? = null,
) {

    /**
     * Шаги черновика редактора (заказчик 2026-10-24, 1#): виды и числа, рядом с черновиком под тем же ключом
     * покоя; ушло сообщение — стёрты; корзина — стёрты вместе с «Вернуть»; в отчёт о проблеме — все.
     */
    private var steps: List<String> = emptyList()
    private val stepsKey: String get() = chatId + DraftSteps.SUFFIX

    /** Проскочившая копия виджета (3#) — в журнал: вид; «сообщение …» — из скрепки. */
    var copyLog: ((kind: String) -> Unit)? = null
    private val copies = CopyWatch()

    private fun nowMs(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

    private fun clock(ms: Long): String {
        val t = kotlinx.datetime.Instant.fromEpochMilliseconds(ms)
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).time
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${two(t.hour)}:${two(t.minute)}:${two(t.second)}"
    }

    /** Шаг черновика — в список и на диск. */
    private fun stepped(what: String) {
        steps = DraftSteps.append(steps, DraftSteps.line(clock(nowMs()), what))
        val port = drafts ?: return
        val text = steps.joinToString("\n")
        scope.launch { withContext(io) { port.save(stepsKey, text) } }
    }

    private fun clearSteps() {
        steps = emptyList()
        val port = drafts ?: return
        scope.launch { withContext(io) { port.save(stepsKey, "") } }
    }

    /** Строка в шаги открытого черновика — повтор нажатия и подобное (2#). */
    fun draftNote(what: String) {
        if (_state.value.editorOpen && _state.value.editDoc == null) stepped(what)
    }

    /** Отложенная запись черновика: не на каждое нажатие клавиши. */
    private var draftSave: kotlinx.coroutines.Job? = null

    private fun keepDraft(text: String) {
        val port = drafts ?: return
        draftSave?.cancel()
        draftSave = scope.launch {
            kotlinx.coroutines.delay(DRAFT_DELAY_MS)
            withContext(io) { port.save(chatId, text) }
        }
    }

    /** Сами уже просили в этой переписке — один запрос, не на каждое обновление списка. */
    private var autoAsked = false

    // Признак берётся из наличия случая, а не задаётся отдельно: два источника одной
    // правды разошлись бы, и кнопка появилась бы там, где нажимать её нечем.
    private val _state = MutableStateFlow(
        ChatState(keyAskMay = requestKeys != null, group = names != null, chatKeysMay = askChatKeys != null),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /**
     * Последний список из базы **целиком**, вместе с ответами веток.
     *
     * Экрану отдаётся свёрнутый (ответы убраны в ветки), а здесь лежит полный: открытие
     * ветки не должно ходить в базу второй раз за тем, что уже пришло потоком.
     */
    private var known: List<ChatLine> = emptyList()

    init {
        // Закреплённое — с сервера при открытии и событием потом; полоса читает общий снимок.
        pins?.let { board ->
            _state.value = _state.value.copy(pinMay = pinMay)
            scope.launch { runCatching { withContext(io) { board.refresh(chatId, _state.value.group) } } }
            scope.launch { board.pinned.collect { _state.value = _state.value.copy(pinned = it[chatId] ?: 0L) } }
        }
        // Черновик этой переписки — в строку ввода, если человек ещё ничего не набрал.
        drafts?.let { port ->
            scope.launch {
                val saved = withContext(io) { port.load(chatId) }
                if (!saved.isNullOrEmpty() && _state.value.draft.isEmpty()) _state.value = _state.value.copy(draft = saved)
                // Собранное в редакторе — тоже черновик (Решения §10.1: «назад» сохраняет).
                val doc = composer?.let { c -> withContext(io) { port.load(editorKey) }?.let(c::load) }
                steps = withContext(io) { port.load(stepsKey) }?.takeIf { it.isNotBlank() }?.split('\n').orEmpty()
                if (doc != null && !doc.isEmpty && _state.value.composed == null) _state.value = _state.value.copy(composed = doc)
                // Основное, ждавшее номера продолжения, — пережило перезапуск (Решения §11.3): уйдёт
                // само, как только продолжение получит номер, а не останется черновиком без подкладки.
                composer?.let { c ->
                    val pending = withContext(io) { port.load(pendingKey) }?.takeIf { it.isNotEmpty() }
                    val key = pending?.substringBefore('\n')
                    val main = pending?.substringAfter('\n', "")?.takeIf { it.isNotEmpty() }?.let(c::load)
                    if (key != null && main != null && pendingMain == null) {
                        pendingMain = PendingMain(main, key, null)
                        sendPendingMain(known)
                    }
                }
            }
        }
        // Потерянное помечается сразу, а не после первой просьбы: «запросить» у него быть не должно.
        lostKeys?.let { load -> scope.launch { _state.value = _state.value.copy(lostKeys = load()) } }
        peopleChanges?.onEach { who ->
            if (who !in _state.value.names) return@onEach
            names?.let { directory ->
                _state.value = _state.value.copy(names = _state.value.names + (who to directory.person(who)))
            }
            faces?.let { album ->
                _state.value = _state.value.copy(faces = _state.value.faces + (who to album.face(who)?.let(::decodeImage)))
            }
        }?.launchIn(scope)
        // Список приходит потоком: обновление от самой базы, а не по нажатию. Значит
        // пришедшее сообщение и смена состояния отправки появляются на экране сами.
        observe.page(chatId, pageSize)
            .onEach { lines ->
                // Признак пересчитывается на каждом обновлении: ключ может приехать в
                // любой момент — ротацией, догоном или ответом на просьбу, — и полоса
                // обязана исчезнуть сама, без повторного открытия переписки.
                // Ветка сворачивается здесь, а не на экране: список реплик — одно
                // место, и решать в двух значило бы однажды показать ответы дважды.
                // Под корнем остаётся строка «ветка · N ответов» (ADR-0024 §7, К6).
                // Оба похода в базу — ВНЕ главного потока. Сборщик этого потока живёт в
                // scope экрана, то есть на главном; до 2026-09-17 и проверка ключа, и
                // отметка прочтения шли прямо здесь. Стек зависания с realme показал
                // главный поток стоящим в `SQLiteConnectionPool.waitForConnection` внутри
                // транзакции отметки — то есть рисование ждало базу.
                val noKey = keyMissing()
                known = lines
                sendPendingMain(lines)
                superSent(lines)
                lookUpReferences(lines)
                val replies = lines.filter { it.threadRoot != 0L }.groupingBy { it.threadRoot }.eachCount()
                // «Только продолжение» в ленте не показывается (Решения §11.3): его видно через
                // подкладку и прямой переход; в `known` оно остаётся — подкладке есть что показать.
                // Описание группы (уровень 0) в ленту не идёт — оно в окне «Описание» (ПЛАН-(ЗО) ЗО4);
                // подкладке и переходу оно остаётся видно, как продолжение.
                val showcase: (ChatLine) -> Boolean = { _state.value.group && it.level == 0 }
                val shown = lines.filter { it.threadRoot == 0L && !it.onlyContinuation && !showcase(it) }
                    .map { line -> line.copy(replies = replies[line.serverId] ?: 0) }
                _state.value = _state.value.copy(
                    lines = shown,
                    continuations = lines.filter { (it.onlyContinuation || showcase(it)) && it.threadRoot == 0L },
                    thread = _state.value.thread?.let { open ->
                        // Тот же список, из которого собран экран переписки.
                        // Открытая ветка обновляется тем же потоком: пришедший ответ
                        // появляется в ней сам, без перечитывания.
                        open.copy(replies = lines.filter { it.threadRoot == open.rootId })
                    },
                    noGroupKey = noKey,
                )
                // Недоступные сообщения — один запрос ключей сами, без фразы (заказчик
                // 2026-10-06: «автоматический режим в виде одного запроса»). Молча: откажут по
                // подписи — откроется поле фразы, остальное человеку не показывается.
                if ((askChatKeys != null || requestKeys != null) && !autoAsked &&
                    lines.any { it.display == MessageDisplay.UNREADABLE }
                ) {
                    autoAsked = true
                    scope.launch {
                        lostKeys?.let { _state.value = _state.value.copy(lostKeys = it()) }
                        // В личной — только если есть что вернуть: потерянное просить незачем.
                        val worth = requestKeys != null || askable().isNotEmpty()
                        if (worth && autoAskAllowed?.invoke() == true && !_state.value.expectKey) {
                            _state.value = _state.value.copy(expectKey = true)
                            askKeysNow(null, quiet = true)
                        }
                    }
                }
                // Имена спрашиваются по одному разу на автора и только в группе: список
                // обновляется на каждое сообщение, и поход за именем на каждой строке
                // означал бы запрос к серверу на каждую реплику.
                names?.let { directory ->
                    val new = lines.mapNotNull { it.senderId }
                        .distinct()
                        .filter { it !in _state.value.names }
                    if (new.isNotEmpty()) {
                        val padded = _state.value.names.toMutableMap()
                        for (who in new) padded[who] = directory.person(who)
                        _state.value = _state.value.copy(names = padded)
                        // Аватары — следом за именами, по тем же новым авторам. «Не
                        // доехал» тоже запоминается, иначе поход повторялся бы на каждой
                        // строке; за новой картинкой сходим при следующем открытии.
                        faces?.let { album ->
                            val pictures = _state.value.faces.toMutableMap()
                            for (who in new) pictures[who] = album.face(who)?.let(::decodeImage)
                            _state.value = _state.value.copy(faces = pictures)
                        }
                    }
                }
                // Переписка на экране — значит прочитана. Порядок именно такой: сначала
                // показать, потом отметить; иначе отметка обгонит то, что человек увидит.
                //
                // Круг «запись будит запрос, запрос зовёт запись» разорван не здесь, а в
                // `SqlInboxStore.markChatRead`: он не трогает базу, когда отмечать нечего.
                // Сторожить это в экране было бы вторым местом для одной правды — и
                // ушедшим бы первым, потому что отметку зовут не только отсюда.
                if (seenNow) withContext(io) { markRead?.chat(chatId) }
            }
            .launchIn(scope)
    }

    /** Видит ли человек переписку сейчас — только тогда приходящее в ней прочитано. */
    private var seenNow = seenAtStart

    /**
     * Человек стал видеть переписку или перестал. Стал — прочитано всё, что в ней есть; перестал —
     * новое остаётся непрочитанным, пока он её снова не увидит.
     */
    fun seen(on: Boolean) {
        if (seenNow == on) return
        seenNow = on
        if (on) scope.launch { withContext(io) { markRead?.chat(chatId) } }
    }

    /** Лечение запускалось: второй раз не надо — ответ придёт потоком из базы. */
    private var healed = false

    /**
     * Вид группы — открытая или закрытая (заказчик 2026-10-21: «Если у группы есть поле — она открытая. То
     * достаточно запросить это поле»). `null` — личная переписка либо группа ещё не сверялась: тогда о
     * ключе ничего не решается и ничего не показывается — ни полосы, ни лечения.
     */
    private var groupKind: GroupKind? = null

    /**
     * Ключа нет — по порядку: сначала вид, потом ключ. Ключ нужен только закрытой группе; у неё его нет —
     * полоса и лечение (забрать обёртки, а если ключа не было ни у кого — выпустить первый).
     */
    private suspend fun keyMissing(): Boolean {
        if (groupKind != GroupKind.Personal) return false
        val noKey = withContext(io) { anyKey?.invoke() == false }
        if (noKey && !healed) {
            healed = true
            heal?.let { launchHeal(it) }
        }
        return noKey
    }

    /**
     * Вид группы стал известен — из строки переписки, которую пишет сверка групп. Открытая — ключ ей не
     * положен, уровень по умолчанию открытый; закрытая — проверить ключ сейчас, не дожидаясь сообщения.
     */
    fun groupKind(kind: GroupKind?) {
        if (kind == groupKind) return
        groupKind = kind
        when (kind) {
            GroupKind.Public -> _state.value = _state.value.copy(
                openGroup = true,
                noGroupKey = false,
                level = if (_state.value.level == MessageCircle.Secret.level) MessageCircle.Outside.level else _state.value.level,
            )
            GroupKind.Personal -> scope.launch { _state.value = _state.value.copy(noGroupKey = keyMissing()) }
            null -> Unit
        }
    }

    private fun launchHeal(case: HealGroupKey) {
        scope.launch {
            when (withContext(io) { case.heal(chatId) }) {
                // Ключ выпущен или приехал — снять баннер СЕЙЧАС. Состояние «ключа нет»
                // пересчитывается по потоку сообщений, а ключ в базе этот поток не будит:
                // на стенде 2026-09-18 ротация прошла, а баннер висел до следующего
                // сообщения, которого в пустой группе не бывает.
                HealStep.Issued, is HealStep.Fetched -> _state.value = _state.value.copy(noGroupKey = false)

                // Публичная группа: баннер снять, а отправлять по умолчанию «Всем» —
                // шифровать тут нечем и незачем.
                HealStep.NotEncrypted -> _state.value = _state.value.copy(
                    openGroup = true,
                    noGroupKey = false,
                    level = if (_state.value.level == MessageCircle.Secret.level) MessageCircle.Outside.level else _state.value.level,
                )

                HealStep.NeedAsk, HealStep.Unknown -> Unit
            }
        }
    }

    /** Человек набирает текст. */
    fun draftChanged(text: String) {
        _state.value = _state.value.copy(draft = text, notice = null)
        // Правка — не черновик: её текст взят из сообщения и уходит правкой.
        if (_state.value.editing == 0L) keepDraft(text)
    }

    /**
     * Человек нажал «отправить».
     *
     * Возвращает исход, потому что вызывающему бывает нужно знать, состоялось ли
     * действие — например чтобы убрать клавиатуру. Состояние при этом уже обновлено.
     */
    /**
     * Человек открыл ветку под сообщением (ADR-0024, К6).
     *
     * Корень называется **серверным** номером: ветку зовут одним числом на всех
     * устройствах, а местный ключ знает только наше.
     */
    fun threadOpened(rootId: Long) {
        val root = known.firstOrNull { it.serverId == rootId } ?: return
        _state.value = _state.value.copy(
            thread = ThreadState(
                rootId = rootId,
                root = root,
                // Ответы берутся из того же потока, что и переписка: второй источник
                // разошёлся бы с первым ровно в момент прихода нового ответа.
                replies = known.filter { it.threadRoot == rootId },
            ),
        )
    }

    /** Человек закрыл ветку. */
    fun threadClosed() {
        _state.value = _state.value.copy(thread = null, threadDraft = "")
    }

    /** Правка поля ввода в ветке. Отдельно от общего черновика: это два разных текста. */
    fun threadDraftChanged(text: String) {
        _state.value = _state.value.copy(threadDraft = text)
    }

    /**
     * Ответ в ветке.
     *
     * Уходит **тем же путём и тем же ключом**, что обычное сообщение: ветка — не второй
     * контур, а сообщение с названным корнем. Круг тоже общий: у ответа своего нет, он
     * берётся у корня — сервер отдаёт ветку тем же, кому отдал корень.
     */
    fun threadSendPressed(): SendMessageResult {
        val open = _state.value.thread ?: return SendMessageResult.Empty
        val outcome = send.send(chatId, _state.value.threadDraft, open.root.level, threadRoot = open.rootId)
        if (outcome is SendMessageResult.Queued || outcome is SendMessageResult.AlreadyQueued) {
            _state.value = _state.value.copy(threadDraft = "")
        }
        return outcome
    }

    /** Человек включил или выключил показ меток круга. */
    fun circlesShown(show: Boolean) {
        _state.value = _state.value.copy(showCircles = show)
    }

    /** Человек выбрал круг для следующего сообщения (ADR-0019). */
    /** Повторить отказанное — с кругом, выбранным сейчас: причиной чаще всего был круг. */
    fun retryDead(dedupKey: String) {
        val port = dead ?: return
        scope.launch { withContext(io) { port.retry(dedupKey, _state.value.level) } }
    }

    /** Убрать неотправленное — отказанное или ждущее. */
    fun deleteUnsent(dedupKey: String) {
        val port = dead ?: return
        scope.launch { withContext(io) { port.delete(dedupKey) } }
    }

    fun circleChosen(level: Int) {
        _state.value = _state.value.copy(level = level)
    }

    /**
     * Человек выбрал, до какого круга сузить уже отправленное сообщение.
     *
     * **Сначала предупреждение, потом действие, и разделено это намеренно.** Сужение
     * необратимо и неполно: у тех, кто уже унёс сообщение к себе, запись останется —
     * сервер чужие переносы не отзывает и отозвать не может. Человек обязан узнать об
     * этом до нажатия, а не после.
     *
     * Расширение сюда не доходит: экран его не предлагает, а доменный случай отвергает.
     */
    fun narrowAsked(messageId: Long, was: Int, to: Int) {
        if (narrow == null) return
        _state.value = _state.value.copy(
            pendingNarrow = PendingNarrow(messageId, was, to),
            notice = ChatNotice.NarrowWarning(MessageCircle.of(to).title),
        )
    }

    /** Человек прочитал предупреждение и подтвердил сужение. */
    fun narrowConfirmed() {
        val case = narrow ?: return
        val asked = _state.value.pendingNarrow ?: return
        _state.value = _state.value.copy(pendingNarrow = null, notice = null)
        scope.launch {
            val outcome = case.narrow(chatId, asked.messageId, asked.was, asked.to)
            _state.value = _state.value.copy(
                notice = when (outcome) {
                    // Метку у реплики поменяет не экран, а событие сервера: оно приходит
                    // всем устройствам, включая наше, и второй источник правды здесь
                    // означал бы расхождение между тем, что видим мы, и что видят другие.
                    is NarrowStep.Narrowed -> ChatNotice.Narrowed(MessageCircle.of(outcome.level).title)
                    NarrowStep.Wider -> ChatNotice.NarrowRefused(words().chat.onlyNarrow)
                    NarrowStep.AlreadySecret ->
                        ChatNotice.NarrowRefused(words().chat.secretIsNarrow)
                    NarrowStep.CannotEncryptLater ->
                        ChatNotice.NarrowRefused(words().chat.openAlreadyOut)
                    NarrowStep.NotAllowed -> ChatNotice.NarrowRefused(words().chat.strangerNarrowsAdmin)
                    NarrowStep.NotFound -> ChatNotice.NarrowRefused(words().chat.messageGone)
                    is NarrowStep.Offline -> ChatNotice.NarrowRefused(words().chat.offlineRetryLater)
                    is NarrowStep.Refused -> ChatNotice.NarrowRefused(words().trouble.refused(outcome.reason))
                },
            )
        }
    }

    /** Человек передумал сужать. */
    fun narrowDropped() {
        _state.value = _state.value.copy(pendingNarrow = null, notice = null)
    }

    // ── Меню сообщения (Решения §11.1) ─────────────────────────────────────────

    /** «Ответить» на чужое: полоса ответа над строкой ввода; отправка уйдёт с блоком ответа. */
    fun replyStarted(line: ChatLine) {
        if (line.serverId <= 0) return
        _state.value = _state.value.copy(
            // Цитата непроверенного несёт его пометку (КК13): иначе ответ выглядел бы подтверждением автора.
            replyTo = ReplyDraft(
                line.serverId, line.senderId.orEmpty(),
                line.text?.take(REPLY_PREVIEW)?.let { if (line.unverified) words().chat.unverifiedMark + " — " + it else it },
            ),
            editing = 0,
        )
    }

    fun replyCancelled() {
        _state.value = _state.value.copy(replyTo = null)
    }

    /**
     * «Изменить»: в строку ввода — набранный текст со знаками (с прежними правками), над ней —
     * полоса «Изменение». Отправка уйдёт правкой. Нечем выразить знаками (таблица, ряд) —
     * сказать, что такое правит редактор.
     */
    fun editStarted(line: ChatLine) {
        val port = edit ?: return
        if (line.edits >= EditMessage.MAX_EDITS) {
            _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.editLimit))
            return
        }
        scope.launch {
            val source = withContext(io) { port.source(chatId, line.serverId) }
            if (source != null) {
                _state.value = _state.value.copy(draft = source, editing = line.serverId, replyTo = null, notice = null)
                return@launch
            }
            // Знаками не выразить (ряд, виджет) — правка в редакторе (заказчик 2026-10-18: «Редактор
            // не открывает»). Документом тоже не выразить — сказать, что править нельзя.
            val doc = withContext(io) { port.document(chatId, line.serverId) }
            _state.value = if (doc == null) {
                _state.value.copy(notice = ChatNotice.Said(words().chat.editImpossible))
            } else {
                _state.value.copy(editorOpen = true, editDoc = doc, editing = line.serverId, replyTo = null, notice = null, droppedComposed = null)
            }
        }
    }

    /**
     * «Отправить» в редакторе правки: уходит правкой исходного — только тексты, строение то же.
     * Ушло или менять нечего — окно закрывается; строение изменилось, пусто, велико — остаётся открытым,
     * причину скажет редактор; остальные отказы — словами в переписке.
     */
    fun editorEditSend(doc: ComposedDoc): EditResult? {
        val port = edit ?: return null
        val id = _state.value.editing.takeIf { it > 0 && _state.value.editDoc != null } ?: return null
        val result = port.editDoc(chatId, id, doc)
        val w = words().chat
        val closed = _state.value.copy(editorOpen = false, editDoc = null, editing = 0)
        _state.value = when (result) {
            is EditResult.Queued, is EditResult.AlreadyQueued, EditResult.Same -> closed.copy(notice = null)
            EditResult.Limit -> closed.copy(notice = ChatNotice.Said(w.editLimit))
            EditResult.NotSent, EditResult.Missing, EditResult.NotMine -> closed.copy(notice = ChatNotice.Said(w.editImpossible))
            EditResult.Empty, EditResult.Structure, is EditResult.TooLarge -> _state.value
        }
        return result
    }

    /** «‹» в редакторе правки: правка отменена, черновиком она не становится. */
    fun editorEditCancelled() {
        _state.value = _state.value.copy(editorOpen = false, editDoc = null, editing = 0)
    }

    /** Правку отменили: строка ввода пустеет, полоса уходит. */
    fun editCancelled() {
        _state.value = _state.value.copy(draft = "", editing = 0)
    }

    private fun editPressed(serverId: Long): SendMessageResult {
        val port = edit ?: return SendMessageResult.Empty
        val outcome = port.edit(chatId, serverId, _state.value.draft)
        val w = words().chat
        _state.value = when (outcome) {
            is EditResult.Queued, is EditResult.AlreadyQueued, EditResult.Same ->
                _state.value.copy(draft = "", editing = 0, notice = null)
            EditResult.Empty -> _state.value.copy(notice = null)
            EditResult.Structure -> _state.value.copy(notice = ChatNotice.Said(w.editStructure))
            EditResult.Limit -> _state.value.copy(notice = ChatNotice.Said(w.editLimit), editing = 0)
            is EditResult.TooLarge -> _state.value.copy(notice = ChatNotice.TooLarge(outcome.bytes, outcome.limit))
            EditResult.NotSent, EditResult.Missing, EditResult.NotMine ->
                _state.value.copy(notice = ChatNotice.Said(w.editInEditor), editing = 0)
        }
        return when (outcome) {
            is EditResult.Queued -> SendMessageResult.Queued(outcome.dedupKey)
            is EditResult.AlreadyQueued -> SendMessageResult.AlreadyQueued(outcome.dedupKey)
            else -> SendMessageResult.Empty
        }
    }

    /** «Удалить у себя» — только на устройстве, без вопросов: у остальных всё остаётся. */
    fun deleteForMe(line: ChatLine) {
        val port = deleter ?: return
        if (_state.value.editing == line.serverId) editCancelled()
        scope.launch { withContext(io) { port.forMe(chatId, line.dedupKey, line.serverId) } }
    }

    /** «Удалить у всех» — сначала вопрос: вернуть нельзя. */
    fun deleteForAllAsked(line: ChatLine) {
        if (deleter == null || line.serverId <= 0) return
        _state.value = _state.value.copy(notice = ChatNotice.DeleteAsk(line.serverId))
    }

    fun deleteForAllConfirmed() {
        val ask = _state.value.notice as? ChatNotice.DeleteAsk ?: return
        val port = deleter ?: return
        _state.value = _state.value.copy(notice = null)
        val ids = listOf(ask.serverId) + ask.more
        if (_state.value.editing in ids) editCancelled()
        scope.launch {
            val w = words().chat
            var said: String? = null
            for (id in ids) {
                val result = withContext(io) { port.forAll(chatId, id, _state.value.group) }
                said = said ?: when (result) {
                    DeleteResult.Deleted -> null
                    DeleteResult.NotSent -> w.deleteRefused("not_sent")
                    DeleteResult.Offline -> w.deleteOffline
                    is DeleteResult.Refused -> w.deleteRefused(result.code)
                }
            }
            if (said != null) _state.value = _state.value.copy(notice = ChatNotice.Said(said))
        }
    }

    // ── Выбор нескольких сообщений (заказчик 2026-10-19) ───────────────────────

    /** «Удалить у себя» для выбранных. */
    fun deleteForMeMany(lines: List<ChatLine>) = lines.forEach(::deleteForMe)

    /** «Удалить у всех» для выбранных — сначала вопрос: вернуть нельзя. Только своё отправленное. */
    fun deleteForAllManyAsked(lines: List<ChatLine>) {
        if (deleter == null) return
        val ids = lines.filter { it.outgoing && it.serverId > 0 }.map { it.serverId }
        if (ids.isEmpty()) return
        _state.value = _state.value.copy(notice = ChatNotice.DeleteAsk(ids.first(), ids.drop(1)))
    }

    /** «В Подборку» для выбранных: сказать один раз — сколько добавлено. */
    fun pickedMany(lines: List<ChatLine>) {
        val port = picks ?: return
        scope.launch {
            var added = 0
            var full = false
            for (line in lines.filter { it.serverId > 0 }) {
                when (withContext(io) { port.add(pickLevel, chatId, line.serverId) }) {
                    PickResult.Added -> added++
                    PickResult.Full -> full = true
                    else -> Unit
                }
            }
            val w = words().chat
            _state.value = _state.value.copy(notice = ChatNotice.Said(if (full) w.picksFull else w.pickedMany(added)))
        }
    }

    /**
     * Что переслать из сообщения: знаками, документом редактора, иначе видимым текстом. Фото
     * забирает окно — у него доступ к файлам. `null` — пересылать нечего.
     */
    fun forwardOf(line: ChatLine): Forwarded? {
        // Непроверенное не пересылается (КК13): у получателя пометка потерялась бы, а автор остался бы.
        if (line.unverified) return null
        if (line.deleted) return null
        val port = edit
        if (port != null && line.serverId > 0) {
            port.copySource(chatId, line.serverId)?.let { return Forwarded.Text(it) }
            port.copyDocument(chatId, line.serverId)?.let { return Forwarded.Doc(it) }
        }
        return line.text?.takeIf { it.isNotBlank() }?.let { Forwarded.Text(it, marks = false) }
    }

    /** Пересланное сюда — каждое отдельным сообщением, в том же порядке. Черновик не трогается. */
    fun sendForwarded(items: List<Forwarded>) {
        for (item in items) {
            when (item) {
                is Forwarded.Text -> send.send(chatId, item.text, _state.value.level, marks = item.marks)
                is Forwarded.Doc -> composer?.send(chatId, item.doc, _state.value.level, null)
                is Forwarded.Photo -> sendPhotos(listOf(item.bytes to item.mime))
                is Forwarded.File -> sendFile(item.bytes, item.mime, item.name)
            }
        }
        _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.forwarded(items.size)))
    }

    /** «Добавить в Подборку» — ссылка на сообщение на этом устройстве. */
    fun picked(line: ChatLine) {
        val port = picks ?: return
        scope.launch {
            val result = withContext(io) { port.add(pickLevel, chatId, line.serverId) }
            val w = words().chat
            val said = when (result) {
                PickResult.Added -> w.picked
                PickResult.Already -> w.pickedAlready
                PickResult.Full -> w.picksFull
                PickResult.NotSent -> w.deleteRefused("not_sent")
            }
            _state.value = _state.value.copy(notice = ChatNotice.Said(said))
        }
    }

    /**
     * Эмоджи при пустой строке — отдельным сообщением (Решения §9): знак, а не текст, поэтому без
     * быстрого оформления.
     */
    fun sendEmoji(emoji: String) {
        val reply = _state.value.replyTo?.let {
            ReplyTarget(kind = if (_state.value.group) "group" else "personal", chatId = chatId, messageId = it.serverId, userId = it.userId)
        }
        send.send(chatId, emoji, _state.value.level, marks = false, reply = reply)
        if (reply != null) _state.value = _state.value.copy(replyTo = null)
    }

    // ── Вложения (Решения §2, §3, §6) ───────────────────────────────────────────

    /** Есть ли чем отправлять вложения — экран показывает Галерею и Скрепку. */
    val canAttach: Boolean get() = attach != null

    /** В группе файлы — только ссылкой на облако (Решения §2). */
    val filesByLink: Boolean get() = peerId == null

    /** Отказ вложению — журналу (FILE-SEND): причина словом кода, без имени файла. */
    var attachLog: ((reason: String) -> Unit)? = null

    private fun attachResult(result: AttachResult, warn: String? = null) {
        when (result) {
            AttachResult.TooMany -> attachLog?.invoke("too_many")
            is AttachResult.Refused -> attachLog?.invoke(result.reason)
            else -> Unit
        }
        val w = words().chat
        val said = when (result) {
            is AttachResult.Queued -> warn
            AttachResult.Offline -> w.sendFailed
            AttachResult.TooMany -> w.photoTooMany
            is AttachResult.Refused -> w.fileRefused(result.reason)
        }
        _state.value = _state.value.copy(notice = said?.let { ChatNotice.Said(it) })
    }

    /** Фото: 1280 готовится здесь же, на фоне (ПЛАН-(ПФ) ПФ3); превью в теле нет (ADR-0052). */
    fun sendPhotos(images: List<Pair<ByteArray, String>>) {
        val port = attach ?: return
        _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.sending))
        scope.launch {
            // Сторона фото и сколько их за раз — по правилам сервера (ПЛАН-(ИФ) ИФ2).
            val rules = withContext(io) { port.rules() }
            if (images.size > rules.maxPhotos) {
                attachLog?.invoke("too_many")
                _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.photoTooMany))
                return@launch
            }
            val result = withContext(io) {
                val photos = images.mapNotNull { (bytes, _) ->
                    PhotoPrep.prepare(bytes, side = rules.photoSide)?.let { SendAttachments.Photo(it.full, it.width, it.height) }
                }
                port.photos(chatId, _state.value.level, peerId, photos)
            }
            attachResult(result)
        }
    }

    /**
     * Одно фото после редактора (ADR-0052, заказчик 2026-10-22): [make] сводит слои на исходнике и
     * уменьшает до стороны из правил сервера — её он и получает. `null` — свести не вышло.
     */
    fun sendPhotoMade(make: suspend (side: Int) -> PhotoPrep.Prepared?) {
        val port = attach ?: return
        _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.sending))
        scope.launch {
            val rules = withContext(io) { port.rules() }
            val ready = withContext(io) { make(rules.photoSide) }
            if (ready == null) {
                attachResult(AttachResult.Refused("nothing"))
                return@launch
            }
            val result = withContext(io) {
                port.photos(chatId, _state.value.level, peerId, listOf(SendAttachments.Photo(ready.full, ready.width, ready.height)))
            }
            attachResult(result)
        }
    }

    /** Файл почтальоном (личная переписка): запреты и предупреждения — по имени (Решения §6). */
    fun sendFile(bytes: ByteArray, mime: String, name: String) {
        val port = attach ?: return
        val peer = peerId ?: return
        if (FileRules.forbidden(name)) {
            attachResult(AttachResult.Refused("type"))
            return
        }
        _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.sending))
        val warn = if (FileRules.warned(name)) words().chat.fileRefused("archive") else null
        scope.launch {
            val result = withContext(io) { port.file(chatId, _state.value.level, peer, selfChat, bytes, mime, name) }
            attachResult(result, warn)
        }
    }

    /**
     * Голосовое (ПЛАН-(ВМ) ВМ3): в личной — файл на срок для собеседника, в группе — для её
     * участников ([chatId] — номер группы). В канале голосовых нет (Р19): экран кнопки не даёт.
     */
    fun sendVoice(ogg: ByteArray, durationMs: Long, wave: ByteArray) {
        val port = attach ?: return
        _state.value = _state.value.copy(notice = ChatNotice.Said(words().chat.sending))
        scope.launch {
            val result = withContext(io) {
                port.voice(chatId, _state.value.level, peerId, if (peerId == null) chatId else null, ogg, durationMs, wave)
            }
            attachResult(result)
        }
    }

    fun sendContact(name: String, phone: String) {
        val port = attach ?: return
        if (name.isBlank() && phone.isBlank()) return
        attachResult(port.contact(chatId, _state.value.level, name, phone))
    }

    fun sendPlace(lat: Double, lon: Double, label: String = "") {
        val port = attach ?: return
        attachResult(port.place(chatId, _state.value.level, lat, lon, label))
    }

    fun sendLink(url: String, title: String = "", cloudKind: String = "") {
        val port = attach ?: return
        val clean = url.trim().let { if ("://" in it) it else "https://$it" }
        if (clean.length < 10) return
        attachResult(port.link(chatId, _state.value.level, clean, title, cloudKind))
    }

    // ── Редактор (Решения §9, §10) ──────────────────────────────────────────────

    /** Есть ли редактор — кнопка слева от строки ввода. */
    val canCompose: Boolean get() = composer != null

    /** Черновик редактора — под своим ключом рядом с черновиком строки. */
    private val editorKey: String get() = "$chatId|editor"

    /** Основное, ждущее номера продолжения: `ключ продолжения \n документ` (Решения §11.3). */
    private val pendingKey: String get() = "$chatId|pending"

    private fun keepComposed(doc: ComposedDoc?) {
        val port = drafts ?: return
        val c = composer ?: return
        scope.launch { withContext(io) { port.save(editorKey, doc?.takeUnless { it.isEmpty }?.let(c::save).orEmpty()) } }
    }

    /**
     * Открыть редактор. Собранного нет — в него переезжает набранное в строке: человек не теряет
     * текста, нажав на три полосы посреди фразы.
     */
    fun openEditor(): ComposedDoc? {
        if (composer == null || _state.value.editing > 0) return null
        val s = _state.value
        // Набранное со знаками переезжает в редактор уже оформлением — тем же разбором, что при отправке
        // (заказчик 2026-10-24, 2б#): в редакторе знаков md нет.
        val doc = s.composed ?: ComposedDoc.of(s.draft)
        _state.value = s.copy(editorOpen = true, composed = doc, draft = "", droppedComposed = null, notice = null)
        if (s.draft.isNotEmpty()) keepDraft("")
        stepped(DraftSteps.OPENED)
        return doc
    }

    /** «Назад» из редактора: собранное остаётся черновиком, в строке — чип (Решения §9). */
    fun editorBack(doc: ComposedDoc) {
        val kept = doc.takeUnless { it.isEmpty }
        if (_state.value.editDoc == null) stepped(DraftSteps.CLOSED)
        _state.value = _state.value.copy(editorOpen = false, composed = kept)
        keepComposed(kept)
    }

    /**
     * Редактор открыт, документ поменялся — черновик пишется сразу, а не только по «‹» (заказчик
     * 2026-10-18: «Любой уход из приложения — всё теряется»). Окно уже закрыто (отправлено, «‹») —
     * ничего: иначе отправленное вернулось бы черновиком.
     */
    fun editorDraft(doc: ComposedDoc) {
        if (!_state.value.editorOpen || _state.value.editDoc != null) return
        val kept = doc.takeUnless { it.isEmpty }
        val before = _state.value.composed
        // Шаг — виды и числа (1#); тот же виджет того же адреса за 2 с — возможная копия (3#).
        DraftSteps.step(before, kept)?.let(::stepped)
        for (w in DraftSteps.added(before, kept)) {
            if (copies.seen(w, nowMs())) {
                val kind = DraftSteps.kindOf(w)
                copyLog?.invoke(kind)
                stepped(DraftSteps.copy(kind))
            }
        }
        _state.value = _state.value.copy(composed = kept)
        keepComposed(kept)
    }

    /** Отправить собранное — из редактора или стрелкой строки ввода. */
    fun sendComposed(doc: ComposedDoc): ComposedResult? {
        val c = composer ?: return null
        val reply = _state.value.replyTo?.let {
            ReplyTarget(kind = if (_state.value.group) "group" else "personal", chatId = chatId, messageId = it.serverId, userId = it.userId)
        }
        val result = c.send(chatId, doc, _state.value.level, reply)
        if (superWanted && (result is ComposedResult.Queued || result is ComposedResult.ContinuationFirst)) {
            superWanted = false
            if (result is ComposedResult.Queued) superKey = result.dedupKey else superPending = true
        }
        _state.value = when (result) {
            is ComposedResult.Queued -> _state.value.copy(editorOpen = false, composed = null, replyTo = null, notice = showcaseNotice())
                .also {
                    keepComposed(null)
                    clearSteps()
                }
            ComposedResult.Empty -> _state.value
            is ComposedResult.Invalid -> _state.value.copy(
                notice = ChatNotice.Said(words().editor.cannotSend + ": " + result.problems.firstOrNull().orEmpty()),
            )
            is ComposedResult.TableTooWide -> _state.value.copy(notice = ChatNotice.Said(words().editor.tableTooWide(result.maxColumns)))
            // Продолжение ушло первым; основное ждёт его номера и пока лежит черновиком (Решения §11.3).
            is ComposedResult.ContinuationFirst -> {
                pendingMain = PendingMain(result.main, result.dedupKey, reply)
                keepPending(pendingMain)
                keepComposed(result.main)
                _state.value.copy(editorOpen = false, composed = null, replyTo = null, notice = ChatNotice.Said(words().editor.continuationSent))
            }
        }
        return result
    }

    /**
     * Отдельное сообщение из одного виджета — ссылка из скрепки (заказчик 2026-10-19). Черновик
     * редактора и ответ не трогает: это не собранное в редакторе.
     */
    fun sendWidget(widget: ComposedWidget): ComposedResult? {
        val c = composer ?: return null
        if (copies.seen(widget, nowMs())) copyLog?.invoke(DraftSteps.sentCopy(DraftSteps.kindOf(widget)))
        val result = c.send(chatId, ComposedDoc(listOf(ComposedItem.Widget(widget))), _state.value.level, null)
        val said = when (result) {
            is ComposedResult.Invalid -> words().editor.cannotSend + ": " + result.problems.firstOrNull().orEmpty()
            else -> null
        }
        if (said != null) _state.value = _state.value.copy(notice = ChatNotice.Said(said))
        return result
    }

    /** Уже спрошенные ссылки: каждая — один раз за жизнь переписки на экране (кроме «нет сети»). */
    private val asked = mutableSetOf<String>()

    /** Журнал недоступного по ссылке — у вызывающего: модуль окна журнала не знает. */
    var refLog: ((kind: String, number: Long, reason: String, level: Int?) -> Unit)? = null

    /**
     * Ссылки в загруженном, которых нет среди загруженного, — спросить по номеру (Решения §11.3):
     * вставленное и подкладка из другой переписки, ответ на давнее. Ответ кладётся в [ChatState.found].
     */
    private fun lookUpReferences(lines: List<ChatLine>) {
        val find = finder ?: return
        val kind = if (_state.value.group) "group" else "personal"
        val have = lines.mapTo(HashSet()) { "${it.chatId}/${it.serverId}" }
        val wanted = lines.asSequence()
            .mapNotNull { it.rich }
            .flatMap { it.references(chatId, kind) }
            .filter { (_, chat, number) -> number > 0 && "$chat/$number" !in have && asked.add("$chat/$number") }
            .toList()
        for ((refKind, chat, number) in wanted) {
            scope.launch {
                val found = withContext(io) { runCatching { find.find(refKind, chat, number) }.getOrDefault(FoundMessage.Offline) }
                when (found) {
                    // Сети нет — спросим при следующем обновлении ленты.
                    FoundMessage.Offline -> asked.remove("$chat/$number")
                    is FoundMessage.Unavailable -> refLog?.invoke(refKind, number, found.reason, found.level)
                    else -> Unit
                }
                _state.value = _state.value.copy(found = _state.value.found + ("$chat/$number" to found))
            }
        }
    }

    /** Переход к сообщению, которого нет в ленте: спросить и показать одно (Решения §11.4). */
    fun peek(kind: String, chat: String, number: Long) {
        val key = "$chat/$number"
        (known.firstOrNull { it.chatId == chat && it.serverId == number })?.let {
            _state.value = _state.value.copy(peek = FoundMessage.Found(it))
            return
        }
        _state.value.found[key]?.takeIf { it !is FoundMessage.Offline }?.let {
            _state.value = _state.value.copy(peek = it)
            return
        }
        val find = finder ?: return
        scope.launch {
            val found = withContext(io) { runCatching { find.find(kind, chat, number) }.getOrDefault(FoundMessage.Offline) }
            if (found is FoundMessage.Unavailable) refLog?.invoke(kind, number, found.reason, found.level)
            _state.value = _state.value.copy(peek = found, found = _state.value.found + (key to found))
        }
    }

    fun peekClosed() {
        _state.value = _state.value.copy(peek = null)
    }

    /** Право закреплять стало известно позже открытия: роль приезжает со списком групп. */
    fun pinAllowed(may: Boolean) {
        if (pins != null) _state.value = _state.value.copy(pinMay = may)
    }

    /** Закрепить сообщение (ПЛАН-(ЗО) ЗО3): одно на переписку, прежнее заменяется. */
    fun pin(line: ChatLine) = pinChange(line.serverId)

    /** Снять закрепление. */
    fun unpin() = pinChange(0)

    private fun pinChange(serverId: Long) {
        val board = pins ?: return
        if (serverId < 0 || (serverId == 0L && _state.value.pinned == 0L)) return
        val group = _state.value.group
        scope.launch {
            val step = withContext(io) { if (serverId > 0) board.pin(chatId, group, serverId) else board.unpin(chatId, group) }
            val w = words().showcase
            when (step) {
                PinStep.Done -> Unit
                is PinStep.Refused -> _state.value = _state.value.copy(notice = ChatNotice.Said(w.pinRefused(step.code)))
                PinStep.Offline -> _state.value = _state.value.copy(notice = ChatNotice.Said(w.pinRefused("offline")))
            }
            pinLog?.invoke(serverId > 0, step)
        }
    }

    /** Журнал закрепления — у вызывающего: модуль окна журнала не знает (core-diag). */
    var pinLog: ((on: Boolean, step: PinStep) -> Unit)? = null

    /**
     * Суперсообщение описания (ПЛАН-(ЗО) ЗО5): редактор с подкладками всех сообщений уровня 0;
     * отправленное уходит уровнем 0 и, получив номер, ставится суперсообщением.
     */
    fun composeSuper(doc: ComposedDoc) {
        if (composer == null || setSuper == null) return
        superWanted = true
        _state.value = _state.value.copy(editorOpen = true, composed = doc, level = 0, draft = "", notice = null)
    }

    /** Суперсообщение ждёт отправки (`true`) или номера своего сообщения (ключ). */
    private var superWanted = false
    private var superKey: String? = null

    /** Суперсообщение ушло продолжением вперёд — ключ основного будет у `sendMain`. */
    private var superPending = false

    /**
     * Ушло уровнем 0 в группе — в ленте его не будет, оно в окне «Описание» (ПЛАН-(ЗО) ЗО4); человек
     * должен узнать, куда делось, а не искать.
     */
    private fun showcaseNotice(): ChatNotice? =
        if (_state.value.group && _state.value.level == 0) ChatNotice.Said(words().showcase.sentToShowcase) else null

    private fun superSent(lines: List<ChatLine>) {
        val key = superKey ?: return
        val set = setSuper ?: return
        val number = lines.firstOrNull { it.dedupKey == key }?.serverId?.takeIf { it > 0 } ?: return
        superKey = null
        scope.launch {
            val ok = withContext(io) { set(number) }
            val w = words().showcase
            _state.value = _state.value.copy(notice = ChatNotice.Said(if (ok) w.superSet else w.superFailed))
        }
    }

    private fun keepPending(p: PendingMain?) {
        val port = drafts ?: return
        val c = composer ?: return
        scope.launch { withContext(io) { port.save(pendingKey, p?.let { it.continuationKey + "\n" + c.save(it.doc) }.orEmpty()) } }
    }

    /** Основное, которое ждёт номера своего продолжения от сервера. */
    private class PendingMain(val doc: ComposedDoc, val continuationKey: String, val reply: ReplyTarget?)

    private var pendingMain: PendingMain? = null

    /** Продолжение получило номер — уходит основное с подкладкой на него (Решения §11.3). */
    private fun sendPendingMain(lines: List<ChatLine>) {
        val p = pendingMain ?: return
        val c = composer ?: return
        val number = lines.firstOrNull { it.dedupKey == p.continuationKey }?.serverId?.takeIf { it > 0 } ?: return
        pendingMain = null
        val result = c.sendMain(chatId, p.doc, if (_state.value.group) "group" else "personal", number, _state.value.level, p.reply)
        if (result is ComposedResult.Queued) keepPending(null)
        if (result is ComposedResult.Queued && superPending) {
            superPending = false
            superKey = result.dedupKey
        }
        if (result is ComposedResult.Queued) {
            keepComposed(null)
            _state.value = _state.value.copy(notice = null)
        }
    }

    /** Таймер «Вернуть» после корзины. */
    private var dropTimer: kotlinx.coroutines.Job? = null

    /** Корзина в чипе: строка как пустая, 5 секунд «Вернуть» (Решения §9, В36). */
    fun dropComposed() {
        val doc = _state.value.composed ?: return
        _state.value = _state.value.copy(composed = null, droppedComposed = doc)
        dropTimer?.cancel()
        dropTimer = scope.launch {
            kotlinx.coroutines.delay(UNDO_DROP_MS)
            _state.value = _state.value.copy(droppedComposed = null)
            keepComposed(null)
            clearSteps()
        }
    }

    fun restoreComposed() {
        val doc = _state.value.droppedComposed ?: return
        dropTimer?.cancel()
        _state.value = _state.value.copy(composed = doc, droppedComposed = null)
    }

    /** Сказать человеку одно предложение — «Текст скопирован» и подобное. */
    fun said(text: String) {
        _state.value = _state.value.copy(notice = ChatNotice.Said(text))
    }

    fun sendPressed(): SendMessageResult {
        // Идёт правка — отправка уходит правкой исходного, а не новым сообщением (ADR-0045).
        _state.value.editing.takeIf { it > 0 }?.let { return editPressed(it) }
        // Собрано в редакторе — стрелка шлёт собранное (Решения §9).
        _state.value.composed?.let { doc ->
            return when (val r = sendComposed(doc)) {
                is ComposedResult.Queued -> SendMessageResult.Queued(r.dedupKey)
                else -> SendMessageResult.Empty
            }
        }
        val text = _state.value.draft
        // Круг едет в очередь вместе с сообщением: решение «нужен ли ключ» принимается
        // при отправке, а она может случиться после перезапуска.
        val reply = _state.value.replyTo?.let {
            ReplyTarget(kind = if (_state.value.group) "group" else "personal", chatId = chatId, messageId = it.serverId, userId = it.userId)
        }
            // `@Ник` набранным — ответ на последнее сообщение этого человека (Решения §7).
            ?: NickReply.target(text, _state.value.names, known)
                ?.takeIf { _state.value.group }
                ?.let { (who, number) -> ReplyTarget(kind = "group", chatId = chatId, messageId = number, userId = who) }
        // Набранное — как в поле, со знаками md; разбирает их отправка (ADR-0053).
        val outcome = send.send(chatId, text, _state.value.level, reply = reply)

        _state.value = when (outcome) {
            // Принято: поле чистим — сообщение уже в списке.
            is SendMessageResult.Queued,
            is SendMessageResult.AlreadyQueued,
            -> _state.value.copy(draft = "", notice = showcaseNotice(), replyTo = null).also { keepDraft("") }

            // Нажатие мимо. Ни сообщения, ни жалобы: человек и сам видит, что поле пусто.
            SendMessageResult.Empty -> _state.value.copy(notice = null)

            // Текст ОСТАЁТСЯ. Сообщить надо, а отобрать написанное — нельзя.
            is SendMessageResult.TooLarge -> _state.value.copy(
                notice = ChatNotice.TooLarge(outcome.bytes, outcome.limit),
            )
        }
        return outcome
    }

    /** Человек набирает секретную фразу — её просят только после отказа по подписи. */
    fun changedPhrase(text: String) {
        _state.value = _state.value.copy(phrase = text)
    }

    /**
     * Отправить просьбу с набранной фразой — после отказа по подписи. Слова берутся из поля и
     * дальше нигде не сохраняются: держать их значило бы отдать вместе с устройством и тот
     * заслон, ради которого фразу спрашивают.
     */
    fun requestKey() {
        if (requestKeys == null && askChatKeys == null) return
        if (_state.value.expectKey) return
        val words = PhraseWords.parse(_state.value.phrase).takeIf { it.isNotEmpty() }
        _state.value = _state.value.copy(expectKey = true, notice = null)
        scope.launch { askKeysNow(words, quiet = false) }
    }

    /**
     * «Сообщение недоступно, запросить» — нажатие на недоступное сообщение, в личной переписке
     * и в группе (2026-10-06). Сначала без фразы: заверенному устройству она не нужна. Не всякое
     * устройство можно заверить — тогда сервер отказывает по подписи, и открывается поле фразы.
     */
    fun askChatKeys() {
        if (requestKeys == null && askChatKeys == null) return
        if (_state.value.expectKey) return
        _state.value = _state.value.copy(expectKey = true, notice = null)
        scope.launch { askKeysNow(null, quiet = false) }
    }

    /**
     * Нажатие на «Не расшифровано» (план КК, КК4, Р9) — окно «Ждём ключ…». Просьба своим устройствам и
     * собеседнику (участникам) уходит, если её не было за сутки; у «ключа не осталось» просить незачем —
     * окно сразу предлагает фразу.
     */
    fun unreadableTapped() {
        val lostAll = askable().isEmpty()
        _state.value = _state.value.copy(keyWait = KeyWait(lost = lostAll, phraseOpen = lostAll, fromPhone = fromPhoneMay))
        if (lostAll || _state.value.expectKey || (requestKeys == null && askChatKeys == null)) return
        scope.launch {
            if (autoAskAllowed?.invoke() == false) return@launch
            _state.value = _state.value.copy(expectKey = true)
            askKeysNow(null, quiet = false)
        }
    }

    fun keyWaitClosed() {
        _state.value = _state.value.copy(keyWait = null, phrase = "")
    }

    /** «Открыть через фразу» — поле фразы в окне. */
    fun phraseOpenStarted() {
        _state.value.keyWait?.let { _state.value = _state.value.copy(keyWait = it.copy(phraseOpen = true)) }
    }

    /**
     * «Открыть» с набранной фразой: ключи из копии (КК1, КК4) и, если устройство не заверено, та же фраза
     * подписывает просьбу своим и собеседнику. Слова дальше нигде не хранятся.
     */
    fun openByPhrasePressed() {
        val wait = _state.value.keyWait ?: return
        val words = PhraseWords.parse(_state.value.phrase).takeIf { it.isNotEmpty() } ?: return
        _state.value = _state.value.copy(keyWait = wait.copy(opening = true, said = null))
        scope.launch {
            if (_state.value.phraseWanted && !_state.value.expectKey) {
                _state.value = _state.value.copy(expectKey = true)
                askKeysNow(words, quiet = true)
            }
            val outcome = runCatching { openByPhrase?.invoke(words) }.getOrNull()
            val said = when (outcome) {
                is PhraseOpen.Opened -> words().chat.phraseOpened(outcome.count)
                PhraseOpen.WrongPhrase -> words().chat.phraseWrong
                PhraseOpen.NoCopy -> words().chat.copyMissing
                PhraseOpen.Offline, null -> words().chat.offlineRetryIn(5)
            }
            val now = _state.value.keyWait ?: return@launch
            _state.value = _state.value.copy(
                keyWait = now.copy(opening = false, said = said),
                phrase = if (outcome is PhraseOpen.Opened) "" else _state.value.phrase,
            )
        }
    }

    /** ПК, «Передать с телефона»: просьба своим устройствам сейчас, без суточного предела (КК4). */
    fun fromPhonePressed() {
        val wait = _state.value.keyWait ?: return
        _state.value = _state.value.copy(keyWait = wait.copy(said = words().chat.fromPhoneHint))
        if (_state.value.expectKey || (requestKeys == null && askChatKeys == null)) return
        _state.value = _state.value.copy(expectKey = true)
        scope.launch { askKeysNow(null, quiet = false) }
    }

    /** Недоступные сообщения, которые стоит просить: с номером сервера и не потерянные, новые первыми. */
    private fun askable(): List<Long> {
        val lost = _state.value.lostKeys
        return _state.value.lines
            .filter { it.display == MessageDisplay.UNREADABLE && it.serverId > 0 && it.serverId !in lost }
            .map { it.serverId }
            .distinct()
            .sortedDescending()
    }

    /** Признак «запрос в пути» ставит вызывающий — до запуска, чтобы второе нажатие его видело. */
    private suspend fun askKeysNow(words: List<String>?, quiet: Boolean) {
        val outcome = requestKeys?.request(chatId, words) ?: askChatKeys?.invoke(words, askable()) ?: return
        lostKeys?.let { _state.value = _state.value.copy(lostKeys = it()) }
        val needs = outcome == RequestKeysStep.NeedsSecretPhrase
        _state.value = _state.value.copy(
            // Отказ по подписи — в открытом окне сразу поле фразы (КК4): «Открыть» подпишет просьбу ею же.
            keyWait = _state.value.keyWait?.let { if (needs) it.copy(phraseOpen = true) else it },
            expectKey = false,
            // Фраза нужна — поле остаётся открытым, пока просьба не уйдёт.
            phraseWanted = needs || (_state.value.phraseWanted && outcome !is RequestKeysStep.Asked),
            // Набранная фраза живёт до успеха и стирается сразу после него: держать её на
            // экране дольше нужного незачем.
            phrase = if (outcome is RequestKeysStep.Asked) "" else _state.value.phrase,
            notice = if (quiet) _state.value.notice else noticeOf(outcome),
        )
    }

    private fun noticeOf(outcome: RequestKeysStep): ChatNotice = when (outcome) {
        is RequestKeysStep.Asked -> ChatNotice.KeysAsked(outcome.devices)
        RequestKeysStep.NoHelpers -> ChatNotice.KeysNoHelpers
        is RequestKeysStep.Lost -> ChatNotice.KeysLost(outcome.messages)
        RequestKeysStep.NothingMissing -> ChatNotice.KeysNothingMissing
        RequestKeysStep.NeedsSecretPhrase -> ChatNotice.KeysNeedPhrase
        RequestKeysStep.NotMember -> ChatNotice.KeysRefused(words().chat.notMemberAnyMore)
        is RequestKeysStep.Offline -> ChatNotice.KeysRefused(
            words().chat.offlineRetryIn((outcome.retryAfterMs / 1000).coerceAtLeast(1).toInt()),
        )
        is RequestKeysStep.Refused -> ChatNotice.KeysRefused(words().trouble.refused(outcome.reason))
    }

    /** Человек закрыл сообщение о беде. */
    fun noticeDismissed() {
        _state.value = _state.value.copy(notice = null)
    }
}

/** Что видно на экране переписки. */
data class ChatState(
    /** Новое сверху — так же, как отдаёт запрос к базе. */
    val lines: List<ChatLine> = emptyList(),
    /** «Только продолжение» (Решения §11.3): в ленте нет, но подкладке есть что показать. */
    val continuations: List<ChatLine> = emptyList(),
    /** Набранное, но не отправленное. Живёт до подтверждения постановки в очередь. */
    val draft: String = "",
    /** Собранное в редакторе (Решения §9): в строке — чип, стрелка шлёт его. */
    val composed: ComposedDoc? = null,
    /** Редактор открыт поверх переписки. */
    val editorOpen: Boolean = false,
    /** Редактор открыт правкой своего сообщения (ряд, виджет): документ исходного с правками. */
    val editDoc: ComposedDoc? = null,
    /** Сброшено корзиной — ещё 5 секунд можно «Вернуть». */
    val droppedComposed: ComposedDoc? = null,
    /** Серверный номер исправляемого (ADR-0045); 0 — правки нет, отправка — новое сообщение. */
    val editing: Long = 0,
    /** На что отвечаем (Решения §11.1); `null` — не ответ. */
    val replyTo: ReplyDraft? = null,
    val notice: ChatNotice? = null,
    /** Запрос ключа в пути: второе нажатие не шлёт вторую просьбу чужим устройствам. */
    val expectKey: Boolean = false,
    /** Можно ли просить ключ: у личной переписки такой возможности нет. */
    val keyAskMay: Boolean = false,
    /** Личная переписка, где недоступное сообщение можно запросить нажатием (2026-10-06). */
    val chatKeysMay: Boolean = false,
    /** Набранная секретная фраза. Пусто — запрос уйдёт без подписи. */
    val phrase: String = "",
    /**
     * Сервер отказал без подписи — устройство не заверено, и просьбу подписывает фраза: поле
     * фразы открыто (2026-10-06).
     */
    val phraseWanted: Boolean = false,
    /** Номера сообщений, к которым ключа не осталось ни у кого: «запросить» у них нет. */
    val lostKeys: Set<Long> = emptySet(),
    /** Окно «Ждём ключ…» по нажатию на «Не расшифровано» (план КК, КК4); `null` — закрыто. */
    val keyWait: KeyWait? = null,
    /**
     * Групповая ли переписка. От этого зависит показ автора у каждой реплики: в личной
     * он лишний, в групповой без него сообщение теряет половину смысла.
     */
    val group: Boolean = false,
    /**
     * Круг следующего сообщения (ADR-0019): −1 шифр, 0…3 открытые.
     *
     * Умолчание — шифр: в личной переписке других не бывает, а в группе выбор человека
     * заменяет его при первом же касании.
     */
    val level: Int = -1,
    /** Открытая ветка (ADR-0024, К6). `null` — ветка закрыта. */
    val thread: ThreadState? = null,
    /** Спрошенное по номеру: `"переписка/номер"` → сообщение или заглушка (Решения §11.3). */
    val found: Map<String, FoundMessage> = emptyMap(),
    /** Сообщение, открытое одним — переход к тому, чего нет в ленте (Решения §11.4). */
    val peek: FoundMessage? = null,
    /** Номер закреплённого (ПЛАН-(ЗО) ЗО3); 0 — ничего не закреплено или не знаем. */
    val pinned: Long = 0,
    /** Могу ли закреплять и снимать здесь: личная — да, группа — модератор и выше. */
    val pinMay: Boolean = false,
    /** Набранное в ветке. Отдельно от общего черновика: это два разных текста. */
    val threadDraft: String = "",
    /**
     * Показывать ли метки круга у реплик.
     *
     * **По умолчанию выключено.** Метка отвечает на вопрос, которого у обычного участника
     * нет: он и так видит ровно то, что ему открыто. Включает её тот, кто распоряжается
     * содержимым, — админ или автор.
     */
    val showCircles: Boolean = false,
    /** Имена авторов по идентификатору. Пусто для личной переписки. */
    val names: Map<String, ChatPerson> = emptyMap(),
    /** Аватары авторов по `senderId`; `null` — спрашивали, нет. */
    val faces: Map<String, ImageBitmap?> = emptyMap(),
    /**
     * У устройства нет ни одной версии ключа этой группы.
     *
     * Не «нет сообщений» и не «сообщение не читается»: молчит вся группа целиком, и сказать
     * об этом надо прямо — иначе человек решит, что группа пуста.
     */
    val noGroupKey: Boolean = false,
    /**
     * Группа публичная: ключа у неё нет по замыслу, сообщения идут открытыми уровнями.
     * Узнаётся от сервера при первом открытии (`HealGroupKey`) — своего поля «род группы»
     * у переписки пока нет.
     */
    val openGroup: Boolean = false,
    /**
     * Сужение, о котором человека предупредили и которого он ещё не подтвердил.
     *
     * Держится в состоянии, а не в замыкании кнопки: предупреждение — это отдельный шаг,
     * и между показом и подтверждением экран может пережить поворот.
     */
    val pendingNarrow: PendingNarrow? = null,
)

/** Через сколько после последнего нажатия черновик ложится на диск. */
private const val DRAFT_DELAY_MS = 400L

/** Сколько держится «Вернуть» после корзины в чипе (Решения §9, В36). */
private const val UNDO_DROP_MS = 5_000L

/** Задуманный ответ: номер сообщения, его автор и строка из него для полосы. */
data class ReplyDraft(val serverId: Long, val userId: String, val preview: String?)

/**
 * Окно недоступного (план КК, КК4, Р9).
 *
 * @param lost ключа не осталось ни у кого — сразу фраза.
 * @param phraseOpen поле фразы раскрыто.
 * @param fromPhone пункт «Передать с телефона» (только ПК).
 * @param said исход последнего действия словами.
 */
data class KeyWait(
    val lost: Boolean = false,
    val phraseOpen: Boolean = false,
    val fromPhone: Boolean = false,
    val opening: Boolean = false,
    val said: String? = null,
)

/** Сколько знаков сообщения показывать в полосе ответа. */
private const val REPLY_PREVIEW = 120

/** Задуманное сужение: до какого круга и от какого. */
data class PendingNarrow(val messageId: Long, val was: Int, val to: Int)

/**
 * Сообщение человеку. Список короткий намеренно: то, что очередь решает сама
 * (повторы, ожидание сети), человеку сообщать нечем и незачем — это видно по
 * состоянию строки.
 */
sealed interface ChatNotice {
    /** Слишком большое. Числа в сообщении нужны: «слишком большое» без размера бесполезно. */
    data class TooLarge(val bytes: Int, val limit: Int) : ChatNotice

    /** Просьба ушла: ключи приедут, когда ответит кто-то из этих устройств. */
    data class KeysAsked(val devices: Int) : ChatNotice

    /** Просить некого: нужных версий нет ни у кого из участников. Ждать бесполезно. */
    data object KeysNoHelpers : ChatNotice

    /** Личная переписка: [messages] названных сообщений не вернуть — ключа нет ни у кого. */
    data class KeysLost(val messages: Int) : ChatNotice

    /** Недостающих версий нет — значит, сообщение не читается по другой причине. */
    data object KeysNothingMissing : ChatNotice

    /**
     * Нужна секретная фраза: аккаунт защищён ею от угона номера.
     *
     * **Для устройства, подключённого по QR-коду, это тупик**, и текст обязан назвать
     * второй выход. Фразы у такого устройства нет по построению; зато смену ключа в
     * группе может запустить любой участник — значит и сам человек, с другого своего
     * устройства. После смены группа читается вперёд, прежнее остаётся закрытым.
     */
    data object KeysNeedPhrase : ChatNotice

    data class KeysRefused(val text: String) : ChatNotice

    /**
     * Предупреждение перед сужением: у тех, кто унёс, запись останется.
     *
     * Это главное, чего человек не ожидает. Сужение выглядит как «спрятать», а прячет оно
     * только вперёд: перенос уже сделан, и отзывать чужие страницы никто не станет.
     */
    data class NarrowWarning(val circle: String) : ChatNotice

    /** Сузили. Круг назван словами: номера человек не видит нигде. */
    data class Narrowed(val circle: String) : ChatNotice

    data class NarrowRefused(val text: String) : ChatNotice

    /** Одно предложение человеку, уже словами: «Текст скопирован», отказ правки и подобное. */
    data class Said(val text: String) : ChatNotice

    /** Подтвердить «Удалить у всех»: вернуть нельзя (Решения §8). */
    /** «Удалить у всех?» — [serverId] и, при выборе нескольких, [more] (2026-10-19). */
    data class DeleteAsk(val serverId: Long, val more: List<Long> = emptyList()) : ChatNotice
}

/**
 * Открытая ветка: исходное сообщение и ответы на него (ADR-0024 §7, К6).
 *
 * Корень лежит целиком, а не одним номером: подокно показывает его первой строкой — так
 * нарисовано в макете, и без него ветка начинается с ответа на неизвестно что.
 */
data class ThreadState(
    val rootId: Long,
    val root: ChatLine,
    val replies: List<ChatLine> = emptyList(),
)

/** Пересылаемое в другую переписку (заказчик 2026-10-19): каждое уходит там отдельным сообщением. */
sealed interface Forwarded {
    /** Текст; [marks] — со знаками быстрого оформления (как набран), иначе как есть. */
    data class Text(val text: String, val marks: Boolean = true) : Forwarded
    data class Doc(val doc: ComposedDoc) : Forwarded
    class Photo(val bytes: ByteArray, val mime: String) : Forwarded

    /** Файл из меню файла (заказчик 2026-10-20) — уходит файлом, как приложенный. */
    class File(val bytes: ByteArray, val mime: String, val name: String) : Forwarded
}
