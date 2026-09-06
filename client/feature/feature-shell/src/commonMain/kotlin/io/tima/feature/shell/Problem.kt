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
) {
    fun lines(): List<String> = buildList {
        if (build.isNotBlank()) add("Версия: $build" + if (stream.isBlank()) "" else " ($stream)")
        if (model.isNotBlank()) add("Устройство: $model")
        if (os.isNotBlank()) add("Система: $os")
        if (platform.isNotBlank()) add("Платформа: $platform")
        if (nickname.isNotBlank()) add("Ник: $nickname")
        if (userId.isNotBlank()) add("Аккаунт: $userId")
        if (deviceId.isNotBlank()) add("Устройство в системе: $deviceId")
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
    /** Всё, что уложилось в границы журнала, текстом. */
    fun dump(): String
}

/** Собранный отчёт. Ровно это уходит на сервер и ровно это показывает «Смотреть». */
data class ProblemReport(
    val kind: ProblemKind,
    val text: String,
    val origin: String,
    val facts: ProblemFacts,
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
    /** Раскрыт ли блок «Что приложится». */
    val showing: Boolean = false,
    /** Журнал, который уйдёт. Берётся при открытии экрана, а не при отправке. */
    val log: String = "",
    val sending: Boolean = false,
    val outcome: SendOutcome? = null,
) {
    val canSend: Boolean get() = text.isNotBlank() && !sending
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
) {
    private val _state = MutableStateFlow(
        ProblemState(origin = origin, facts = facts, log = log.dump()),
    )
    val state: StateFlow<ProblemState> = _state.asStateFlow()

    fun changedText(line: String) {
        _state.value = _state.value.copy(text = line, outcome = null)
    }

    fun chose(kind: ProblemKind) {
        _state.value = _state.value.copy(kind = kind)
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
                text = state.text.trim(),
                origin = state.origin?.short().orEmpty(),
                facts = state.facts,
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
    onShow: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(
    modifier.fillMaxSize().padding(TimaSpacing.about4).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
) {
    state.origin?.let { Secondary(it.words()) }

    Caption("Что случилось", fontSize = TimaType.sz4, weight = FontWeight.Bold)
    Field(
        value = state.text,
        onChange = onText,
        hint = "Опишите словами: что делали и что пошло не так",
    )

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

    Button(
        label = if (state.showing) "Скрыть" else "Смотреть",
        onClick = onShow,
        kind = ButtonKind.Quiet,
    )
    if (state.showing) {
        state.facts.lines().forEach { Tertiary(it) }
        Tertiary(if (state.log.isBlank()) "Журнал пуст" else state.log)
    }

    state.outcome?.let { Result(it) }

    Button(
        label = if (state.sending) "Отправляем…" else "Отправить",
        onClick = onSend,
        kind = if (state.canSend) ButtonKind.Action else ButtonKind.Quiet,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.text.isBlank()) {
        Tertiary("Без описания отчёт бесполезен: журнал покажет, что происходило, но не то, чего вы ждали.")
    }
}

/** Чем кончилось — своими словами: от исхода зависит, что человеку делать дальше. */
@Composable
private fun Result(outcome: SendOutcome) {
    when (outcome) {
        is SendOutcome.Sent -> {
            Caption("Отправлено", fontSize = TimaType.sz4, weight = FontWeight.Bold)
            Secondary("Отчёт № " + outcome.number + " — назовите его, если будете писать нам ещё раз.")
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
