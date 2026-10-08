package io.tima.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.tima.core.call.CallAction
import io.tima.core.call.CallDoor
import io.tima.core.call.CallEngine
import io.tima.core.call.RemoteVideoLoss
import io.tima.core.call.CallEvent
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.CallStep
import io.tima.core.call.CallPeer
import io.tima.core.call.Calls
import io.tima.core.call.GroupControl
import io.tima.core.call.GroupRules
import io.tima.core.call.cappedTo
import io.tima.core.call.PublishPreset
import io.tima.core.call.VideoHandle
import io.tima.core.call.basePreset
import io.tima.core.call.askCallAccess
import io.tima.core.call.callOngoing
import io.tima.core.call.callOngoingOff
import io.tima.core.call.callProximity
import io.tima.core.call.callKeepScreen
import io.tima.core.call.CallNoticeActions
import io.tima.core.call.SoundRoute
import io.tima.core.call.openCallSettings
import io.tima.core.diag.Journal
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.core.diag.LogCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Звонок как состояние приложения — окно 0 (макет `21-call.md`, решение заказчика
 * 2026-09-19).
 *
 * **Держит ровно то, чего не знает ни экран, ни движок.** Экран рисует состояние; движок
 * знает про медиа; сигналинг знает про сервер. Между ними остаётся то, что здесь: **идёт
 * ли звонок вообще**, кто собеседник, сколько он длится и чем кончился.
 *
 * **Один звонок за раз** — так в макете («один активный сеанс»). Второй входящий во время
 * разговора здесь не показывается вовсе: показать его значило бы обещать выбор, которого
 * у человека нет, пока не разведён второй сеанс.
 */
class CallHost(
    private val calls: Calls,
    private val engine: CallEngine?,
    private val scope: CoroutineScope,
    /**
     * Словарь — **ссылкой**, а не значением (решение заказчика 2026-09-08, вариант «г»).
     *
     * Прочитанный один раз при создании, он застыл бы на языке запуска: человек сменил
     * язык, а беда звонка продолжает говорить по-русски. Ссылка читается в момент беды.
     */
    private val words: () -> Words = { CurrentWords.value },
    /**
     * Чем публиковать — **ссылкой по той же причине, что словарь**.
     *
     * Пресет меняют на экране стенда посреди жизни приложения, а `CallHost` живёт от
     * запуска до запуска. Прочитанный один раз при создании, он застыл бы на том наборе,
     * который был выбран при старте, — и человек, сменивший кодек, звонил бы прежним, не
     * понимая почему.
     *
     * **`null` — обычный звонок** (ПЛАН-(В)-ВИДЕО.md В3, В5б, 2026-09-29): H.264 или VP8 без
     * запасного, потолок — от сервера ([basePreset]). Набор стенда отдаётся, только пока
     * испытательный режим включён: там он был и остаётся законом, а за стендом обычный
     * звонок его больше не наследует.
     */
    private val preset: () -> PublishPreset? = { null },
    /**
     * Спросить доступ к микрофону (и камере — `true`). Платформенный вопрос по умолчанию;
     * параметром — ради проверок: на ПК ответ даёт Windows, и проверка не должна зависеть
     * от переключателей машины, на которой идёт.
     */
    private val access: (video: Boolean, onResult: (Boolean) -> Unit) -> Unit = ::askCallAccess,
    /**
     * «Настройки → Звонки → Видео при сворачивании: продолжать показывать» (заказчик
     * 2026-10-01, 1в). Выключено — по умолчанию: свернули — своё видео на паузу (1б).
     * Ссылкой: настройку меняют, пока ведущий жив.
     */
    private val cameraInBackground: () -> Boolean = { false },
    /**
     * Служба звонка: поднять и погасить. Платформенные по умолчанию; параметрами — ради
     * проверок, которым нужно видеть, сколько раз и когда службу трогали.
     */
    serviceOn: (ServiceWish) -> Unit = { callOngoing(it.title, it.text, it.hangUpLabel, it.connectedAt, it.camera) },
    serviceOff: () -> Unit = ::callOngoingOff,
    /** Часы ворот службы — в проверках виртуальные. */
    clock: () -> Long = { msNow() },
    /**
     * Гудки звонящего (заказчик 2026-10-08): вызов — пока дозваниваемся, «занято» — когда
     * занято или отклонили. По умолчанию тишина: проверкам звук не нужен; сборка передаёт
     * платформенные `CallTones`.
     */
    private val ringback: (Boolean) -> Unit = {},
    private val busyTone: () -> Unit = {},
) {
    /**
     * Все подъёмы и гашения службы — только здесь: не чаще одного подъёма в секунду, повтор
     * того же не отправляется (заказчик 2026-10-01, [ServiceGate]).
     */
    private val service = ServiceGate(scope, clock, serviceOn, serviceOff)

    /** Идёт ли звонок. По этому признаку окно 0 есть или его нет (`Window.shown`). */
    var active by mutableStateOf(false)
        private set

    var state by mutableStateOf(CallState())
        private set

    /** Кто на том конце — имя для экрана; пусто, пока его не знаем. */
    var peer by mutableStateOf("")
        private set

    /** Нам звонят (а не мы). Сигналинг знает это, SFU — нет. */
    var incoming by mutableStateOf(false)
        private set

    /**
     * Вызов дошёл до телефона собеседника — у звонящего «Звонит» вместо «Вызов…»
     * (ВЗ0а, решение заказчика 2026-09-26: «делаем сразу»).
     *
     * Своё поле, а не поле состояния движка: состояние приходит от SFU и перезаписывается
     * им целиком, а о доставке SFU не знает ничего — это слово нашего сервера.
     */
    var delivered by mutableStateOf(false)
        private set

    /** Сколько идёт разговор. Считает здесь: ни экран, ни движок времени не владеют. */
    var seconds by mutableStateOf(0)
        private set

    /**
     * Что случилось за звонок — лента событий (ЗВ10).
     *
     * Копится здесь, а не на экране: экран пересоздаётся при каждом повороте и уходе в
     * соседнее окно, а события обязаны пережить и то и другое. Свайпнул в «Чаты» и
     * вернулся — лента на месте.
     */
    val events = mutableStateListOf<CallEvent>()

    /** Своя картинка — то, что видит собеседник. `null` — камера выключена. */
    val localVideo: StateFlow<VideoHandle?> get() = engine?.localVideo ?: noVideo

    /** Картинка собеседника. `null` — он себя не показывает или мы отписались. */
    val remoteVideo: StateFlow<VideoHandle?> get() = engine?.remoteVideo ?: noVideo

    private val noVideo = MutableStateFlow<VideoHandle?>(null)

    // ── ГРУППОВОЙ ЗВОНОК (ПЛАН-(ГЗ)-ГРУППОВЫХ-ЗВОНКОВ ГЗ3) ─────────────────────────────

    /**
     * Групповой звонок: группа, её название, создатель, правила. `null` — звонок на двоих.
     * Окно 0 по этому признаку рисует сетку участников вместо одного собеседника.
     */
    var group by mutableStateOf<GroupCallView?>(null)
        private set

    /** Участники группового звонка — каждый со своей картинкой. */
    val peers: StateFlow<List<CallPeer>> get() = engine?.peers ?: noPeers
    private val noPeers = MutableStateFlow<List<CallPeer>>(emptyList())

    /**
     * Участников больше, чем видео по правилам (решение 4: больше 8 — только голос): своя
     * камера выключена и не включается, чужое видео не принимаем.
     */
    var voiceOnly by mutableStateOf(false)
        private set

    /**
     * Запрет создателя (уточнение заказчика 2026-10-01): сервер не принимает от меня звук или
     * видео, пока создатель не разрешит. Кнопки не включают запрещённое — говорят почему.
     */
    var micForbidden by mutableStateOf(false)
        private set
    var videoForbidden by mutableStateOf(false)
        private set

    /**
     * Порядок участников группового: вошедший — в конец, ушедший выпадает (заказчик
     * 2026-10-01, 4б).
     */
    var peerOrder by mutableStateOf<List<String>>(emptyList())
        private set

    /** Чьё видео принимать — видимых на странице сетки (2а). */
    fun showPeers(identities: Set<String>) {
        scope.launch { engine?.setVisiblePeers(identities) }
    }

    /** Сказано ли в журнал, что стенд отменяет потолок, — один раз за звонок. */
    private var benchCeilingSaid = false

    /** Своё на паузе создателя: что было включено до неё — вернуть после (решение 5). */
    private var beforePause: Pair<Boolean, Boolean>? = null

    /** Групповой ли идущий звонок — для решений вне окна (кто ушёл, конец по ленте). */
    val isGroup: Boolean get() = group != null

    private var peerId: String = ""

    /** Кто на другом конце — чтобы окно подтянуло его имя, когда приедет карточка. */
    val peerUserId: String get() = peerId
    private var callId: String = ""

    /**
     * Дверь идущего звонка — чтобы в ту же комнату можно было **вернуться**.
     *
     * Нужна одному: смене набора публикации на ходу (С-В5). Токен там же, и он живёт
     * дольше разговора, так что второй раз к серверу идти не надо — а значит перезаход не
     * трогает сигналинг вовсе: звонок на сервере тот же, собеседник никуда не выходит.
     */
    private var door: CallDoor? = null
    private var ticking: Boolean = false

    /**
     * Видео это звонок или голос — **переменная окна 0**, её показывает переключатель
     * справа от «Перезвонить».
     *
     * Ставится тем, как окно открыли: исходящий или входящий видеовызов — видео, иначе
     * голос. Дальше её ведёт камера: включил посреди разговора — звонок стал видео,
     * выключил — голосом. «Перезвонить» звонит тем, что здесь, а человек может
     * переключить до нажатия.
     *
     * **В настройки не пишется** — решение заказчика 2026-09-25: это свойство звонка, а
     * не привычка человека, и живёт оно, пока живёт окно 0.
     *
     * Принявший видеовызов показывает себя сразу (ЗВ9) — тоже по ней.
     */
    var video by mutableStateOf(false)
        private set

    /** Сторож набора: гасит звонок, на который никто не ответил. */
    private var watchdog: Job? = null

    /** Умеет ли эта платформа звонить вообще. `false` — кнопок «позвонить» нет. */
    val possible: Boolean get() = engine != null

    /**
     * Идёт ли звонок **на самом деле**.
     *
     * ── ЧЕМ ЭТО ОТЛИЧАЕТСЯ ОТ [active] ──────────────────────────────────────
     *
     * [active] значит «окно 0 показано». После завершения оно остаётся показанным
     * нарочно: там «Перезвонить» и «Закрыть», и закрывает его человек, когда прочитал.
     *
     * Но **«окно открыто» и «я занят» — разные вещи**, и подмена стоила проверки
     * 2026-09-20: пока человек не нажал «Закрыть», входящие к нему не доходили вовсе.
     * В журнале это видно дословно — `CALL второй входящий во время звонка — не показан`
     * на завершённом звонке (отчёты `B4AJ` и `BKGW`, один и тот же `callId` на обоих
     * концах). Со стороны звонящего это выглядело как «Звоним…» без конца, со стороны
     * принимающего — как тишина.
     *
     * Работало же после того, как экран гас: Android пересоздавал окно, `CallHost`
     * собирался заново, и [active] сбрасывался сам.
     */
    private val busy: Boolean get() = active && state.stage != CallStage.Ended

    /** Идёт ли звонок — для уведомлений: во время звонка сообщения без звука (2026-10-02). */
    val busyNow: Boolean get() = busy

    init {
        // «Завершить» из шторки — тем же путём, что кнопка окна 0 (ПЛАН-(В)-ВИДЕО.md В11).
        CallNoticeActions.hangUp = { hangUp() }
        engine?.let { live ->
            // Гудок вызова — пока свой звонок на двоих дозванивается. Ответили, отменили,
            // кончился — гудок гаснет сам: условие перестало быть правдой.
            scope.launch {
                androidx.compose.runtime.snapshotFlow {
                    active && !incoming && group == null && state.stage == CallStage.Connecting
                }.distinctUntilChanged().collect { dialing ->
                    if (dialing) Journal.note(LogCode.CALL, "гудок вызова")
                    ringback(dialing)
                }
            }
            scope.launch {
                live.state.collectLatest { fresh ->
                    val was = state
                    state = fresh
                    if (fresh.stage == CallStage.Connected) startTicking()
                    if (fresh.stage == CallStage.Ended) stopTicking()
                    noticed(was, fresh)
                    if (fresh.roomPaused != was.roomPaused && group != null) paused(fresh.roomPaused)
                    nearEar(fresh)
                    screenOn(fresh)
                }
            }
            // Число участников группового — потолок видео (решение 4); порядок — по входу.
            scope.launch {
                live.peers.collect { list ->
                    peerOrder = io.tima.feature.call.groupOrder(peerOrder, list.map { it.identity })
                    if (group != null && busy) countChanged(list.size + 1)
                }
            }
        }
    }

    /**
     * Ждать ли ответа дальше.
     *
     * ── ПОЧЕМУ СРОК ВООБЩЕ НУЖЕН ────────────────────────────────────────────
     *
     * До 2026-09-20 звонок, на который не ответили, **висел вечно**: сервер таймаута не
     * ставит, собеседник мог просто не взять телефон в руки — и «Звоним…» шло до тех пор,
     * пока звонящий не отменял сам. Для него это выглядело как поломка: телефон делает
     * вид, что дозванивается, а дозвониться уже не к кому.
     *
     * ── ПОЧЕМУ НА КЛИЕНТЕ, А НЕ НА СЕРВЕРЕ ──────────────────────────────────
     *
     * Правильное место — сервер: он переживёт убитое приложение и скажет обоим разом.
     * Но там нужен отложенный обход звонков, которого нет, а API менять сейчас нельзя
     * (`Plan.md §0.0`, решение 5: формат и ручки стоят, пока v2 не заработает). Здесь же
     * это один `delay` и уже существующий `/end`, который сервер понимает как `missed` и
     * рассылает второму.
     *
     * **Сторож стоит у обеих сторон.** У звонящего он кончает набор, у принимающего —
     * снимает вызов, который некому больше показывать. Второму хватило бы и слова
     * сервера, но слово это приходит от первого, и если первый умер, ждать его нечего.
     */
    private fun watch() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(RINGING_LIMIT_MS)
            if (!active || state.stage == CallStage.Connected || state.stage == CallStage.Ended) return@launch
            Journal.note(
                LogCode.CALL,
                "никто не ответил — кладу трубку",
                "звонок" to callId.take(8),
                "ждали" to RINGING_LIMIT_MS / 1000,
            )
            note(if (incoming) words().call.missedCall else words().call.noAnswer)
            if (incoming && group != null) {
                // Групповой вызов не «отклонён», а пропущен: `/end` сказал бы серверу
                // «отказался», и пропущенного у человека не осталось бы. Звонок у
                // остальных идёт, и войти в него можно и позже.
                watchdog?.cancel()
                stopTicking()
                state = state.copy(stage = CallStage.Ended)
                return@launch
            }
            hangUp()
        }
    }

    /** Позвонить. Дверь открывает сервер; движок в неё входит. */
    fun start(peerId: String, peerName: String, video: Boolean) {
        val live = engine ?: return
        if (busy) {
            // **Один сеанс за раз — и это правило про исходящие тоже.** 2026-09-20 окно
            // звонка не показывалось (подокно переписки его закрывало), человек жал
            // «позвонить» ещё и ещё — и сервер завёл три звонка подряд одному и тому же
            // собеседнику, а тому пришло три вызова. Ту беду починили в другом месте, но
            // запрет нужен сам по себе: кнопка, начинающая второй звонок поверх первого,
            // не имеет смысла ни при какой причине нажатия.
            //
            // Проверяется [busy], а не `active`: завершённый звонок занятым не считается,
            // даже если его окно ещё на экране.
            Journal.note(LogCode.CALL, "звонок уже идёт — второй не начат", "кому" to peerId.take(8))
            return
        }
        forgetGroup()
        this.peerId = peerId
        this.video = video
        peer = peerName
        incoming = false
        active = true
        seconds = 0
        events.clear()
        delivered = false
        state = CallState(stage = CallStage.Connecting)
        watch()
        // Разрешение спрашивается ДО похода на сервер: отказавший человек не должен
        // оставить за собой начатый звонок, на который собеседнику покажут вызов.
        withAccess(video) {
            scope.launch {
                when (val step = calls.start(peerId, video)) {
                    is CallStep.Door -> {
                        callId = step.door.callId
                        door = step.door
                        Journal.note(LogCode.CALL, "звонок начат", "кому" to peerId.take(8), "видео" to video)
                        live.connect(step.door, publishFor(step.door))
                        if (!stillOurs(step.door.callId)) return@launch
                        told()
                        // Видеозвонок показывает себя сразу, не дожидаясь нажатия (ЗВ9):
                        // разрешение уже спрошено выше — `withAccess(video)`.
                        if (video) live.setCamera(true)
                    }
                    else -> refuse(step)
                }
            }
        }
    }

    /**
     * Нам звонят. Состояние ставится сразу, до всякой сети: человек должен увидеть
     * входящий немедленно, а не после похода на сервер.
     */
    fun ring(callId: String, fromId: String, fromName: String, video: Boolean) {
        // ── ПОВТОР ТОГО ЖЕ ЗВОНКА — НЕ ВТОРОЙ ЗВОНОК ────────────────────────
        //
        // Сервер повторяет `call.incoming`, пока звонок звонит: живая доставка идёт через
        // Redis Pub/Sub, то есть «не более одного раза», и один потерянный кадр означал,
        // что человеку просто не позвонили (беда 2026-09-20, отчёты `C6DF` и `DW78`).
        //
        // **Без этой проверки повтор был бы смертелен.** Дубликат пришёл бы, пока звонок
        // показан, попал бы в ветку «занят» ниже — и мы положили бы трубку собственному
        // живому вызову.
        if (active && callId == this.callId) {
            Journal.note(LogCode.CALL, "повтор того же входящего — уже показан", "звонок" to callId.take(8))
            return
        }

        if (busy) {
            // Один сеанс за раз. Второй звонок не показываем, но и не прячем молча —
            // в журнал он попадает, иначе «мне звонили, а телефон молчал» не разобрать.
            // Ровно эта запись и назвала беду 2026-09-20, когда условием было `active`.
            Journal.note(LogCode.CALL, "второй входящий во время звонка — не показан", "звонок" to callId.take(8))
            // **И сказать серверу — с причиной.** «Занят» знает только этот телефон:
            // сервер состояние «разговаривает» закрыть сам не может, а проверка по нему
            // однажды заперла всех на пять часов. Звонящий по этому слову скажет
            // «собеседник занят» вместо «никто не ответил».
            scope.launch { calls.end(callId, busy = true) }
            return
        }
        // Входящий личный — прежний групповой забыть: иначе «Принять» шло входом в группу,
        // а окно рисовалось групповым (отчёт 2FDW, 2026-10-02).
        forgetGroup()
        this.callId = callId
        this.video = video
        peerId = fromId
        peer = fromName
        incoming = true
        active = true
        seconds = 0
        events.clear()
        delivered = false
        state = CallState(stage = CallStage.Connecting, callId = callId)
        watch()
        Journal.note(LogCode.CALL, "входящий звонок", "от" to fromId.take(8), "видео" to video)
        // Нечем говорить (ПК0): окно покажет «ответьте на телефоне» вместо «Принять».
        if (engine == null) Journal.note(LogCode.CALL, "на этом устройстве звонков нет — показан без «Принять»")
    }

    /** Принять входящий: сервер выдаёт токен той же комнаты. */
    fun accept() {
        val live = engine ?: return
        // Тот же вопрос, что и при исходящем, и по той же причине: без микрофона комната
        // соединится, а звука не будет ни в одну сторону.
        withAccess(video) {
            scope.launch {
                // Групповой — вход в звонок группы, а не ответ: отвечать некому (ГЗ3).
                val step = if (group != null) calls.join(callId) else calls.answer(callId)
                when (step) {
                    is CallStep.Door -> {
                        door = step.door
                        live.connect(step.door, publishFor(step.door))
                        if (!stillOurs(step.door.callId)) return@launch
                        told()
                        // **Принял видеозвонок — показываешь себя.** Так решил заказчик
                        // 2026-09-20: отдельного согласия на камеру не спрашиваем, его
                        // дал сам ответ на видеовызов. Разрешение системы при этом
                        // спрошено — `withAccess(video)` выше.
                        if (video) live.setCamera(true)
                    }
                    else -> refuse(step)
                }
            }
        }
    }

    /**
     * На звонок ответили **на другом устройстве** этого же человека.
     *
     * Закрываем окно и только. Положить трубку здесь значило бы закончить чужой живой
     * разговор — тот самый случай, ради которого слово и заведено отдельным.
     *
     * Разговаривающее устройство сюда не попадает: сервер ему `taken` не шлёт. Проверка
     * всё равно стоит — состояние дешевле доверия.
     */
    fun takenElsewhere() {
        if (state.stage == CallStage.Connected) return
        Journal.note(LogCode.CALL, "ответили на другом устройстве", "звонок" to callId.take(8))
        close()
    }

    /**
     * Звонок кончил сервер, и сказал чем.
     *
     * `busy` — собеседник занят другим разговором. Слово приходит не из базы, а от его
     * телефона: только он знает, что разговаривает. Проверка по состоянию в базе была и
     * однажды заперла всех на пять часов — незакрытая строка делала человека занятым,
     * пока не истечёт окно (2026-09-20).
     */
    fun ended(why: String) {
        // ── ГРУППОВОЙ, УЖЕ ИДУЩИЙ: «ПРОПУЩЕННЫЙ» — НЕ КОНЕЦ ──────────────────────
        //
        // «Пропущенный» и «отклонил» говорят про вызов, а не про звонок. Пришли в идущий
        // разговор — значит сервер не знает, что мы вошли (2026-10-01: события LiveKit
        // не принимались, и через 50 с трубку клали все, включая создателя). Конец
        // группового — остановка создателем или удаление, а не слово о вызове.
        if (group != null && why in GROUP_CALL_WORDS &&
            (state.stage == CallStage.Connected || state.stage == CallStage.Reconnecting)
        ) {
            Journal.trouble(LogCode.CALL, "групповой: слово о вызове пришло в идущий звонок — трубку не кладём", "слово" to why, "звонок" to callId.take(8))
            return
        }
        when {
            why == BUSY -> {
                Journal.note(LogCode.CALL, "собеседник занят", "кому" to peerId.take(8))
                note(words().call.peerBusy)
                if (group == null) busyTone()
            }
            // Исходы звонящего словами (ВЗ0а): отклонил — не то же, что не ответил, и
            // человек поступает по-разному — второму перезванивают, первому нет. Гудок
            // у отклонённого тот же «занято», как у обычного телефона.
            !incoming && why == DECLINED -> {
                note(words().call.peerDeclined)
                if (group == null) busyTone()
            }
            !incoming && why == MISSED -> note(words().call.noAnswer)
        }
        hangUp()
    }

    /** Отклонить или положить трубку — для сервера это одно и то же. */
    fun hangUp() {
        val id = callId
        // **Второй раз класть нечего.** В журнале это видно парами: два «звонок закончен»
        // с одним идентификатором и два `POST /end` подряд (отчёт `BKGW` 2026-09-20).
        // Сервер такое переживает, а вот отчёт становится вдвое длиннее и вдвое менее
        // понятным — по нему кажется, что звонков было два.
        if (!active || state.stage == CallStage.Ended) return
        watchdog?.cancel()
        service.off()
        scope.launch {
            engine?.disconnect()
            if (id.isNotEmpty()) calls.end(id)
        }
        Journal.note(
            LogCode.CALL, if (group != null) "вышли из группового звонка" else "звонок закончен",
            "звонок" to id.take(8), "длился" to seconds,
        )
        stopTicking()
        state = state.copy(stage = CallStage.Ended)
    }

    /**
     * Перезвонить тому же человеку.
     *
     * Здесь, а не на экране: экран не знает идентификатора собеседника — он знает имя.
     * Держать идентификатор на экране значило бы отдать ему работу сигналинга.
     */
    fun again() {
        // Групповой — у окна свои кнопки: «Создать звонок» у автора, «Присоединиться» у
        // остальных ([createAgain]); «Перезвонить» здесь только для звонка на двоих.
        if (group != null) return
        val id = peerId
        val name = peer
        if (id.isEmpty()) return
        val kind = video
        close()
        start(id, name, video = kind)
    }

    /** Переключатель «голос · видео» на окне 0: чем перезвонить. */
    fun redialAs(video: Boolean) {
        this.video = video
    }

    /**
     * Конец группового, я автор — «Создать звонок»: в той же группе, с теми же галочками,
     * что в прошлый раз; не начинал здесь — «Звонить» выключено, позвать всех.
     */
    fun createAgain() {
        val g = group ?: return
        val last = lastStart?.takeIf { it.groupId == g.groupId }
        val kind = video
        close()
        startGroup(g.groupId, g.title, last?.ring ?: false, kind, last?.invited.orEmpty())
    }

    /**
     * Повторить групповой звонок в группе — автору, из вкладки «Звонки» и из чата звонка
     * (заказчик 2026-10-08). Тем же правилом, что [createAgain]: с теми же галочками, что в
     * прошлый раз; не начинал здесь — «Звонить» выключено, позвать всех. Окно прошлого
     * звонка закрывается: новый начинается с чистого листа.
     */
    fun repeatGroup(groupId: String, title: String, video: Boolean) {
        val last = lastStart?.takeIf { it.groupId == groupId }
        if (active && state.stage == CallStage.Ended) close()
        startGroup(groupId, title, last?.ring ?: false, video, last?.invited.orEmpty())
    }

    /**
     * Забыть групповой — личный звонок начинается с чистого листа. Окно прошлого группового
     * могло остаться открытым («Перезвонить» ещё на экране), и пометка «звонок групповой»
     * переезжала в следующий личный: вход вместо ответа, сетка вместо собеседника.
     */
    private fun forgetGroup() {
        group = null
        lastStart = null
        peerOrder = emptyList()
        benchCeilingSaid = false
        voiceOnly = false
        micForbidden = false
        videoForbidden = false
        beforePause = null
        pendingInvites = null
    }

    /** Закрыть окно 0: звонка больше нет. */
    /**
     * Сказать системе, что идёт звонок, — служба переднего плана (ЗВ14).
     *
     * Зовётся **после входа в комнату**, а не при нажатии: служба нужна ровно тогда,
     * когда пошло медиа. И только оттуда, где человек только что нажал кнопку — с
     * Android 12 из фона её не поднять, и законное окно для этого даст лишь
     * высокоприоритетный push, которого у нас пока нет.
     */
    private fun told(connectedAt: Long = toldAt) {
        toldAt = connectedAt
        toldOnce = true
        // Камера — в тип службы, только когда человек выбрал показывать себя свёрнутым (1в):
        // без этого типа HyperOS отбирает камеру у свёрнутого приложения через секунды.
        service.want(
            ServiceWish(
                words().call.activeCall, peer.ifBlank { words().chat.nameless }, words().call.hangUp, connectedAt,
                camera = cameraInBackground() && state.cameraOn,
            ),
        )
    }

    /** Служба звонка поднята в этом звонке; с какого времени идёт счётчик. */
    private var toldOnce = false
    private var toldAt = 0L

    // ── СВЕРНУЛИ ПОСРЕДИ ВИДЕОЗВОНКА (заказчик 2026-10-01, 1б и 1в) ─────────────
    //
    // HyperOS отбирает камеру у свёрнутого приложения через несколько секунд, и видео не
    // возвращалось до конца звонка (`БЕДЫ/2026-10-01-камера-в-фоне.md`). По умолчанию (1б)
    // своё видео через 2 с фона ставится на паузу — собеседник видит «свернул приложение»;
    // вернулись — камера включается. 2 с — чтобы взгляд в шторку не мигал паузой. С
    // настройкой «продолжать показывать» (1в) паузы нет: камеру держит тип службы.
    // В обоих случаях после возврата сторож проверяет, идут ли кадры, и открывает камеру
    // заново, если нет.

    private var pauseJob: kotlinx.coroutines.Job? = null
    private var pausedByBackground = false

    /** Окно приложения на экране или нет — зовёт платформа ([CallKeep.visible]). */
    fun appVisible(visible: Boolean) {
        if (!busy) return
        if (!visible) {
            if (!state.cameraOn || cameraInBackground()) return
            pauseJob?.cancel()
            pauseJob = scope.launch {
                kotlinx.coroutines.delay(PAUSE_AFTER_MS)
                if (!busy || !state.cameraOn) return@launch
                Journal.note(LogCode.CALL, "свернули — своё видео на паузу")
                engine?.announcePaused(true)
                engine?.setCamera(false)
                pausedByBackground = true
            }
            return
        }
        pauseJob?.cancel()
        pauseJob = null
        scope.launch {
            if (pausedByBackground) {
                pausedByBackground = false
                Journal.note(LogCode.CALL, "вернулись — своё видео снова идёт")
                engine?.setCamera(true)
                engine?.announcePaused(false)
            }
            cameraWatch()
        }
    }

    /**
     * Идут ли кадры своей камеры после возврата. Нет — открыть её заново: система могла
     * отобрать её в фоне, а сама она не вернётся.
     */
    private suspend fun cameraWatch() {
        val live = engine ?: return
        kotlinx.coroutines.delay(CAMERA_SETTLE_MS)
        if (!busy || !state.cameraOn) return
        val before = live.cameraFrames() ?: return
        kotlinx.coroutines.delay(CAMERA_PROBE_MS)
        if (!busy || !state.cameraOn) return
        val after = live.cameraFrames() ?: return
        if (after > before) return
        Journal.trouble(LogCode.CALL, "камера не даёт кадров после фона — открываем заново", "кадров" to after)
        live.restartCamera()
    }

    /**
     * Жив ли ещё звонок [id] — после входа в комнату (БЕДЫ 2026-09-30-служба-звонка-после-отмены).
     *
     * Вход в комнату — `suspend`, и пока он шёл, трубку могли положить: `hangUp` уже
     * погасил службу и отменил вход, а код после `connect` всё равно выполняется. Без этой
     * проверки он поднимал службу «идёт звонок» у законченного звонка и включал камеру —
     * Redmi 2026-09-30 закрылся с `ForegroundServiceDidNotStartInTimeException`.
     */
    private fun stillOurs(id: String): Boolean {
        if (busy && callId == id) return true
        Journal.note(LogCode.CALL, "звонок кончился, пока входили в комнату — службу и камеру не поднимаем", "звонок" to id.take(8))
        return false
    }

    /** Тот ли это звонок, который у нас идёт. Чужой конец нашего разговора не касается. */
    fun callIs(id: String): Boolean = id.isNotEmpty() && id == callId

    /**
     * Тот ли это человек, с кем мы говорим. Нужен, чтобы понять, чей уход нас касается. В
     * групповом — никто: уход одного из пятерых разговора не кончает.
     */
    fun peerIs(userId: String): Boolean = group == null && userId.isNotEmpty() && userId == peerId

    fun close() {
        watchdog?.cancel()
        service.off()
        nearEarOn = false
        callProximity(false)
        screenOnNow = false
        callKeepScreen(false)
        pauseJob?.cancel()
        pauseJob = null
        pausedByBackground = false
        toldOnce = false
        toldAt = 0L
        active = false
        callId = ""
        peerId = ""
        peer = ""
        seconds = 0
        group = null
        peerOrder = emptyList()
        voiceOnly = false
        micForbidden = false
        videoForbidden = false
        beforePause = null
        pendingInvites = null
        state = CallState()
    }

    fun microphone(on: Boolean) {
        if (on && micForbidden) {
            // Запрет создателя: сервер звук не примет — говорим, а не делаем вид.
            note(words().groupCall.mutedMic, whileTrue = MIC_FORBIDDEN)
            return
        }
        scope.launch { engine?.setMicrophone(on) }
    }

    /** Кнопка «Динамик» — громкая или разговорный (ПЛАН-(В)-ВИДЕО.md В9). */
    fun speaker(on: Boolean) {
        scope.launch { engine?.setSpeaker(on) }
    }

    /** Кнопка «Переключение камеры» — передняя ↔ задняя (ПЛАН-(В)-ВИДЕО.md В9). */
    fun switchCamera() {
        scope.launch { engine?.switchCamera() }
    }

    /** Взята ли блокировка «гасить экран у уха». */
    private var nearEarOn = false

    /**
     * Датчик приближения — вариант 2а (ПЛАН-(В)-ВИДЕО.md В10): гасить экран у уха **только в
     * голосовом разговоре и только когда звук в разговорном динамике**. В видеозвонке, при
     * громкой связи и в наушниках телефон к уху не подносят, и погасший от ладони экран
     * только мешал бы.
     */
    private fun nearEar(now: CallState) {
        val want = nearEarWanted(active, now)
        if (want == nearEarOn) return
        nearEarOn = want
        callProximity(want)
    }

    /** Держим ли экран включённым. */
    private var screenOnNow = false

    /** Видеозвонок — экран не гаснет (решение заказчика 2026-09-30). */
    private fun screenOn(now: CallState) {
        val want = screenOnWanted(active, now)
        if (want == screenOnNow) return
        screenOnNow = want
        callKeepScreen(want)
    }

    /**
     * Показать или перестать показывать себя.
     *
     * **Разрешение спрашивается здесь, а не при начале звонка.** Голосовой звонок камеры
     * не просит, и брать её впрок нечем объяснить — правило то же, что у микрофона
     * (`core-call/Access.kt`). Зато в момент нажатия объяснение очевидно: человек только
     * что попросил показать себя.
     *
     * Выключение разрешения не требует вовсе: перестать показывать можно всегда.
     */
    fun camera(on: Boolean) {
        if (on && voiceOnly) {
            // Больше 8 — только голос (решение 4): правило сервера, а не поломка камеры.
            note(words().groupCall.voiceOnly(group?.rules?.videoUpTo ?: 8))
            return
        }
        if (on && state.roomPaused) {
            note(words().groupCall.pausedWait)
            return
        }
        if (on && videoForbidden) {
            note(words().groupCall.mutedVideo, whileTrue = VIDEO_FORBIDDEN)
            return
        }
        if (!on) {
            // Выключил камеру — звонок стал голосовым, и перезвонит окно тоже голосом.
            video = false
            scope.launch { engine?.setCamera(false) }
            return
        }
        access(true) { allowed ->
            if (allowed) {
                // Разрешили — просьба выполнена, и висеть ей больше незачем.
                forget(NO_CAMERA)
                video = true
                scope.launch { engine?.setCamera(true) }
            } else {
                // Звонок продолжается — это не беда звонка, а отказ в камере. Молчать
                // нельзя: нажатая кнопка, после которой ничего не произошло, читается
                // как поломка, и в неё жмут повторно.
                Journal.trouble(LogCode.CALL, "камеру не разрешили", "звонок" to callId.take(8))
                // Кнопка здесь ровно по той же причине, что и у микрофона: Android после
                // второго отказа диалог больше не показывает, и включить камеру можно
                // только в настройках телефона. Забыть её тут было тем же самым, что
                // сказать «включается в настройках» и не дать туда пути.
                note(words().call.noCamera, CallAction.OpenSettings, whileTrue = NO_CAMERA)
            }
        }
    }

    /**
     * Принимать ли чужое видео — ЗВ11.
     *
     * Отписка, а не занавеска: собеседник включает камеру, не спрашивая нас, и за приём
     * платит наш трафик. Спрятать картинку, продолжая её качать, значило бы не оставить
     * выхода вовсе.
     */
    fun remoteVideo(take: Boolean) {
        scope.launch { engine?.setRemoteVideo(take) }
    }

    /**
     * Из смены состояния — событие.
     *
     * **Только то, чего человек не увидит сам.** Включённый микрофон виден по кнопке, а
     * вот «собеседник показывает себя, а вы нет» по экрану не читается: картинка просто
     * появляется, и почему своя не появилась — непонятно.
     */
    private fun noticed(was: CallState, now: CallState) {
        val words = words().call

        // ── ДЛЯЩИЕСЯ: появляются и СНИМАЮТСЯ ────────────────────────────────
        //
        // «Собеседник показывает себя, ваша камера выключена» — правда ровно до того
        // мгновения, когда человек включил камеру. Раньше строка висела и после, то есть
        // продолжала утверждать неверное (заказчик 2026-09-20).
        // Камера заработала — значит её разрешили, и просьба выполнена. Это общее
        // правило ленты: **событие, которое чего-то просит, снимается, когда просьбу
        // исполнили** (заказчик 2026-09-20). Второй такой случай — «собеседник
        // показывает себя, ваша камера выключена» ниже.
        if (now.cameraOn) forget(NO_CAMERA)

        val peerAlone = now.remoteVideoShown && !now.cameraOn
        if (peerAlone) note(words.peerShowsSelf, whileTrue = PEER_ALONE) else forget(PEER_ALONE)

        if (now.videoPaused) note(words.videoPaused, whileTrue = PAUSED) else forget(PAUSED)

        // Собеседник взял трубку — значит его устройство нашлось, и слово о том, что
        // его нет на связи, стало неправдой.
        if (now.stage == CallStage.Connected) forget(OFFLINE)

        val backing = now.stage == CallStage.Reconnecting
        if (backing) note(words.reconnecting, whileTrue = BACKING) else forget(BACKING)

        if (!now.remoteVideoTaken) note(words.remoteHidden, whileTrue = HIDDEN) else forget(HIDDEN)

        // Видео собеседника нет, хотя он его показывает, — и почему (заказчик 2026-09-29).
        // Смена кодека на запасной сюда не попадает: кадры при ней идут. Два ключа, а не
        // один: длящееся событие с тем же ключом не переписывается, а причина может смениться.
        when (val loss = now.remoteVideoLoss) {
            null -> {
                forget(NO_FRAMES)
                forget(NOT_DECODED)
            }
            // «Пожаловаться» — у событий, где беду видно программно (ПЛАН-(В)-ВИДЕО.md В7).
            RemoteVideoLoss.NotArriving -> {
                forget(NOT_DECODED)
                note(words.remoteVideoNotArriving, CallAction.Report, whileTrue = NO_FRAMES)
            }
            is RemoteVideoLoss.NotDecoding -> {
                forget(NO_FRAMES)
                note(words.remoteVideoNotDecoding(loss.codec), CallAction.Report, whileTrue = NOT_DECODED)
            }
        }
        // Уходит не тот кодек, что просили (заказчик 2026-09-29) — видно программно, значит
        // с «Пожаловаться».
        when (val mismatch = now.codecMismatch) {
            null -> forget(CODEC_MISMATCH)
            else -> note(words.sentCodecDiffers(mismatch.asked, mismatch.sent), CallAction.Report, whileTrue = CODEC_MISMATCH)
        }

        // Прогон стенда: кодек набора телефону не по силам — своё видео собеседник не увидит.
        val unsent = now.ownVideoUnsent
        if (unsent != null) {
            note(words.ownCodecUnsupported(unsent), CallAction.Report, whileTrue = OWN_UNSENT)
        } else {
            forget(OWN_UNSENT)
        }

        // Собеседник свернул приложение — его видео на паузе (1б): длится, пока не вернётся.
        if (now.peerPaused) note(words.peerPausedVideo, whileTrue = PEER_PAUSED) else forget(PEER_PAUSED)

        // ── СЛУЧИВШИЕСЯ: остаются ──────────────────────────────────────────
        if (!now.remoteVideoShown && was.remoteVideoShown && now.remoteVideoTaken && !now.peerPaused) note(words.peerStoppedVideo)

        // Камеру включили или выключили при «продолжать показывать» — тип службы следом (1в).
        if (now.cameraOn != was.cameraOn && toldOnce && busy && cameraInBackground()) told()

        // ── ТО ЖЕ САМОЕ В ЖУРНАЛ ────────────────────────────────────────────
        //
        // **Отчёт о проблеме обязан отвечать на вопрос «а что вообще происходило».** До
        // 2026-09-20 журнал знал ровно две вещи: «звонок начат» и «звонок закончен». Между
        // ними могло не быть ни звука, ни соединения, ни картинки — и в отчёте это
        // выглядело одинаково. Немой звонок нашёлся не по журналу, а по расстоянию между
        // строками; второй раз такого везения может не случиться.
        if (now.stage != was.stage) {
            Journal.note(LogCode.CALL, "стадия звонка", "стала" to now.stage.name, "была" to was.stage.name)
            // Ответили или кончилось — ждать больше нечего.
            if (now.stage == CallStage.Connected || now.stage == CallStage.Ended) watchdog?.cancel()
            // Звонка нет — и следа его в шторке быть не должно: висящее уведомление
            // «идёт звонок» хуже отсутствующего, потому что ему верят.
            if (now.stage == CallStage.Ended) {
                service.off()
                // ── И ОТПУСТИТЬ МЕДИА ───────────────────────────────────────
                //
                // **Трубку мог положить собеседник, и тогда наш движок никто не
                // останавливал.** Чужая дорожка при этом снимается сама (её больше нет в
                // комнате), а своя остаётся — вместе с картинкой на экране и включённой
                // камерой. В журнале это видно по отсутствию строки «своя камера
                // включена=false» там, где «видео собеседника идёт=false» есть: отчёт
                // `K89R` 2026-09-20, и заказчик увидел на экране кадр прошлого звонка.
                //
                // Повторный `disconnect` безвреден: комнаты уже нет, и состояние не
                // меняется — поток одинаковые значения не повторяет.
                scope.launch { engine?.disconnect() }
            }
        }
        if (now.microphoneOn != was.microphoneOn) {
            Journal.note(LogCode.CALL, "микрофон", "включён" to now.microphoneOn)
        }
        if (now.cameraOn != was.cameraOn) {
            Journal.note(LogCode.CALL, "своя камера", "включена" to now.cameraOn)
        }
        if (now.remoteVideoShown != was.remoteVideoShown) {
            Journal.note(LogCode.CALL, "видео собеседника", "идёт" to now.remoteVideoShown)
        }
        if (now.remoteVideoTaken != was.remoteVideoTaken) {
            Journal.note(LogCode.CALL, "приём чужого видео", "включён" to now.remoteVideoTaken)
        }
        if (now.videoPaused != was.videoPaused) {
            Journal.note(LogCode.CALL, "видео погашено нехваткой полосы", "погашено" to now.videoPaused)
        }
        if (now.quality != was.quality) {
            Journal.note(LogCode.CALL, "оценка связи", "стала" to now.quality.name)
        }
        // Завершение чужой стороной идёт мимо `hangUp`, и записи о конце не было вовсе:
        // звонок в журнале просто обрывался.
        if (now.stage == CallStage.Ended && was.stage != CallStage.Ended) {
            Journal.note(LogCode.CALL, "звонок кончился", "длился" to seconds, "причина" to (now.trouble ?: "положили трубку"))
        }
    }

    /**
     * Дописать событие.
     *
     * Повтор не дописывается: длящееся событие проверяется на каждом обновлении
     * состояния, а их за звонок десятки — лента иначе состояла бы из одной строки,
     * повторённой сорок раз.
     */
    private fun note(text: String, action: CallAction? = null, whileTrue: String? = null) {
        if (whileTrue != null && events.any { it.whileTrue == whileTrue }) return
        if (whileTrue == null && events.lastOrNull()?.text == text) return
        events.add(CallEvent(seconds = seconds, text = text, action = action, whileTrue = whileTrue))
    }

    /**
     * Вызов не забрало ни одно устройство собеседника (`call.unreachable`).
     *
     * **Звонок при этом продолжается.** Сервер сказал не «человека нет», а «сейчас никто
     * не подтвердил кадр»: устройство вернётся — возьмёт вызов из журнала само, и до
     * конца сорока пяти секунд телефон ещё зазвонит. Поэтому это строка в ленте, а не
     * причина класть трубку, — решать за вернувшееся устройство мы не вправе.
     *
     * Событие длящееся: собеседник появился и ответил — оно снимается, как и всякое
     * отражение уже случившегося (заказчик 2026-09-20).
     */
    /** Вызов дошёл до телефона собеседника (ВЗ0а): «Звонит», и «не в сети» снимается. */
    fun peerRinging(callId: String) {
        if (!active || !callIs(callId) || incoming) return
        if (state.stage != CallStage.Connecting && state.stage != CallStage.Idle) return
        delivered = true
        forget(OFFLINE)
        Journal.note(LogCode.CALL, "вызов доставлен — у собеседника звонит", "звонок" to callId.take(8))
    }

    fun peerOffline(callId: String) {
        if (!active || !callIs(callId)) return
        if (state.stage != CallStage.Connecting) return // уже соединились — слово ложно
        note(words().call.peerOffline, whileTrue = OFFLINE)
        Journal.note(LogCode.CALL, "вызов не забрало ни одно устройство собеседника")
    }

    /** Снять длящееся событие: то, о чём оно говорило, кончилось. */
    private fun forget(whileTrue: String) {
        events.removeAll { it.whileTrue == whileTrue }
    }

    /**
     * Применить выбранный набор к **идущему** звонку — С-В5, решение заказчика 2026-09-21.
     *
     * ── ЧЕГО ЭТО СТОИТ И ПОЧЕМУ ЦЕНА ПРИНЯТА ────────────────────────────────
     *
     * Разговор прерывается на две-три секунды: комната закрывается и открывается заново.
     * Дешевле было бы перепубликовать одну дорожку, но `dynacast` и `adaptiveStream` —
     * свойства комнаты, и перепубликацией они не меняются. Набор, применённый наполовину,
     * хуже непримененного: прогон назывался бы одним, а мерил другое.
     *
     * **Говорим об этом строкой в ленте.** Пропавшая на три секунды картинка без
     * объяснения читается как поломка — ровно та беда, из-за которой заведена лента.
     */
    fun applyPreset() {
        val live = engine ?: return
        if (callId.isEmpty()) return
        // Пока не соединились, применять нечего: набор и так возьмётся при входе.
        if (state.stage != CallStage.Connected && state.stage != CallStage.Reconnecting) return
        // Набор меняют на стенде; вне стенда применять нечего — обычный звонок один.
        val name = preset()?.name ?: return
        scope.launch {
            // ── СНАЧАЛА ДВЕРЬ, ПОТОМ ЛОМАТЬ КОМНАТУ ─────────────────────────
            //
            // Токен живёт две минуты, разговор дольше. Перезаход старым токеном падает с
            // «token is expired» — и **кончает звонок**, потому что прежней комнаты уже
            // нет (живой прогон 2026-09-21). Порядок здесь и есть починка: не получили
            // свежую дверь — говорим словами и ничего не трогаем, разговор продолжается.
            val step = calls.join(callId)
            if (step !is CallStep.Door) {
                Journal.trouble(
                    LogCode.CALL,
                    "набор не применён — сервер не дал войти заново",
                    "ответ" to step::class.simpleName.orEmpty(),
                )
                note(words().call.presetRefused)
                return@launch
            }
            door = step.door
            Journal.note(LogCode.CALL, "набор применён на ходу", "набор" to name)
            note(words().call.presetApplied(name))
            live.reenter(step.door, publishFor(step.door))
        }
    }

    /**
     * Чем публиковать в этот звонок: набор стенда, если он включён, иначе обычный звонок с
     * потолком от сервера. Потолок пишется в журнал с источником: «видео 640×480» без
     * «откуда» не отличить от настройки, которую никто не ставил.
     */
    private fun publishFor(door: CallDoor): PublishPreset {
        preset()?.let { return it }
        val base = basePreset(door.video).let { b ->
            // Групповой: до 4 — 720p, дальше ниже; число на входе неизвестно, потолок
            // пересчитается по участникам, как только они станут видны (решение 4).
            val top = door.group?.rules?.heightFor(1)
            if (top != null) b.cappedTo(top) else b
        }
        Journal.note(
            LogCode.CALL,
            "потолок видео",
            "кадр" to ("" + base.video.width + "×" + base.video.height),
            "к/с" to base.video.fps,
            "бит/с" to base.video.bitrate,
            "откуда" to if (door.video != null) "сервер" else "приложение",
        )
        return base
    }

    // ── ГРУППОВОЙ ЗВОНОК: НАЧАТЬ, ВОЙТИ, КОМАНДЫ ────────────────────────────────

    /**
     * Групповой звонок в группе [groupId] (ГЗ5): «Звонить» — [ring], «Включить видео» —
     * [video], позвать — [invited] (пусто — всех участников группы). Идёт уже — сервер
     * впускает в него же.
     */
    fun startGroup(groupId: String, title: String, ring: Boolean, video: Boolean, invited: List<String>) {
        val live = engine ?: return
        if (busy) {
            Journal.note(LogCode.CALL, "звонок уже идёт — групповой не начат", "группа" to groupId.take(8))
            return
        }
        open(groupId, title, video, incoming = false)
        lastStart = GroupStart(groupId, title, ring, video, invited)
        pendingInvites = invited
        withAccess(video) {
            scope.launch {
                when (val step = calls.startGroup(groupId, ring, video, invited)) {
                    is CallStep.Door -> enter(live, step.door, video)
                    else -> refuse(step)
                }
            }
        }
    }

    /**
     * Войти в идущий групповой звонок — по полосе «Идёт звонок» в группе или по
     * приглашению в личном чате (решение 3а).
     */
    fun joinGroup(callId: String, groupId: String, title: String, video: Boolean) {
        val live = engine ?: return
        if (busy) {
            if (this.callId == callId) return
            Journal.note(LogCode.CALL, "звонок уже идёт — во второй не входим", "звонок" to callId.take(8))
            return
        }
        open(groupId, title, video, incoming = false)
        this.callId = callId
        withAccess(video) {
            scope.launch {
                when (val step = calls.join(callId)) {
                    is CallStep.Door -> enter(live, step.door, video)
                    else -> refuse(step)
                }
            }
        }
    }

    /** Зовут в групповой звонок (решение 3): входящий, принять — войти. */
    fun ringGroup(callId: String, groupId: String, title: String, fromId: String, video: Boolean) {
        if (active && callId == this.callId) return
        if (busy) {
            // Один сеанс за раз. Групповому «занят» не говорим: звонок идёт у остальных, и
            // войти можно будет после — по полосе в группе.
            Journal.note(LogCode.CALL, "зовут в групповой во время звонка — не показан", "звонок" to callId.take(8))
            return
        }
        ring(callId, fromId, title, video)
        group = GroupCallView(groupId = groupId, title = title, creatorId = fromId, mine = false, rules = GroupRules())
    }

    private fun open(groupId: String, title: String, video: Boolean, incoming: Boolean) {
        this.video = video
        this.incoming = incoming
        peerId = ""
        peer = title
        active = true
        seconds = 0
        events.clear()
        delivered = false
        group = GroupCallView(groupId = groupId, title = title, creatorId = "", mine = false, rules = GroupRules())
        state = CallState(stage = CallStage.Connecting)
    }

    /** Вошли в дверь группового: создатель и правила — от сервера. */
    private suspend fun enter(live: CallEngine, door: CallDoor, video: Boolean) {
        callId = door.callId
        this.door = door
        val room = door.group
        val me = myUserId()
        group = group?.copy(
            creatorId = room?.creatorId.orEmpty(),
            mine = room?.creatorId == me && me.isNotEmpty(),
            rules = room?.rules ?: GroupRules(),
        )
        micForbidden = room?.micForbidden == true
        videoForbidden = room?.videoForbidden == true
        Journal.note(
            LogCode.CALL, "групповой звонок: вошли",
            "звонок" to door.callId.take(8), "группа" to (room?.groupId?.take(8) ?: "—"),
            "создатель" to (group?.mine == true), "видео" to video,
        )
        // Начали сами — приглашения в личные чаты позванным (решение 3а). Вход в чужой
        // идущий звонок их не шлёт: позвал его создатель.
        val invites = pendingInvites
        pendingInvites = null
        if (invites != null && group?.mine == true && room != null) onStarted(room.groupId, group?.title.orEmpty(), invites)
        live.connect(door, publishFor(door))
        if (!stillOurs(door.callId)) return
        told()
        // Запрет держится при перезаходе: сервер выдал права без запрещённого, и включать
        // его незачем — дорожку не примут.
        if (micForbidden) live.setMicrophone(false)
        if (micForbidden || videoForbidden) forbidNotes()
        if (room?.paused == true) paused(true)
        if (video && !voiceOnly && !videoForbidden && room?.paused != true) live.setCamera(true)
    }

    /** Кто я — чтобы узнать себя создателем. Ставит сборка; в проверках — пусто. */
    var myUserId: () -> String = { "" }

    /**
     * Звонок начат мной — разослать приглашения в личные чаты: `(groupId, название,
     * позванные)`; пусто — все участники группы. Ставит сборка: она умеет отправлять.
     */
    var onStarted: (String, String, List<String>) -> Unit = { _, _, _ -> }

    /**
     * Строка звонка в переписку группы: `(groupId, ключ, текст)` — пауза, продолжение,
     * «вас удалили» (заказчик 2026-10-01, 8б). Пишет только тот, кто был в звонке: другие о
     * паузе не знают. Ставит сборка — у неё хранилище.
     */
    var onGroupLine: (String, String, String) -> Unit = { _, _, _ -> }

    /** С чем начинали групповой — «Перезвонить» повторяет (решение 6). */
    private var lastStart: GroupStart? = null
    private var pendingInvites: List<String>? = null

    /** Позвать ещё участников группы в идущий звонок — «Добавить» в журнале звонка. */
    fun inviteMore(userIds: List<String>) {
        for (u in userIds) control(GroupControl.Invite, u)
        if (userIds.isNotEmpty()) group?.let { onStarted(it.groupId, it.title, userIds) }
    }

    /** Сменилось число участников — потолок видео по правилам (решение 4). */
    private suspend fun countChanged(count: Int) {
        val rules = group?.rules ?: return
        // 2а (заказчик 2026-10-01): после 8 видео остаётся — с самым низким потолком; полосу
        // держит то, что принимаем только видимых на странице, а не выключение видео у всех.
        val height = rules.heightFor(count) ?: rules.tiers.minOfOrNull { it.height }
        val nowVoice = height == null
        // Стенд включён — набор стенда закон, как в звонке на двоих (заказчик 2026-10-02, 2а):
        // прогон меряет выбранное, а не урезанное потолком.
        if (preset() != null) {
            if (!benchCeilingSaid) {
                benchCeilingSaid = true
                Journal.note(LogCode.CALL, "групповой: стенд включён — потолок по числу участников не применяется", "участников" to count)
            }
        } else {
            engine?.setVideoCeiling(height)
        }
        if (nowVoice == voiceOnly) return
        voiceOnly = nowVoice
        if (nowVoice) {
            Journal.note(LogCode.CALL, "групповой: участников больше, чем видео по правилам", "участников" to count)
            engine?.setRemoteVideo(false)
            note(words().groupCall.voiceOnly(rules.videoUpTo), whileTrue = VOICE_ONLY)
        } else {
            Journal.note(LogCode.CALL, "групповой: видео снова по правилам", "участников" to count)
            engine?.setRemoteVideo(true)
            forget(VOICE_ONLY)
        }
    }

    /** Пауза создателя (решение 5): своё стоит у всех, после — как было. */
    private fun paused(on: Boolean) {
        if (on) {
            if (beforePause == null) beforePause = state.microphoneOn to state.cameraOn
            group?.let { g -> onGroupLine(g.groupId, "call:$callId:pause:" + msNow(), words().groupCall.linePaused) }
            note(words().groupCall.paused, whileTrue = ROOM_PAUSED)
            scope.launch {
                engine?.setMicrophone(false)
                engine?.setCamera(false)
            }
            return
        }
        forget(ROOM_PAUSED)
        val was = beforePause ?: return
        beforePause = null
        group?.let { g -> onGroupLine(g.groupId, "call:$callId:resume:" + msNow(), words().groupCall.lineResumed) }
        note(words().groupCall.resumed)
        scope.launch {
            if (was.first && !micForbidden) engine?.setMicrophone(true)
            if (was.second && !voiceOnly && !videoForbidden) engine?.setCamera(true)
        }
    }

    /**
     * Команда создателя (решения 5, 6, 17). Только у создателя — кнопок у других нет, а
     * сервер чужую команду и так отвергнет.
     */
    fun control(action: GroupControl, userId: String = "") {
        val g = group ?: return
        if (!g.mine || callId.isEmpty()) return
        val id = callId
        scope.launch {
            val done = calls.control(id, action, userId)
            Journal.note(LogCode.CALL, "команда создателя", "команда" to action.wire, "кому" to userId.take(8), "принята" to done)
            if (!done) note(words().groupCall.controlFailed)
        }
        if (action == GroupControl.Stop) {
            note(words().groupCall.stopped)
            hangUp()
        }
    }

    /** Длящиеся строки о запретах: пока запрет стоит — строка есть, сняли — уходит. */
    private fun forbidNotes() {
        val w = words().groupCall
        if (micForbidden) note(w.mutedMic, whileTrue = MIC_FORBIDDEN) else forget(MIC_FORBIDDEN)
        if (videoForbidden) note(w.mutedVideo, whileTrue = VIDEO_FORBIDDEN) else forget(VIDEO_FORBIDDEN)
        if (micForbidden && videoForbidden) note(w.watching, whileTrue = WATCHING) else forget(WATCHING)
    }

    /** Сервер передал команду создателя мне (событие `call.control`). */
    fun controlled(callId: String, action: String, by: String) {
        if (!active || !callIs(callId) || group == null) return
        val w = words().groupCall
        Journal.note(LogCode.CALL, "команда создателя пришла", "команда" to action)
        when (GroupControl.of(action)) {
            GroupControl.MuteMic -> {
                micForbidden = true
                scope.launch { engine?.setMicrophone(false) }
                forbidNotes()
            }
            GroupControl.MuteVideo -> {
                videoForbidden = true
                scope.launch { engine?.setCamera(false) }
                forbidNotes()
            }
            GroupControl.AllowMic -> {
                micForbidden = false
                forbidNotes()
                note(w.allowedMic)
            }
            GroupControl.AllowVideo -> {
                videoForbidden = false
                forbidNotes()
                note(w.allowedVideo)
            }
            GroupControl.Remove -> {
                note(w.removed)
                group?.let { g -> onGroupLine(g.groupId, "call:$callId:removed", w.lineRemoved) }
                watchdog?.cancel()
                service.off()
                scope.launch { engine?.disconnect() }
                stopTicking()
                state = state.copy(stage = CallStage.Ended)
            }
            // Пауза приходит и данными комнаты — там и обрабатывается; событие здесь
            // лишь страхует, если данные комнаты не дошли.
            GroupControl.Pause -> if (!state.roomPaused) paused(true)
            GroupControl.Resume -> if (beforePause != null) paused(false)
            GroupControl.Stop, GroupControl.Invite, GroupControl.Pin, GroupControl.Unpin, null -> Unit
        }
    }

    /** Сделать то, что предлагает событие. */
    fun act(action: CallAction) {
        when (action) {
            CallAction.OpenSettings -> openCallSettings()
            // Отчёт открывает окно — это дело `Root`: он знает, где настройки.
            CallAction.Report -> Unit
        }
    }

    /**
     * Кадр собеседника для отчёта — снимается **в момент нажатия** «Пожаловаться»
     * (ПЛАН-(В)-ВИДЕО.md В8): через секунду беды на картинке может уже не быть.
     */
    suspend fun remoteFrame(): ByteArray? {
        val frame = engine?.remoteFrame()
        Journal.note(LogCode.CALL, "кадр собеседника для отчёта", "снят" to (frame != null), "байт" to (frame?.size ?: 0))
        return frame
    }

    /**
     * Спросить разрешение и, если дали, продолжить.
     *
     * **Отказ заканчивает звонок словами, а не тишиной.** Без микрофона звонок не «хуже»,
     * а невозможен: комната соединяется, собеседник виден, звука нет ни в одну сторону — и
     * ищут такую беду где угодно, кроме разрешения.
     */
    private fun withAccess(video: Boolean, then: () -> Unit) {
        access(video) { allowed ->
            if (allowed) {
                then()
            } else {
                Journal.trouble(LogCode.CALL, "звонок не начался", "причина" to "нет доступа к микрофону")
                // **Сказать серверу, если звонок уже существует.** У входящего звонок
                // заведён до нашего отказа, и молчание оставило бы звонящего слушать
                // «Звоним…» до бесконечности: он не узнал бы ни что ему отказали, ни
                // почему. Исходящий до сервера ещё не дошёл — `callId` пуст, и заканчивать
                // нечего.
                val id = callId
                if (id.isNotEmpty()) scope.launch { calls.end(id) }
                // **Событием, а не строкой под именем.** У события есть кнопка, и здесь
                // она обязательна: Android после второго отказа диалог больше не
                // показывает, и включить микрофон можно только в настройках телефона.
                // Сказать «разрешение включается в настройках» и не дать туда пути —
                // то же самое, что не сказать ничего.
                note(words().call.noMicrophone, CallAction.OpenSettings)
                state = state.copy(stage = CallStage.Ended)
            }
        }
    }

    private fun refuse(step: CallStep) {
        // «Занят» — не беда, а ответ, и человеку он нужен словами. Остальные отказы
        // остаются кодом: код находит строку в обработчике, слова — нет.
        if (step is CallStep.Refused && step.code == BUSY) {
            Journal.note(LogCode.CALL, "собеседник занят", "кому" to peerId.take(8))
            note(words().call.peerBusy)
            busyTone()
            stopTicking()
            watchdog?.cancel()
            state = state.copy(stage = CallStage.Ended)
            return
        }
        val why = when (step) {
            CallStep.NotConfigured -> "звонки не настроены на сервере"
            is CallStep.Offline -> "нет связи с сервером"
            is CallStep.Refused -> "сервер отказал: " + step.code
            is CallStep.Door -> return
        }
        Journal.note(LogCode.CALL, "звонок не начался", "причина" to why)
        stopTicking()
        state = state.copy(stage = CallStage.Ended, trouble = why)
    }

    /**
     * Секунды разговора.
     *
     * Считаются с момента, когда SFU сказал «в комнате», а не с набора: до ответа считать
     * нечего, и показанные там секунды были бы выдумкой.
     */
    private fun startTicking() {
        if (ticking) return
        ticking = true
        // Ответили — в шторке пошёл счётчик разговора (ПЛАН-(В)-ВИДЕО.md В11).
        if (active) told(connectedAt = msNow())
        scope.launch {
            while (isActive && ticking) {
                delay(1000)
                if (ticking) seconds += 1
            }
        }
    }

    private fun stopTicking() {
        ticking = false
    }

    private companion object {
        /** Свернули — через столько своё видео на паузу (1б): взгляд в шторку короче. */
        const val PAUSE_AFTER_MS = 2_000L

        /** После возврата камере дать подняться, потом считать кадры. */
        const val CAMERA_SETTLE_MS = 2_000L
        const val CAMERA_PROBE_MS = 1_500L

        /**
         * Сколько ждём ответа.
         *
         * Сорок пять секунд — столько же, сколько звонит обычный телефон, и на это у
         * человека есть привычка. Меньше — не успеть дойти до телефона; больше — звонящий
         * перестаёт верить, что дозвон вообще кончится.
         *
         * Число одно и на обе стороны: разойдись они, одна сторона гасила бы звонок,
         * который у другой ещё идёт.
         */
        const val RINGING_LIMIT_MS = 45_000L

        /** Код отказа сервера, когда собеседник уже разговаривает (`calls.go`). */
        const val BUSY = "busy"

        /** Слова ленты звонков (ВЗ0а): собеседник отклонил; никто не ответил за срок. */
        const val DECLINED = "declined"
        const val MISSED = "missed"

        /** Слова ленты о вызове — у идущего группового звонка они не конец. */
        val GROUP_CALL_WORDS = setOf("missed", "declined")

        // Ключи длящихся событий. Строками, а не перечнем: их читает только этот файл,
        // и перечень на четыре значения был бы лестницей к одной ступеньке.
        const val PEER_ALONE = "чужое видео при нашей выключенной камере"
        const val PEER_PAUSED = "собеседник свернул приложение"
        const val PAUSED = "видео погашено полосой"
        const val BACKING = "связь возвращается"
        const val HIDDEN = "чужое видео скрыто нами"
        const val NO_CAMERA = "камера не разрешена"
        const val OFFLINE = "устройство собеседника не на связи"
        const val NO_FRAMES = "видео собеседника не приходит"
        const val NOT_DECODED = "видео собеседника не раскодируется"
        const val OWN_UNSENT = "своё видео не уйдёт: кодек набора не по силам"
        const val CODEC_MISMATCH = "уходит не тот кодек, что просили"
        const val VOICE_ONLY = "групповой: только голос"
        const val ROOM_PAUSED = "групповой: пауза создателя"
        const val MIC_FORBIDDEN = "групповой: микрофон запрещён"
        const val VIDEO_FORBIDDEN = "групповой: видео запрещено"
        const val WATCHING = "групповой: только смотрю"
    }
}

/**
 * Групповой звонок глазами окна 0.
 *
 * @param mine я создатель — у меня команды (решение 6).
 */
/** С чем начинали групповой звонок. */
private data class GroupStart(
    val groupId: String,
    val title: String,
    val ring: Boolean,
    val video: Boolean,
    val invited: List<String>,
)

data class GroupCallView(
    val groupId: String,
    val title: String,
    val creatorId: String,
    val mine: Boolean,
    val rules: GroupRules,
)

/** Дверь, собранная сигналингом: адрес SFU, комната и токен. */
internal typealias Door = CallDoor

/** Гасить ли экран у уха (ПЛАН-(В)-ВИДЕО.md В10, вариант 2а). Отдельно — ради проверки. */
/**
 * Не гасить экран (решение заказчика 2026-09-30): звонок идёт и в нём есть видео — своё или
 * собеседника. Скрытое нами видео собеседника звонок видеозвонком быть не перестаёт: скрыли
 * на минуту — и экран не должен погаснуть за эту минуту.
 */
internal fun screenOnWanted(active: Boolean, state: CallState): Boolean =
    active &&
        (state.stage == CallStage.Connecting || state.stage == CallStage.Connected || state.stage == CallStage.Reconnecting) &&
        (state.cameraOn || state.remoteVideoShown)

internal fun nearEarWanted(active: Boolean, state: CallState): Boolean =
    active && state.stage == CallStage.Connected && !state.cameraOn && state.sound == SoundRoute.Earpiece
