package io.tima.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.tima.core.call.CallAction
import io.tima.core.call.CallDoor
import io.tima.core.call.CallEngine
import io.tima.core.call.CallEvent
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.CallStep
import io.tima.core.call.Calls
import io.tima.core.call.PublishPreset
import io.tima.core.call.VideoHandle
import io.tima.core.call.askCallAccess
import io.tima.core.call.callOngoing
import io.tima.core.call.callOngoingOff
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
     * **Читается он и при выключенном флаге** (ПЛАН-СТЕНДА §4): выбранный набор
     * становится обычным поведением приложения, уходит только испытательная обвязка.
     */
    private val preset: () -> PublishPreset = { PublishPreset(name = "умолчание") },
) {
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

    private var peerId: String = ""
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

    init {
        engine?.let { live ->
            scope.launch {
                live.state.collectLatest { fresh ->
                    val was = state
                    state = fresh
                    if (fresh.stage == CallStage.Connected) startTicking()
                    if (fresh.stage == CallStage.Ended) stopTicking()
                    noticed(was, fresh)
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
                        live.connect(step.door, preset())
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
    }

    /** Принять входящий: сервер выдаёт токен той же комнаты. */
    fun accept() {
        val live = engine ?: return
        // Тот же вопрос, что и при исходящем, и по той же причине: без микрофона комната
        // соединится, а звука не будет ни в одну сторону.
        withAccess(video) {
            scope.launch {
                when (val step = calls.answer(callId)) {
                    is CallStep.Door -> {
                        door = step.door
                        live.connect(step.door, preset())
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
        when {
            why == BUSY -> {
                Journal.note(LogCode.CALL, "собеседник занят", "кому" to peerId.take(8))
                note(words().call.peerBusy)
            }
            // Исходы звонящего словами (ВЗ0а): отклонил — не то же, что не ответил, и
            // человек поступает по-разному — второму перезванивают, первому нет.
            !incoming && why == DECLINED -> note(words().call.peerDeclined)
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
        callOngoingOff()
        scope.launch {
            engine?.disconnect()
            if (id.isNotEmpty()) calls.end(id)
        }
        Journal.note(LogCode.CALL, "звонок закончен", "звонок" to id.take(8), "длился" to seconds)
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

    /** Закрыть окно 0: звонка больше нет. */
    /**
     * Сказать системе, что идёт звонок, — служба переднего плана (ЗВ14).
     *
     * Зовётся **после входа в комнату**, а не при нажатии: служба нужна ровно тогда,
     * когда пошло медиа. И только оттуда, где человек только что нажал кнопку — с
     * Android 12 из фона её не поднять, и законное окно для этого даст лишь
     * высокоприоритетный push, которого у нас пока нет.
     */
    private fun told() {
        callOngoing(words().call.activeCall, peer.ifBlank { words().chat.nameless })
    }

    /** Тот ли это звонок, который у нас идёт. Чужой конец нашего разговора не касается. */
    fun callIs(id: String): Boolean = id.isNotEmpty() && id == callId

    /** Тот ли это человек, с кем мы говорим. Нужен, чтобы понять, чей уход нас касается. */
    fun peerIs(userId: String): Boolean = userId.isNotEmpty() && userId == peerId

    fun close() {
        watchdog?.cancel()
        callOngoingOff()
        active = false
        callId = ""
        peerId = ""
        peer = ""
        seconds = 0
        state = CallState()
    }

    fun microphone(on: Boolean) {
        scope.launch { engine?.setMicrophone(on) }
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
        if (!on) {
            // Выключил камеру — звонок стал голосовым, и перезвонит окно тоже голосом.
            video = false
            scope.launch { engine?.setCamera(false) }
            return
        }
        askCallAccess(video = true) { allowed ->
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

        // ── СЛУЧИВШИЕСЯ: остаются ──────────────────────────────────────────
        if (!now.remoteVideoShown && was.remoteVideoShown && now.remoteVideoTaken) note(words.peerStoppedVideo)

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
                callOngoingOff()
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
        val name = preset().name
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
            live.reenter(step.door, preset())
        }
    }

    /** Сделать то, что предлагает событие. */
    fun act(action: CallAction) {
        when (action) {
            CallAction.OpenSettings -> openCallSettings()
        }
    }

    /**
     * Спросить разрешение и, если дали, продолжить.
     *
     * **Отказ заканчивает звонок словами, а не тишиной.** Без микрофона звонок не «хуже»,
     * а невозможен: комната соединяется, собеседник виден, звука нет ни в одну сторону — и
     * ищут такую беду где угодно, кроме разрешения.
     */
    private fun withAccess(video: Boolean, then: () -> Unit) {
        askCallAccess(video) { allowed ->
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

        // Ключи длящихся событий. Строками, а не перечнем: их читает только этот файл,
        // и перечень на четыре значения был бы лестницей к одной ступеньке.
        const val PEER_ALONE = "чужое видео при нашей выключенной камере"
        const val PAUSED = "видео погашено полосой"
        const val BACKING = "связь возвращается"
        const val HIDDEN = "чужое видео скрыто нами"
        const val NO_CAMERA = "камера не разрешена"
        const val OFFLINE = "устройство собеседника не на связи"
    }
}

/** Дверь, собранная сигналингом: адрес SFU, комната и токен. */
internal typealias Door = CallDoor
