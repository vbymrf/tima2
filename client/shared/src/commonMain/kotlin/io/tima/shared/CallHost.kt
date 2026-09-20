package io.tima.shared

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.tima.core.call.CallDoor
import io.tima.core.call.CallEngine
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.CallStep
import io.tima.core.call.Calls
import io.tima.core.call.PublishPreset
import io.tima.core.call.askCallAccess
import io.tima.core.diag.Journal
import io.tima.core.words.CurrentWords
import io.tima.core.words.Words
import io.tima.core.diag.LogCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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

    private var peerId: String = ""
    private var callId: String = ""
    private var ticking: Boolean = false

    /** Умеет ли эта платформа звонить вообще. `false` — кнопок «позвонить» нет. */
    val possible: Boolean get() = engine != null

    init {
        engine?.let { live ->
            scope.launch {
                live.state.collectLatest { fresh ->
                    state = fresh
                    if (fresh.stage == CallStage.Connected) startTicking()
                    if (fresh.stage == CallStage.Ended) stopTicking()
                }
            }
        }
    }

    /** Позвонить. Дверь открывает сервер; движок в неё входит. */
    fun start(peerId: String, peerName: String, video: Boolean) {
        val live = engine ?: return
        if (active) {
            // **Один сеанс за раз — и это правило про исходящие тоже.** 2026-09-20 окно
            // звонка не показывалось (подокно переписки его закрывало), человек жал
            // «позвонить» ещё и ещё — и сервер завёл три звонка подряд одному и тому же
            // собеседнику, а тому пришло три вызова. Ту беду починили в другом месте, но
            // запрет нужен сам по себе: кнопка, начинающая второй звонок поверх первого,
            // не имеет смысла ни при какой причине нажатия.
            Journal.note(LogCode.CALL, "звонок уже идёт — второй не начат", "кому" to peerId.take(8))
            return
        }
        this.peerId = peerId
        peer = peerName
        incoming = false
        active = true
        seconds = 0
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
        if (active) {
            // Один сеанс за раз. Второй звонок не показываем, но и не прячем молча —
            // в журнал он попадает, иначе «мне звонили, а телефон молчал» не разобрать.
            Journal.note(LogCode.CALL, "второй входящий во время звонка — не показан", "звонок" to callId.take(8))
            return
        }
        this.callId = callId
        peerId = fromId
        peer = fromName
        incoming = true
        active = true
        seconds = 0
        state = CallState(stage = CallStage.Connecting, callId = callId)
        Journal.note(LogCode.CALL, "входящий звонок", "от" to fromId.take(8), "видео" to video)
    }

    /** Принять входящий: сервер выдаёт токен той же комнаты. */
    fun accept() {
        val live = engine ?: return
        // Тот же вопрос, что и при исходящем, и по той же причине: без микрофона комната
        // соединится, а звука не будет ни в одну сторону.
        withAccess(video = false) {
            scope.launch {
                when (val step = calls.answer(callId)) {
                    is CallStep.Door -> live.connect(step.door, preset)
                    else -> refuse(step)
                }
            }
        }
    }

    /** Отклонить или положить трубку — для сервера это одно и то же. */
    fun hangUp() {
        val id = callId
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
                state = state.copy(notice = null)
                scope.launch { engine?.setCamera(true) }
            } else {
                // Звонок продолжается — это не беда звонка, а отказ в камере. Молчать
                // нельзя: нажатая кнопка, после которой ничего не произошло, читается
                // как поломка, и в неё жмут повторно.
                Journal.trouble(LogCode.CALL, "камеру не разрешили", "звонок" to callId.take(8))
                state = state.copy(notice = words().call.noCamera)
            }
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
                state = state.copy(
                    stage = CallStage.Ended,
                    trouble = words().call.noMicrophone,
                )
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
