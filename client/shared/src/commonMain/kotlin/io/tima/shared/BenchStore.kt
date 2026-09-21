package io.tima.shared

import io.tima.core.call.BenchSample
import io.tima.core.call.BenchSummary
import io.tima.core.call.CallEngine
import io.tima.core.call.CallStage
import io.tima.core.call.benchFileName
import io.tima.core.call.benchReport
import io.tima.core.call.phoneModel
import io.tima.core.call.saveBenchReport
import io.tima.core.call.PublishPreset
import io.tima.core.call.phoneLoad
import io.tima.core.call.phoneTraffic
import io.tima.core.call.presetFromWire
import io.tima.core.call.presetsFromWire
import io.tima.core.call.summarize
import io.tima.core.call.toWire
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.domain.chat.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Испытательный стенд звонков — [ПЛАН-СТЕНДА-ЗВОНКОВ](../../../../../../../doc_mig/ПЛАН-СТЕНДА-ЗВОНКОВ.md),
 * задачи С3, С4, С5, С7.
 *
 * ── ГЛАВНОЕ СВОЙСТВО, КОТОРОЕ ЛЕГКО ПОТЕРЯТЬ ───────────────────────────────
 *
 * **Выключение флага НЕ сбрасывает выбранный пресет.** Заказчик 2026-09-19 сказал прямо:
 * «тогда выбранный режим будет работать для приложения как должно быть без всего этого».
 * То есть выбранный набор становится обычным поведением приложения, а уходит только
 * испытательная обвязка — экран, замеры, ленты.
 *
 * Отсюда и устройство хранения: пресет и флаг лежат **разными ключами**. Сложенные в один,
 * они потеряли бы выбор при первом же выключении, и объяснить человеку, куда он делся,
 * было бы нечем.
 *
 * ── И ВТОРОЕ: ЗАМЕР НЕ ДОЛЖЕН СТОИТЬ НИЧЕГО ПРИ ВЫКЛЮЧЕННОМ ФЛАГЕ ──────────
 *
 * Снятие процессора и нагрева в цикле — само по себе нагрузка, и «замер, который мешает
 * замеряемому» — классика. Поэтому [start] при выключенном флаге не делает ничего: сбор
 * заводится по флагу, а не фильтруется на выходе.
 */
class BenchStore(
    private val settings: Settings,
    /** Движок звонка. `null` — платформа звонить не умеет, и стенда на ней нет. */
    private val engine: CallEngine?,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(BenchState())
    val state: StateFlow<BenchState> = _state.asStateFlow()

    private var sampling: Job? = null

    init {
        settings.all()
            .onEach { all ->
                _state.value = _state.value.copy(
                    on = all[KEY_FLAG] == YES,
                    // Пресет читается отдельным ключом от флага — см. заголовок.
                    preset = all[KEY_PRESET]?.let { presetFromWire(it) } ?: DEFAULT,
                    presets = all[KEY_PRESETS]?.let { presetsFromWire(it) }.orEmpty(),
                    skip = all[KEY_SKIP]?.toIntOrNull()?.coerceIn(0, 60) ?: SKIP_DEFAULT,
                )
            }
            .launchIn(scope)

        // ── ПРОГОН ИДЁТ САМ, ПОКА ИДЁТ РАЗГОВОР ─────────────────────────────
        //
        // Решение заказчика 2026-09-21: «каждый отдельный звонок — отдельный файл».
        // Прогон, который надо не забыть начать, однажды забудут начать — и звонок, ради
        // которого всё делалось, останется без чисел. Поэтому он начинается с ответом и
        // кончается с трубкой, а кнопки остаются для тех, кто ведёт замер осознанно.
        engine?.state
            ?.onEach { live ->
                when (live.stage) {
                    CallStage.Connected -> if (_state.value.on && !_state.value.running) begin()
                    CallStage.Ended, CallStage.Idle -> if (_state.value.running) stop()
                    else -> Unit
                }
            }
            ?.launchIn(scope)
    }

    /** Включить или выключить испытательный режим. Пресет при этом остаётся. */
    fun flag(on: Boolean) {
        if (!on) stop()
        scope.launch { settings.put(KEY_FLAG, if (on) YES else NO) }
        Journal.note(LogCode.CALL, "испытательный режим звонков", "включён" to on)
    }

    /**
     * Сделать пресет текущим — им будут идти следующие звонки.
     *
     * **Следующие, а не идущий.** Кодек и слои участвуют в согласовании, и менять их
     * посреди звонка означает пересогласование, а иногда разрыв (С-В5 в плане). Поэтому
     * пресет применяется при входе в комнату, и сравнивать прогоны приходится
     * перезапуском звонка — цена, названная в плане и принятая.
     */
    fun choose(preset: PublishPreset) {
        scope.launch { settings.put(KEY_PRESET, preset.toWire()) }
    }

    /**
     * Запомнить набор под именем.
     *
     * Одноимённый заменяется: имя и есть то, чем прогоны различают, и два разных набора
     * под одним именем сделали бы отчёт неразличимым.
     */
    fun save(preset: PublishPreset) {
        val kept = _state.value.presets.filterNot { it.name == preset.name } + preset
        scope.launch {
            settings.put(KEY_PRESETS, kept.toWire())
            settings.put(KEY_PRESET, preset.toWire())
        }
    }

    fun forget(name: String) {
        val kept = _state.value.presets.filterNot { it.name == name }
        scope.launch { settings.put(KEY_PRESETS, kept.toWire()) }
    }

    /**
     * Сколько первых секунд разговора **не учитывать**.
     *
     * Ради разгона полосы: в первые секунды она растёт, и включённые в среднее они портят
     * его тем сильнее, чем короче прогон (ПЛАН-СТЕНДА §6). Раньше это делала кнопка
     * «Начать заново» — её убрали решением заказчика 2026-09-21: настройка делает то же
     * самое и не требует помнить про неё в каждом звонке.
     */
    fun skipSeconds(seconds: Int) {
        val clean = seconds.coerceIn(0, 60)
        scope.launch { settings.put(KEY_SKIP, clean.toString()) }
    }

    /**
     * Начать прогон.
     *
     * Зовётся сам, когда разговор соединился; руками его не начинают — см. слежку за
     * состоянием движка выше.
     */
    private fun begin() {
        if (!_state.value.on) return // флаг выключен — замер не стоит ничего
        if (sampling?.isActive == true) return
        val preset = _state.value.preset
        _state.value = _state.value.copy(running = true, samples = emptyList(), last = null)
        Journal.note(LogCode.CALL, "прогон стенда начат", "пресет" to preset.name)
        sampling = scope.launch {
            var second = 0
            while (isActive) {
                delay(SAMPLE_EVERY_MS)
                second += (SAMPLE_EVERY_MS / 1000).toInt()
                // Звонок кончился — прогон кончился вместе с ним. Числа после разрыва
                // относятся уже не к звонку, а к приложению вообще.
                val stage = engine?.state?.value?.stage
                if (stage == CallStage.Ended || stage == CallStage.Idle) {
                    stop()
                    return@launch
                }
                // Числа спрашиваем и в разгоне: счётчики битрейта и процессора считаются
                // разницей, и пропущенный опрос сделал бы первый учтённый отсчёт втрое
                // больше настоящего.
                val sample = BenchSample(
                    atSecond = second,
                    stats = engine?.stats(),
                    load = phoneLoad(),
                    traffic = phoneTraffic(),
                )
                if (second > _state.value.skip) {
                    _state.value = _state.value.copy(samples = _state.value.samples + sample)
                }
            }
        }
    }

    /**
     * Остановить прогон, свернуть его и **положить файл на телефон**.
     *
     * Зовётся и сам — когда кончился звонок, — и руками: кнопкой «Остановить» или сменой
     * набора на ходу. Смена набора обязана оборвать прогон: иначе в отчёт попало бы одно
     * число про минуту, внутри которой было два разных кодека, и сравнивать его было бы
     * не с чем.
     *
     * Остановленный дважды — не беда, а ничего.
     */
    fun stop() {
        sampling?.cancel()
        sampling = null
        val now = _state.value
        if (!now.running) return
        val summary = summarize(now.preset, now.samples)
        // Файл пишется ДО обновления состояния: путь к нему показывает экран, и появиться
        // он должен вместе со свёрткой, а не через мгновение после неё.
        val saved = saveBenchReport(
            fileName = benchFileName(phoneModel()),
            text = benchReport(summary, now.samples, now.preset, phoneModel()),
        )
        _state.value = now.copy(
            running = false,
            last = summary,
            lastFile = saved,
            // Новые прогоны сверху: смотрят последний, а не первый.
            runs = listOf(summary) + now.runs,
        )
        Journal.note(
            LogCode.CALL,
            "прогон стенда окончен",
            "пресет" to summary.preset,
            "секунд" to summary.seconds,
            "отсчётов" to summary.samples,
            "кодер" to (summary.codec ?: "—"),
            "файл" to (saved ?: "не записан"),
        )
        next()
    }

    /**
     * Перейти к следующему набору — **кольцом, по концу звонка**.
     *
     * ── ПОЧЕМУ ТАК, А НЕ КОМАНДОЙ ВТОРОМУ ТЕЛЕФОНУ ──────────────────────────
     *
     * Решение заказчика 2026-09-21. Сперва предполагался канал данных: ведущий телефон
     * говорит ведомому, когда переключаться. Заказчик отверг это как менее надёжное — и
     * был прав: канал данных живёт в комнате, комната пересоздаётся на каждом переходе,
     * и сговор двух телефонов посреди разговора добавляет третью неизвестную к двум
     * имеющимся.
     *
     * Здесь сговора нет вовсе. **Оба телефона просто считают звонки**: кончился звонок —
     * сдвинулись на следующий набор. Списки одинаковы — значит идут в ногу, и никто
     * никому ничего не сообщает. Разошлись — это видно на экране, потому что номер
     * набора показан и в окне звонка, и в окне стенда.
     *
     * Кольцом: после последнего снова первый. Забег можно крутить сколько угодно, не
     * трогая настройки между кругами.
     */
    private fun next() {
        val all = _state.value.presets
        if (all.size < 2) return // крутить нечего
        val at = all.indexOfFirst { it.name == _state.value.preset.name }
        val following = all[(at + 1 + all.size) % all.size]
        Journal.note(
            LogCode.CALL,
            "следующий набор забега",
            "стал" to following.name,
            "номер" to (all.indexOf(following) + 1),
            "всего" to all.size,
        )
        choose(following)
    }

    companion object {
        /** Умолчание — тот же набор, что и у звонка без стенда. */
        val DEFAULT = PublishPreset(name = "умолчание")

        /**
         * Как часто снимаем отсчёт.
         *
         * Секунда — не «почаще, чтобы точнее»: процессор считается разницей, и на более
         * коротком промежутке число начинает дрожать от одного планировщика. А реже —
         * теряется провал, ради которого отсчёты и хранятся по одному.
         */
        const val SAMPLE_EVERY_MS = 1_000L

        // Ключи раздельные, и это то самое решение из заголовка.
        const val KEY_FLAG = "call.bench.on"
        const val KEY_PRESET = "call.bench.preset"
        const val KEY_PRESETS = "call.bench.presets"
        const val KEY_SKIP = "call.bench.skip"

        /**
         * Сколько секунд разгона не учитывать по умолчанию.
         *
         * Пять — столько занимает подъём полосы у WebRTC на приличной связи. Не
         * измерено, а выбрано; мерить его — работа того же стенда.
         */
        const val SKIP_DEFAULT = 5

        private const val YES = "yes"
        private const val NO = "no"
    }
}

/**
 * Что сейчас со стендом.
 *
 * @param preset чем идут звонки **сейчас** — и при выключенном флаге тоже.
 * @param presets сохранённые наборы.
 * @param samples отсчёты идущего прогона. Целиком, а не средним: среднее прячет провал.
 * @param last свёртка последнего законченного прогона.
 * @param runs все свёртки за запуск приложения, новые сверху.
 * @param lastFile куда лёг отчёт последнего прогона. `null` — записать не удалось или
 *   платформа этого не умеет; экран тогда говорит об этом, а не молчит.
 * @param skip сколько первых секунд разговора не учитывать.
 */
data class BenchState(
    val on: Boolean = false,
    val preset: PublishPreset = BenchStore.DEFAULT,
    val presets: List<PublishPreset> = emptyList(),
    val running: Boolean = false,
    val samples: List<BenchSample> = emptyList(),
    val last: BenchSummary? = null,
    val runs: List<BenchSummary> = emptyList(),
    val lastFile: String? = null,
    val skip: Int = BenchStore.SKIP_DEFAULT,
) {
    /** Номер текущего набора в забеге, с единицы. `0` — набора нет в списке. */
    val at: Int get() = presets.indexOfFirst { it.name == preset.name } + 1

    /** Сколько наборов в забеге. */
    val total: Int get() = presets.size
}
