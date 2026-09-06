package io.tima.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Alarm
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Field
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.Trouble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Откуда человек ушёл в настройки (ПЛАН-ОТЛАДКИ.md, Б2).
 *
 * **Запоминается в момент перехода, а не в момент отправки.** Пока человек пишет текст, он
 * успевает полистать настройки — и «текущее окно» стало бы «Настройки», то есть
 * бесполезным. Половина жалоб «не работает» — про конкретное место, и место это почти
 * всегда то, откуда человек пошёл жаловаться.
 */
data class Origin(val window: Window, val tab: String = "") {
    /** Человеку — строкой; ему видно, что мы поняли, где он был, и объяснять не надо. */
    fun words(): String =
        if (tab.isBlank()) "Вы пришли из окна «${window.short}»"
        else "Вы пришли из окна «${window.short}», вкладка «$tab»"

    /** Отчёту — коротко и без кавычек-ёлочек, чтобы читалось в списке. */
    fun short(): String = if (tab.isBlank()) window.short else "${window.short} · $tab"
}

/**
 * Что уйдёт вместе с отчётом, кроме текста человека и журнала.
 *
 * `userId` и `deviceId` здесь для показа человеку — сервер всё равно берёт их из токена и
 * присланному не верит. Номера телефона нет: он есть у сервера по `userId`, и класть его
 * в хранилище отчётов незачем (решение заказчика 2026-09-06).
 */
data class ProblemFacts(
    val platform: String = "",
    val model: String = "",
    val os: String = "",
    val build: String = "",
    val stream: String = "",
    val nickname: String = "",
    val userId: String = "",
    val deviceId: String = "",
    /** Есть ли у приложения токен доступа прямо сейчас. */
    val signedIn: Boolean = false,
) {
    fun lines(): List<String> = buildList {
        if (build.isNotBlank()) add("Версия: $build" + if (stream.isBlank()) "" else " ($stream)")
        if (model.isNotBlank()) add("Устройство: $model")
        if (os.isNotBlank()) add("Система: $os")
        if (platform.isNotBlank()) add("Платформа: $platform")
        if (nickname.isNotBlank()) add("Ник: $nickname")
        if (userId.isNotBlank()) add("Аккаунт: $userId")
        if (deviceId.isNotBlank()) add("Устройство в системе: $deviceId")
        // Поймано первым живым отчётом (ПЛАН-ОТЛАДКИ.md §6): отчёт лёг на сервер
        // неопознанным, и по нему нельзя было понять — токена не было вовсе или сервер
        // его отверг. Разница решающая: в первом случае чинить приложение, во втором —
        // разбираться с токеном.
        add(if (signedIn) "Вход: есть токен" else "Вход: токена нет")
    }
}

/** О чём жалоба. Четыре ярлыка — решение заказчика 2026-09-06 вместо макетных. */
enum class ProblemKind(val label: String) {
    Messages("Не приходят / не уходят сообщения"),
    Calls("Проблемы с звонком"),
    Looks("Отображение в приложении"),
    Other("Другое"),
}

/** Журнал — узкий порт: оболочка не знает, кто и как его копит. */
fun interface ProblemLog {
    /**
     * Журнал текстом за столько дней, сколько попросили.
     *
     * Глубина приходит от человека — из ответа «когда это началось», — а не задана
     * жёстко: журнал теперь хранится месяц, и отправлять из него всегда сутки значило бы
     * хранить месяц впустую.
     */
    fun dump(days: Int): String
}

/**
 * Снимок состояния устройства — то, что считается **в момент составления отчёта**.
 *
 * **Зачем отдельно от журнала.** Журнал отвечает «как дошли», снимок — «что сейчас». На
 * вопрос «в чём проблема у устройства» отвечает именно он, и читается за три секунды, а
 * не за минуту разбора хронологии.
 *
 * **Почему в момент составления, а не по расписанию.** Человек жалуется тогда, когда у
 * него не работает; это и есть нужный момент. Состояние на границах сеансов при этом
 * пишется и в журнал (`APP-START`, `AUTH-*`) — чтобы отчёт, отправленный через час после
 * поломки, не показывал только то, что уже починилось само.
 */
data class Snapshot(
    /** Вход: живой токен, истёкший, отсутствующий. */
    val auth: String = "",
    /** Сколько лежит неотправленного. */
    val queued: Int = 0,
    /** Разрешения, которые нам нужны: какие есть, каких нет. */
    val permissions: String = "",
    /** Сколько живёт этот запуск — сколько журнала мы вообще застали. */
    val sessionFor: String = "",
) {
    fun lines(): List<String> = buildList {
        if (auth.isNotBlank()) add("вход: $auth")
        add("очередь: " + if (queued == 0) "пусто" else "$queued не отправлено")
        if (permissions.isNotBlank()) add("разрешения: $permissions")
        if (sessionFor.isNotBlank()) add("сеанс: $sessionFor")
    }
}

/** Собранный отчёт. Ровно это уходит на сервер и ровно это показывает «Смотреть». */
data class ProblemReport(
    val kind: ProblemKind,
    /** Что человек ответил на вопрос «когда началось» — уходит первой строкой отчёта. */
    val began: Began = Began.Today,
    val text: String,
    val origin: String,
    val facts: ProblemFacts,
    val snapshot: Snapshot,
    val log: String,
)

/** Чем кончилась отправка. */
sealed interface SendOutcome {
    /** Дошло. Номер человек может назвать в разговоре — по нему мы найдём запись. */
    data class Sent(val number: String) : SendOutcome

    /**
     * Связи нет — отчёт лёг в очередь и уйдёт сам.
     *
     * Это не отказ, а обещание: жалоба на обрыв связи отправляется как раз при обрыве, и
     * терять её нельзя (решение заказчика 2026-09-06).
     */
    data object Queued : SendOutcome

    data class Refused(val why: String) : SendOutcome
}

/**
 * Когда началась поломка — и, значит, сколько журнала прикладывать.
 *
 * **Спрашиваем то, что человек знает.** «Отправить журнал за семь дней» — вопрос к
 * инженеру; «когда это началось» знает любой. Срок мы выводим сами (решение заказчика
 * 2026-09-06).
 *
 * Ответ ценен и сам по себе: до этого в отчёте не было ни слова о том, когда всё
 * началось, а при разборе это первое, что хочется знать.
 */
enum class Began(val label: String, val days: Int) {
    Today("Сегодня", 1),
    Week("На этой неделе", 7),

    /** Всё, что храним. Сколько именно — решает настройка в «Памяти и трафике». */
    Earlier("Раньше", 400),
}

/** Кто отправляет. Реализация живёт в приложении, оболочка про сеть не знает. */
fun interface ProblemSender {
    suspend fun send(report: ProblemReport): SendOutcome
}

/** Что видно на экране «Сообщить о проблеме». */
data class ProblemState(
    val origin: Origin? = null,
    val facts: ProblemFacts = ProblemFacts(),
    val kind: ProblemKind = ProblemKind.Other,
    val text: String = "",
    /** Когда началось — от этого зависит, сколько журнала уйдёт. */
    val began: Began = Began.Today,
    /** Раскрыт ли блок «Что приложится». */
    val showing: Boolean = false,
    /** Журнал, который уйдёт. Берётся при открытии экрана, а не при отправке. */
    val log: String = "",
    /** Состояние устройства на момент составления отчёта. */
    val snapshot: Snapshot = Snapshot(),
    val sending: Boolean = false,
    val outcome: SendOutcome? = null,
) {
    val canSend: Boolean get() = text.isNotBlank() && !sending && !delivered

    /**
     * Отчёт уже принят — сервером или очередью. Повторять нечего.
     *
     * **Держится до повторного входа на экран** (решение заказчика 2026-09-06). Пока
     * состояние сбрасывалось на первом же нажатии клавиши, человек не понимал, ушло ли
     * что-нибудь, и жал ещё раз: 2026-09-06 в базу так легли два одинаковых отчёта с
     * одного телефона — `K2PD` и `NAWR`, оба по 78 697 знаков.
     *
     * Сброс — выход и повторный вход: экран пересоздаёт своё состояние, и это ровно то
     * действие, которым человек говорит «хочу написать ещё раз».
     */
    val delivered: Boolean get() = outcome is SendOutcome.Sent || outcome == SendOutcome.Queued

    /** «Уйдёт журнал за неделю — 412 строк, 78 КБ». Человек видит это до нажатия. */
    fun attachment(): String {
        if (log.isBlank()) return "Журнал пуст: приложению нечего рассказать о себе."
        val lines = log.lineSequence().count()
        val kilobytes = (log.length + 512) / 1024
        val depth = when (began) {
            Began.Today -> "за сутки"
            Began.Week -> "за неделю"
            Began.Earlier -> "за всё, что сохранилось"
        }
        return "Уйдёт журнал " + depth + " — " + lines + " строк, " + kilobytes + " КБ."
    }

    /** Чего не хватает для отправки. `null` — всё на месте. */
    val missing: String?
        get() = when {
            delivered -> null
            text.isBlank() -> "Напишите, что случилось — без этого отчёт не отправить."
            else -> null
        }
}

/**
 * Экран «Сообщить о проблеме» — состояние и решения.
 *
 * Журнал снимается **при открытии экрана**: пока человек пишет текст, приложение живёт и
 * дописывает строки, и к моменту отправки начало поломки могло бы вытесниться. Снятый
 * заранее журнал — это то же самое, что человек увидит по кнопке «Смотреть».
 */
class ProblemStore(
    private val log: ProblemLog,
    private val sender: ProblemSender,
    private val scope: CoroutineScope,
    origin: Origin?,
    facts: ProblemFacts,
    /** Снимок состояния — считается здесь же, при открытии экрана. */
    snapshot: Snapshot = Snapshot(),
) {
    private val _state = MutableStateFlow(
        ProblemState(
            origin = origin,
            facts = facts,
            log = log.dump(Began.Today.days),
            snapshot = snapshot,
        ),
    )
    val state: StateFlow<ProblemState> = _state.asStateFlow()

    fun changedText(line: String) {
        // Исход НЕ стирается: отправленный отчёт остаётся отправленным, что бы человек ни
        // печатал дальше. Стирать его здесь значило бы гасить единственный признак того,
        // что отчёт ушёл, — см. [ProblemState.delivered].
        _state.value = _state.value.copy(text = line)
    }

    fun chose(kind: ProblemKind) {
        _state.value = _state.value.copy(kind = kind)
    }

    /**
     * Ответили, когда началось: журнал берётся заново, на нужную глубину.
     *
     * Пересчёт здесь, а не при отправке: человек видит в «Что приложится» ровно то, что
     * уйдёт, и видит это до нажатия. Показывать одно, а отправлять другое нельзя.
     */
    fun chose(began: Began) {
        _state.value = _state.value.copy(began = began, log = log.dump(began.days))
    }

    /** «Смотреть» — показать целиком то, что уйдёт. */
    fun toggleShowing() {
        _state.value = _state.value.copy(showing = !_state.value.showing)
    }

    fun send() {
        val state = _state.value
        if (!state.canSend) return
        scope.launch {
            _state.value = state.copy(sending = true, outcome = null)
            val report = ProblemReport(
                kind = state.kind,
                began = state.began,
                text = state.text.trim(),
                origin = state.origin?.short().orEmpty(),
                facts = state.facts,
                snapshot = state.snapshot,
                log = state.log,
            )
            val outcome = try {
                sender.send(report)
            } catch (e: Throwable) {
                SendOutcome.Refused("Не удалось отправить — попробуйте ещё раз")
            }
            _state.value = _state.value.copy(sending = false, outcome = outcome)
        }
    }
}

/**
 * Экран «Сообщить о проблеме».
 *
 * **Человек видит то, что отправляет.** Блок «Что приложится» показывает не описание, а
 * сам текст: версию, устройство, ник, журнал целиком. Отчёт, состав которого нельзя
 * посмотреть, — это отправка неизвестно чего, и в мессенджере со сквозным шифрованием
 * такое обещание стоит дороже удобства.
 */
@Composable
fun ProblemScreen(
    state: ProblemState,
    onText: (String) -> Unit,
    onKind: (ProblemKind) -> Unit,
    /** Ответили, когда началось: журнал берётся на другую глубину. */
    onBegan: (Began) -> Unit,
    onShow: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(
    modifier.fillMaxSize().padding(TimaSpacing.about4).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
) {
    state.origin?.let { Secondary(it.words()) }

    // ── Исход, помеха и кнопка — НАВЕРХУ (решение заказчика 2026-09-06) ───────
    //
    // Экран вырос: описание, «когда началось», ярлыки, состав отчёта, — и кнопка уехала
    // за нижний край. Человек, который дописал текст, не видит ни её, ни причины, по
    // которой она не работает: чтобы найти их, надо догадаться прокрутить.
    //
    // Наверху они видны всегда и в одном порядке: что получилось → что мешает → что
    // нажать. Форма ниже — её и так читают сверху вниз.
    state.outcome?.let { Result(it) }

    state.missing?.let { Alarm(it) }

    Button(
        label = when {
            state.delivered -> "Отчёт отправлен"
            state.sending -> "Отправляем…"
            else -> "Отправить"
        },
        onClick = onSend,
        // Красная кнопка «Отчёт отправлен» — не кнопка больше, а отметка о сделанном
        // (решение заказчика 2026-09-06). Она держится до повторного входа на экран.
        kind = if (state.delivered) ButtonKind.Done else ButtonKind.Action,
        enabled = state.canSend,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.delivered) {
        Secondary("Чтобы написать ещё раз, выйдите и снова откройте «Сообщить о проблеме».")
    }

    Caption("Что случилось", fontSize = TimaType.sz4, weight = FontWeight.Bold)
    Field(
        value = state.text,
        onChange = onText,
        hint = "Опишите словами: что делали и что пошло не так",
    )
    if (state.text.isBlank()) {
        Tertiary("Журнал покажет, что происходило, но не то, чего вы ждали, — это можете сказать только вы.")
    }

    Caption("Когда это началось", fontSize = TimaType.sz5, weight = FontWeight.Bold)
    Began.entries.forEach { began ->
        ListLine(
            onClick = { onBegan(began) },
            middle = { Name(if (began == state.began) "● " + began.label else "○ " + began.label) },
        )
    }

    Caption("О чём это", fontSize = TimaType.sz5, weight = FontWeight.Bold)
    ProblemKind.entries.forEach { kind ->
        ListLine(
            onClick = { onKind(kind) },
            middle = { Name(if (kind == state.kind) "● " + kind.label else "○ " + kind.label) },
        )
    }

    Caption("Что приложится", fontSize = TimaType.sz5, weight = FontWeight.Bold)
    Secondary(
        "Переписка и файлы НЕ отправляются. В журнал попадают действия и ошибки — " +
            "что нажимали и что ответил сервер, — а не содержимое сообщений.",
    )
    // Про автоматическую отправку падений человек узнаёт здесь, а не постфактум: решение
    // заказчика 2026-09-06 — отправлять их самим, и молчать об этом было бы нечестно.
    Tertiary("Отчёты о внезапном закрытии приложение отправляет само, тем же составом.")

    // Сколько именно уходит — цифрой, а не на веру. До 2026-09-06 нигде не было сказано
    // даже того, что журнал берётся за сутки.
    Tertiary(state.attachment())

    Button(
        label = if (state.showing) "Скрыть" else "Смотреть",
        onClick = onShow,
        kind = ButtonKind.Quiet,
    )
    if (state.showing) {
        state.facts.lines().forEach { Tertiary(it) }
        // Снимок идёт ПЕРЕД журналом: он отвечает «что сейчас», журнал — «как дошли».
        Caption("Состояние сейчас", fontSize = TimaType.sz5, weight = FontWeight.Bold)
        state.snapshot.lines().forEach { Tertiary(it) }
        Caption("Что происходило", fontSize = TimaType.sz5, weight = FontWeight.Bold)
        Tertiary(if (state.log.isBlank()) "Журнал пуст" else state.log)
    }

}

/** Чем кончилось — своими словами: от исхода зависит, что человеку делать дальше. */
@Composable
private fun Result(outcome: SendOutcome) {
    when (outcome) {
        is SendOutcome.Sent -> {
            Secondary("Отчёт получен, номер:")
            // Крупно, потому что это единственное, что человек отсюда унесёт: номер он
            // диктует в разговоре, и мелким его переписывают с ошибкой (решение
            // заказчика 2026-09-06).
            Caption(outcome.number, fontSize = TimaType.sz1, weight = FontWeight.ExtraBold)
            Secondary("Назовите его, если будете писать нам ещё раз.")
        }

        SendOutcome.Queued -> {
            Caption("Отправим, когда появится связь", fontSize = TimaType.sz4, weight = FontWeight.Bold)
            Secondary(
                "Сети сейчас нет, отчёт сохранён на устройстве и уйдёт сам. Приложение " +
                    "можно закрыть.",
            )
        }

        is SendOutcome.Refused -> Trouble(outcome.why)
    }
}
