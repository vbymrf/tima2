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
import io.tima.core.call.openCallSettings
import io.tima.core.diag.Journal
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.core.diag.LogCode
import kotlinx.coroutines.CoroutineScope
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
    private var ticking: Boolean = false

    /** Начат ли звонок как видео: принявший тогда показывает себя сразу (ЗВ9). */
    private var video: Boolean = false

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
        state = CallState(stage = CallStage.Connecting)
        // Разрешение спрашивается ДО похода на сервер: отказавший человек не должен
        // оставить за собой начатый звонок, на который собеседнику покажут вызов.
        withAccess(video) {
            scope.launch {
                when (val step = calls.start(peerId, video)) {
                    is CallStep.Door -> {
                        callId = step.door.callId
                        Journal.note(LogCode.CALL, "звонок начат", "кому" to peerId.take(8), "видео" to video)
                        live.connect(step.door, preset)
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
        if (busy) {
            // Один сеанс за раз. Второй звонок не показываем, но и не прячем молча —
            // в журнал он попадает, иначе «мне звонили, а телефон молчал» не разобрать.
            // Ровно эта запись и назвала беду 2026-09-20, когда условием было `active`.
            Journal.note(LogCode.CALL, "второй входящий во время звонка — не показан", "звонок" to callId.take(8))
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
        state = CallState(stage = CallStage.Connecting, callId = callId)
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
                        live.connect(step.door, preset)
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

    /** Отклонить или положить трубку — для сервера это одно и то же. */
    fun hangUp() {
        val id = callId
        // **Второй раз класть нечего.** В журнале это видно парами: два «звонок закончен»
        // с одним идентификатором и два `POST /end` подряд (отчёт `BKGW` 2026-09-20).
        // Сервер такое переживает, а вот отчёт становится вдвое длиннее и вдвое менее
        // понятным — по нему кажется, что звонков было два.
        if (!active || state.stage == CallStage.Ended) return
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
        close()
        start(id, name, video = false)
    }

    /** Закрыть окно 0: звонка больше нет. */
    fun close() {
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
            scope.launch { engine?.setCamera(false) }
            return
        }
        askCallAccess(video = true) { allowed ->
            if (allowed) {
                scope.launch { engine?.setCamera(true) }
            } else {
                // Звонок продолжается — это не беда звонка, а отказ в камере. Молчать
                // нельзя: нажатая кнопка, после которой ничего не произошло, читается
                // как поломка, и в неё жмут повторно.
                Journal.trouble(LogCode.CALL, "камеру не разрешили", "звонок" to callId.take(8))
                note(words().call.noCamera)
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
        if (!take) note(words().call.remoteHidden)
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
        if (now.remoteVideoShown && !was.remoteVideoShown && !now.cameraOn) note(words.peerShowsSelf)
        if (!now.remoteVideoShown && was.remoteVideoShown && now.remoteVideoTaken) note(words.peerStoppedVideo)
        if (now.videoPaused && !was.videoPaused) note(words.videoPaused)
        if (now.stage == CallStage.Reconnecting && was.stage != CallStage.Reconnecting) note(words.reconnecting)

        // ── ТО ЖЕ САМОЕ В ЖУРНАЛ ────────────────────────────────────────────
        //
        // **Отчёт о проблеме обязан отвечать на вопрос «а что вообще происходило».** До
        // 2026-09-20 журнал знал ровно две вещи: «звонок начат» и «звонок закончен». Между
        // ними могло не быть ни звука, ни соединения, ни картинки — и в отчёте это
        // выглядело одинаково. Немой звонок нашёлся не по журналу, а по расстоянию между
        // строками; второй раз такого везения может не случиться.
        if (now.stage != was.stage) {
            Journal.note(LogCode.CALL, "стадия звонка", "стала" to now.stage.name, "была" to was.stage.name)
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

    /** Дописать событие. Повтор последнего не дописывается: лента не должна заикаться. */
    private fun note(text: String, action: CallAction? = null) {
        if (events.lastOrNull()?.text == text) return
        events.add(CallEvent(seconds = seconds, text = text, action = action))
    }

    /** Сделать то, что предлагает событие. */
    fun act(action: CallAction) {
        when (action) {
            CallAction.OpenSettings -> openCallSettings()
        }
    }

    /**
     * Пресет публикации. Пока один и по умолчанию — H.264 одним слоем, звук с RED.
     *
     * Выбор пресета — испытательный режим за флагом (ADR-0006 Поправка-2), и он придёт
     * сюда же, когда появится его экран (С3, С7).
     */
    private val preset = PublishPreset(name = "умолчание")

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
}

/** Дверь, собранная сигналингом: адрес SFU, комната и токен. */
internal typealias Door = CallDoor
