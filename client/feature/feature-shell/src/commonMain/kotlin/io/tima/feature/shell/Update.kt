package io.tima.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
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
 * Что сервер предлагает поставить.
 *
 * @param stream какому потоку сборок принадлежит предложение. Пусто — сервер поток не
 *   называет; см. [UpdateStore], почему тогда мы ничего не предлагаем.
 * @param sha256 хэш пакета. Пусто — сервер его не объявляет, и на ПК ставить будет
 *   нечего проверять: подписи кода у пакета нет (решение заказчика 2026-09-06).
 * @param size размер пакета в байтах, 0 — неизвестен. Нужен человеку до нажатия: сотня
 *   мегабайт по мобильной сети — это его решение, а не наше.
 */
data class UpdateOffer(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String,
    val stream: String = "",
    val sha256: String = "",
    val size: Long = 0,
    /**
     * Ниже этой версии работать нельзя (уровень 2, Plan.md §3.5). 0 — порога нет.
     *
     * **Ноль и «поле не пришло» — одно и то же, и это правило, а не умолчание.** Сервер,
     * откатившийся на прежнюю версию, перестаёт присылать поле; прочитай мы это как
     * «блокировать», откат выключил бы все установленные приложения разом.
     */
    val minClient: Int = 0,
)

/**
 * Откуда берётся предложение. Узкий порт, объявленный **потребителем**.
 *
 * Оболочка не знает ни про Ktor, ни про адрес сервера: у неё нет зависимости на
 * core-network и не будет. Реализацию подставляет приложение.
 */
fun interface AppVersionPort {
    /** `null` — обновления на сервере не настроены (204). Ошибка — исключение. */
    suspend fun latest(): UpdateOffer?
}

/**
 * Кто ставит скачанное. Тоже порт потребителя: качать и запускать установщик умеет
 * только платформа, и оболочке нельзя про неё знать.
 *
 * `null` вместо реализации — «эта платформа ставить не умеет»: так на iOS, где
 * обновление приходит из App Store, и так же на любой сборке, где установщик ещё не
 * заведён. Тогда кнопки установки нет вовсе — вместо кнопки, которая делает вид.
 */
fun interface UpdateInstaller {
    /**
     * @param onProgress проценты скачивания, 0…100. Свой прогресс обязателен: ждать
     *   вслепую человек не станет, а системное уведомление о загрузке — чужой путь
     *   установки (инвентарь поведения, пункт 12).
     */
    suspend fun install(offer: UpdateOffer, onProgress: (Int) -> Unit): InstallOutcome
}

/** Чем кончилась установка. Каждый исход человеку означает разное — потому и разные. */
sealed interface InstallOutcome {
    /** Установщик запущен; приложение сейчас закроется. */
    data object Started : InstallOutcome

    /** Не докачали: связь оборвалась или сервер не отдал файл. */
    data object NoConnection : InstallOutcome

    /**
     * Скачанное не сошлось с объявленным хэшем.
     *
     * Означает либо битую загрузку, либо подмену пакета. Различить их нечем, и потому
     * говорится главное: **ставить это нельзя**.
     */
    data object BadPackage : InstallOutcome

    /**
     * Сервер не объявил хэш пакета.
     *
     * На ПК это отказ: проверять скачанное больше нечем — подписи кода у пакета нет.
     * Поставить непроверенное молча хуже, чем не поставить и сказать.
     */
    data object NoHash : InstallOutcome

    /** Установщик отказался или человек его отменил. */
    data class Refused(val why: String) : InstallOutcome
}

/** Что видно на вкладке «Обновление». */
data class UpdateState(
    /** Версия, которая сейчас работает. */
    val installed: String = "",
    val installedCode: Int = 0,
    /** Поток этой сборки: `v1`, `v2`. Сравнивать номера можно только внутри потока. */
    val stream: String = "",
    val offer: UpdateOffer? = null,
    val expect: Boolean = false,
    val trouble: String? = null,
    /** Проверка прошла и вернула 204: обновления просто не настроены. */
    val notConfigured: Boolean = false,
    /** Человек нажал «Обновить», и ему показан вопрос перед установкой. */
    val asking: Boolean = false,
    /** Идёт скачивание. */
    val installing: Boolean = false,
    /** Сколько скачано, 0…100. */
    val percent: Int = 0,
    /** Чем кончилось. `null` — ещё не начинали. */
    val outcome: InstallOutcome? = null,
) {
    /**
     * Есть ли что ставить.
     *
     * **Номера версий сравнимы только внутри одного потока сборок.** У v1 сейчас
     * `version_code` 24, у v2 — 2, и наивное «24 больше 2» предложило бы человеку с v2
     * поставить поверх неё прошлогоднюю v1. Это не гипотеза: именно такие числа отдаёт
     * стенд на 2026-08-26.
     *
     * Поэтому правило обратное обычному: **молчим, пока не доказано, что поток наш**.
     * Сервер, не называющий поток, — старый сервер, и его предложение к нам не относится.
     */
    val updateAvailable: Boolean
        get() {
            val offer = offer ?: return false
            if (stream.isBlank() || offer.stream.isBlank()) return false
            if (offer.stream != stream) return false
            return offer.versionCode > installedCode
        }

    /** Сервер что-то предлагает, но не нам. Человеку это надо сказать, а не спрятать. */
    val alienStream: Boolean
        get() = offer != null && !updateAvailable &&
            (offer.stream.isBlank() || offer.stream != stream)

    /**
     * Работать этой сборкой больше нельзя (уровень 2).
     *
     * Условий три, и каждое обязательно: порог объявлен, поток наш, наш номер ниже.
     * **Поток здесь важен ровно так же, как в предложении обновиться**: `min_client`
     * чужого ряда сборок к нам не относится, и принять его значило бы выключить
     * приложение по числу из соседней вселенной.
     */
    val mustUpdate: Boolean
        get() {
            val offer = offer ?: return false
            if (offer.minClient <= 0) return false
            if (stream.isBlank() || offer.stream.isBlank() || offer.stream != stream) return false
            return installedCode < offer.minClient
        }
}

/**
 * Проверка обновлений и их установка.
 *
 * Спрашивает по требованию, а не в цикле: раздел открывают, когда о нём подумали, и
 * фоновый опрос здесь ничего не ускоряет, зато будит сеть.
 *
 * **Установка не начинается по одному нажатию.** Между «Обновить» и загрузкой стоит
 * вопрос (решение заказчика 2026-09-06): на ПК установка закрывает приложение, и человек
 * должен узнать об этом до, а не в тот момент, когда окно исчезло посреди разговора.
 */
class UpdateStore(
    private val versions: AppVersionPort,
    private val scope: CoroutineScope,
    installed: String,
    installedCode: Int,
    stream: String,
    /** `null` — платформа ставить не умеет; тогда кнопки установки нет. */
    private val installer: UpdateInstaller? = null,
    /**
     * Что сделать, когда установщик запущен. На ПК — закрыть приложение: MSI не станет
     * заменять файлы работающей программы, а Windows предложит перезагрузку.
     */
    private val onLeaving: () -> Unit = {},
) {
    private val _state = MutableStateFlow(
        UpdateState(installed = installed, installedCode = installedCode, stream = stream),
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Умеет ли эта сборка ставить обновление сама. */
    val canInstall: Boolean get() = installer != null

    init {
        check()
    }

    fun check() {
        _state.value = _state.value.copy(
            expect = true,
            trouble = null,
            notConfigured = false,
            outcome = null,
        )
        scope.launch {
            _state.value = try {
                val offer = versions.latest()
                _state.value.copy(offer = offer, expect = false, notConfigured = offer == null)
            } catch (e: Throwable) {
                // Сообщение исключения человеку не показываем: там адрес сервера и класс
                // ошибки Ktor. Ему нужно одно — что делать дальше.
                _state.value.copy(expect = false, trouble = "Не удалось спросить сервер — проверьте связь")
            }
        }
    }

    /** Нажали «Обновить»: показываем, чем это кончится, и ждём подтверждения. */
    fun ask() {
        if (_state.value.installing) return
        _state.value = _state.value.copy(asking = true, outcome = null)
    }

    /** Передумали. Ничего не скачано и ничего не изменилось. */
    fun dismiss() {
        _state.value = _state.value.copy(asking = false)
    }

    /** Подтвердили: качаем, проверяем, запускаем установщик. */
    fun install() {
        val offer = _state.value.offer ?: return
        val installer = installer ?: return
        if (_state.value.installing) return
        scope.launch {
            _state.value = _state.value.copy(asking = false, installing = true, percent = 0, outcome = null)
            val outcome = try {
                installer.install(offer) { percent ->
                    _state.value = _state.value.copy(percent = percent)
                }
            } catch (e: Throwable) {
                InstallOutcome.Refused("установщик не запустился")
            }
            _state.value = _state.value.copy(
                installing = outcome is InstallOutcome.Started,
                outcome = outcome,
            )
            // Приложение закрывается ПОСЛЕ того, как исход записан в состояние: иначе на
            // платформе, которая закрытие игнорирует, экран остался бы в «скачиваем».
            if (outcome is InstallOutcome.Started) onLeaving()
        }
    }
}

/**
 * Вкладка «Обновление».
 *
 * Чистый рендер [UpdateState]; решения — в [UpdateStore].
 */
@Composable
fun UpdateSection(
    state: UpdateState,
    onCheck: () -> Unit,
    /** Нажали «Обновить»: дальше вопрос, а не загрузка. */
    onInstall: () -> Unit,
    /** Подтвердили установку. */
    onConfirm: () -> Unit = {},
    /** Отказались от установки. */
    onDismiss: () -> Unit = {},
    /** Умеет ли эта сборка ставить обновление сама. */
    canInstall: Boolean = false,
    modifier: Modifier = Modifier,
) = Column(
    modifier.fillMaxSize().padding(TimaSpacing.about4),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
) {
    ListLine(
        middle = { Name("Установлена " + state.installed.ifBlank { "—" }) },
        right = { Secondary(state.stream.ifBlank { "поток не объявлен" }) },
    )

    when {
        state.installing -> {
            Caption("Скачиваем " + state.percent + "%", fontSize = TimaType.sz3, weight = FontWeight.Bold)
            Secondary("Не закрывайте приложение — оно закроется само, когда начнётся установка.")
        }

        state.asking -> Asking(state, onConfirm, onDismiss)

        state.outcome != null && state.outcome !is InstallOutcome.Started ->
            Failed(state.outcome, onInstall)

        state.expect -> Secondary("Спрашиваем сервер…")

        state.trouble != null -> Trouble(state.trouble)

        state.notConfigured -> Secondary("Сервер обновлений не раздаёт")

        state.updateAvailable -> {
            val offer = requireNotNull(state.offer)
            Caption(
                "Доступна " + offer.versionName,
                fontSize = TimaType.sz3,
                weight = FontWeight.ExtraBold,
            )
            if (offer.notes.isNotBlank()) Secondary(offer.notes)
            if (offer.size > 0) Tertiary("Скачать " + megabytes(offer.size) + " МБ")
            if (canInstall) {
                Button(label = "Обновить", onClick = onInstall)
            } else {
                // Кнопки нет, а не «есть и молчит»: сборка без установщика ставить не
                // умеет, и делать вид — худшее из двух.
                Tertiary("Эта сборка обновляется не сама: поставьте новую версию обычным способом.")
            }
        }

        state.alienStream -> {
            val offer = requireNotNull(state.offer)
            // Прямо и словами. Спрятать предложение значило бы, что человек, знающий про
            // «версию 0.6.5 на сайте», решит, будто приложение сломано.
            Secondary("Сервер предлагает " + offer.versionName + " — это другая сборка, не для этой версии")
        }

        else -> Secondary("Установлена последняя версия")
    }

    Button(label = "Проверить ещё раз", onClick = onCheck, kind = ButtonKind.Quiet)
}

/**
 * Работать нельзя, пока не обновишься (уровень 2, [UpdateState.mustUpdate]).
 *
 * Отдельный экран поверх всего, а не строка в настройках: сервер объявил, что эта сборка
 * больше не поддерживается, и делать вид, что приложение работает, значит обещать
 * доставку сообщений, которой не будет.
 *
 * **Кнопка обновления здесь есть, а обходного пути нет.** «Продолжить всё равно» было бы
 * ложью: продолжать нечего.
 */
@Composable
fun UpdateGate(
    state: UpdateState,
    onInstall: () -> Unit,
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit = {},
    canInstall: Boolean = false,
    modifier: Modifier = Modifier,
) = Column(
    modifier.fillMaxSize().padding(TimaSpacing.about5),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
) {
    Caption("Нужно обновиться", fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(
        "Сервер больше не работает с этой версией приложения. Переписка и аккаунт на " +
            "месте — их ничто не трогает, — но отправлять и получать до обновления не выйдет.",
    )
    Tertiary("Установлена " + state.installed.ifBlank { "—" })

    when {
        state.installing -> {
            Caption("Скачиваем " + state.percent + "%", fontSize = TimaType.sz3, weight = FontWeight.Bold)
            Secondary("Не закрывайте приложение — оно закроется само, когда начнётся установка.")
        }

        state.asking -> Asking(state, onConfirm, onDismiss)

        state.outcome != null && state.outcome !is InstallOutcome.Started ->
            Failed(state.outcome, onInstall)

        canInstall -> Button(label = "Обновить", onClick = onInstall)

        else -> Secondary(
            "Эта сборка обновляется не сама: поставьте новую версию обычным способом — " +
                "тем же, каким ставили эту.",
        )
    }
}

/**
 * Вопрос перед установкой.
 *
 * Решение заказчика 2026-09-06: спросить, показав, что теряется, а что нет. Главное здесь
 * — вторая строка: человек, у которого закрылось приложение, первым делом думает про
 * переписку, и ответ он обязан получить до нажатия, а не после.
 */
@Composable
private fun Asking(state: UpdateState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val offer = state.offer
    Caption("Поставить " + (offer?.versionName ?: ""), fontSize = TimaType.sz3, weight = FontWeight.ExtraBold)
    Secondary("Приложение закроется, и запустится установщик. Это займёт минуту.")
    Secondary(
        "Переписка, аккаунт и настройки останутся: они лежат отдельно от программы, и " +
            "установщик их не трогает. Неотправленное дойдёт после запуска новой версии.",
    )
    Button(label = "Поставить", onClick = onConfirm)
    Button(label = "Не сейчас", onClick = onDismiss, kind = ButtonKind.Quiet)
}

/** Не получилось. Причина названа своими словами: от неё зависит, что делать дальше. */
@Composable
private fun Failed(outcome: InstallOutcome, onRetry: () -> Unit) {
    val (что, что_дальше) = when (outcome) {
        InstallOutcome.NoConnection ->
            "Обновление не скачалось" to "Связь оборвалась. Попробуйте ещё раз."
        InstallOutcome.BadPackage ->
            "Скачанное не совпало с тем, что объявил сервер" to
                "Ставить это нельзя: файл либо не докачался, либо подменён. Попробуйте ещё раз."
        InstallOutcome.NoHash ->
            "Сервер не объявил, что именно он раздаёт" to
                "Без этого проверить скачанное нечем, и мы не ставим. Это чинится на сервере."
        is InstallOutcome.Refused -> "Установка не началась" to outcome.why
        InstallOutcome.Started -> "" to ""
    }
    Trouble(что)
    Secondary(что_дальше)
    Button(label = "Попробовать ещё раз", onClick = onRetry)
}

/** Мегабайты с одним знаком: точные байты человеку не говорят ничего. */
private fun megabytes(bytes: Long): String {
    val tenths = (bytes * 10 + 524_288) / 1_048_576
    return (tenths / 10).toString() + "," + (tenths % 10)
}
