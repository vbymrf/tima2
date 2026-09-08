package io.tima.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.CurrentWords
import io.tima.core.ui.Alarm
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.RussianWords
import io.tima.core.ui.Tima
import io.tima.core.ui.UpdateWords
import io.tima.core.ui.words
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
    /**
     * Важное ли обновление (уровень 1, решение заказчика 2026-09-06).
     *
     * **Не то же самое, что [minClient].** Порог — «работать нельзя», и его окно не
     * закрывается. Важность — «старая версия может вести себя неправильно»: окно при
     * каждом запуске, но с кнопкой «Позже». Разница в том, кто решает: порог решает
     * сервер за человека, важность — человек, которого предупредили.
     */
    val important: Boolean = false,
)

/**
 * Где платформа помнит, что установка была начата.
 *
 * Две лямбды, как у хранилища отчётов, и по той же причине: помнить надо одну строку.
 * **Не база**: обновляются и до входа, а база открывается после.
 *
 * Ради чего это заведено: до 2026-09-06 успех и обрыв установки выглядели для человека
 * одинаково — молчанием. Приложение закрывалось, он запускал его заново и не знал,
 * поставилось ли. Запись «пошёл ставить версию N» позволяет при следующем запуске
 * сравнить задуманное с тем, что стоит, и сказать словами.
 */
class UpdateMemory(
    val load: () -> String?,
    val save: (String) -> Unit,
) {
    companion object {
        /** Не помнит ничего: для проверок и для платформы, у которой места ещё нет. */
        val Forgetful: UpdateMemory = UpdateMemory(load = { null }, save = {})
    }
}

/**
 * Что сказать человеку при запуске. `null` — говорить нечего, и окна нет.
 *
 * Три повода, одно окно (решение заказчика 2026-09-06). Четвёртого не будет: окно,
 * которое показывают «ещё и вот об этом», перестают читать.
 */
sealed interface UpdateNews {
    /** Обновление, которое он начал, доехало. Показывается один раз. */
    data class Installed(val versionName: String, val notes: String) : UpdateNews

    /**
     * Начал ставить и не довёл: версия осталась прежней.
     *
     * Показывается после **каждой** брошенной установки (решение заказчика): незаконченная
     * установка — это состояние, а не разовое событие.
     */
    data class Broken(val wanted: String, val current: String, val notes: String) : UpdateNews

    /** Вышло важное обновление. Показывается при каждом запуске, пока не поставит. */
    data class Important(val versionName: String, val notes: String) : UpdateNews
}

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
 * Новость об обновлении как событие для подокна ([Notice]).
 *
 * Тексты живут здесь, а не в `Root`: оболочка знает, что человеку сказать про обновление,
 * а сборка приложения — нет. Действия подставляет тот, кто знает, куда вести.
 */
fun UpdateNews.notice(words: UpdateWords = RussianWords.update): Notice = when (this) {
    is UpdateNews.Installed -> Notice(
        title = words.installed,
        text = words.runningVersion(versionName),
        details = listOfNotNull(notes.takeIf { it.isNotBlank() }?.let { words.whatChanged(it) }),
    )

    is UpdateNews.Broken -> Notice(
        title = words.broken,
        // Названы обе версии: «не завершилось» без чисел человек читает как «что-то
        // сломалось», а с числами — как «осталось прежнее», что и есть правда.
        text = words.brokenText(wanted, current.ifBlank { words.version }),
        details = listOfNotNull(
            notes.takeIf { it.isNotBlank() }?.let { words.whatChanged(it) },
            words.chatsUntouched,
        ),
    )

    is UpdateNews.Important -> Notice(
        title = words.importantOut,
        text = words.availableVersion(versionName),
        details = listOfNotNull(
            notes.takeIf { it.isNotBlank() }?.let { words.whatChanged(it) },
            words.oldMayMisbehave,
        ),
    )
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
    /** Что сказать при запуске. `null` — нечего, окна нет. */
    val news: UpdateNews? = null,
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

    /**
     * Есть важное обновление, которое ещё не поставлено (уровень 1).
     *
     * Ровно [updateAvailable] плюс объявленная важность: важность чужого потока к нам не
     * относится так же, как и его номер версии, и проверка потока уже внутри.
     */
    val important: Boolean
        get() = updateAvailable && offer?.important == true

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
    /** Где помнится начатая установка. По умолчанию нигде — как было до 2026-09-06. */
    private val memory: UpdateMemory = UpdateMemory.Forgetful,
) {
    private val _state = MutableStateFlow(
        UpdateState(installed = installed, installedCode = installedCode, stream = stream),
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Умеет ли эта сборка ставить обновление сама. */
    val canInstall: Boolean get() = installer != null

    init {
        // Память читается ДО первого обращения к сети: исход прошлой установки известен
        // без сервера, и человек, у которого связи нет, всё равно узнает, поставилось ли.
        rememberedOutcome()?.let { _state.value = _state.value.copy(news = it) }
        check()
    }

    /**
     * Чем кончилась прошлая попытка — по записи на диске и по тому, что стоит сейчас.
     *
     * Запись стирается сразу: и успех, и обрыв говорятся один раз за попытку. Следующая
     * брошенная установка запишет её снова и снова покажет окно — так и задумано.
     */
    private fun rememberedOutcome(): UpdateNews? {
        val raw = memory.load()?.takeIf { it.isNotBlank() } ?: return null
        runCatching { memory.save("") }
        val parts = raw.split(SPLIT)
        val wantedCode = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val wantedName = parts.getOrNull(1).orEmpty()
        val notes = parts.getOrNull(2).orEmpty()
        val installed = _state.value.installedCode
        return if (installed >= wantedCode) {
            UpdateNews.Installed(_state.value.installed.ifBlank { wantedName }, notes)
        } else {
            UpdateNews.Broken(wantedName, _state.value.installed, notes)
        }
    }

    /** Окно закрыли. Важное вернётся при следующем запуске — оно на то и важное. */
    fun dismissNews() {
        _state.value = _state.value.copy(news = null)
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
                val next = _state.value.copy(offer = offer, expect = false, notConfigured = offer == null)
                // Новость об исходе прошлой установки важнее: она про то, что человек
                // уже сделал. Предложение поставить новое подождёт до следующего запуска.
                if (next.news == null && next.important && offer != null) {
                    next.copy(news = UpdateNews.Important(offer.versionName, offer.notes))
                } else {
                    next
                }
            } catch (e: Throwable) {
                // Сообщение исключения человеку не показываем: там адрес сервера и класс
                // ошибки Ktor. Ему нужно одно — что делать дальше.
                _state.value.copy(
                    expect = false,
                    trouble = CurrentWords.value.update.cannotAskServer,
                )
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
                InstallOutcome.Refused(CurrentWords.value.update.installerDidNotStart)
            }
            // Запись — ПЕРЕД закрытием и только на успешном запуске установщика: до
            // этого момента ставить ещё нечего, а после него нас могут не спросить.
            if (outcome is InstallOutcome.Started) {
                runCatching {
                    memory.save(
                        listOf(
                            offer.versionCode.toString(),
                            offer.versionName.replace(SPLIT, " "),
                            offer.notes.replace(SPLIT, " "),
                        ).joinToString(SPLIT),
                    )
                }
            }
            // **Скачивание кончилось при ЛЮБОМ исходе, включая успех** — находка
            // 2026-09-06, и это была настоящая поломка. Раньше здесь стояло
            // `installing = outcome is Started`, то есть при успехе экран навсегда
            // оставался в «Скачиваем 100% · не закрывайте приложение». На ПК этого не
            // видно: следом закрывается само приложение. На Android оно живёт, системный
            // установщик показывается поверх, и отказ от него возвращал человека на экран
            // без единой кнопки — выйти можно было только выгрузив приложение из памяти.
            _state.value = _state.value.copy(installing = false, outcome = outcome)
            // Приложение закрывается ПОСЛЕ того, как исход записан в состояние: иначе на
            // платформе, которая закрытие игнорирует, экран остался бы в «скачиваем».
            if (outcome is InstallOutcome.Started) onLeaving()
        }
    }

    private companion object {
        /** Разделитель полей памяти: в номере версии и примечании его не бывает. */
        const val SPLIT = "	"
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
    val words = Tima.words.update
    ListLine(
        middle = { Name(words.installedVersion(state.installed.ifBlank { "—" })) },
        right = { Secondary(state.stream.ifBlank { words.streamNotDeclared }) },
    )

    when {
        state.installing -> {
            Downloading(state.percent)
        }

        state.asking -> Asking(state, onConfirm, onDismiss)

        state.outcome is InstallOutcome.Started -> Handed(onInstall)

        state.outcome != null -> Failed(state.outcome, onInstall)

        state.expect -> Secondary(words.askingServer)

        state.trouble != null -> Trouble(state.trouble)

        state.notConfigured -> Secondary(words.notConfigured)

        state.updateAvailable -> {
            val offer = requireNotNull(state.offer)
            Caption(
                words.availableVersion(offer.versionName),
                fontSize = TimaType.sz3,
                weight = FontWeight.ExtraBold,
            )
            if (offer.notes.isNotBlank()) Secondary(offer.notes)
            if (offer.size > 0) Tertiary(words.download(megabytes(offer.size)))
            if (canInstall) {
                Button(label = words.install, onClick = onInstall)
            } else {
                // Кнопки нет, а не «есть и молчит»: сборка без установщика ставить не
                // умеет, и делать вид — худшее из двух.
                Tertiary(words.notSelfUpdating)
            }
        }

        state.alienStream -> {
            val offer = requireNotNull(state.offer)
            // Прямо и словами. Спрятать предложение значило бы, что человек, знающий про
            // «версию 0.6.5 на сайте», решит, будто приложение сломано.
            Secondary(words.alienStream(offer.versionName))
        }

        else -> Secondary(words.latestInstalled)
    }

    // Во время скачивания и вопроса кнопки нет вовсе: спрашивать сервер, пока идёт
    // загрузка, нечего — ответ ничего не изменит, а нажатие выглядит как способ
    // прервать её. Не «неактивна», а именно нет: неактивная кнопка тоже зовёт нажать.
    if (!state.installing && !state.asking) {
        Button(label = words.checkAgain, onClick = onCheck, kind = ButtonKind.Quiet)
    }
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
    val words = Tima.words.update
    Caption(words.mustUpdate, fontSize = TimaType.sz2, weight = FontWeight.ExtraBold)
    Secondary(words.mustUpdateAbout)
    Tertiary(words.installedVersion(state.installed.ifBlank { "—" }))

    when {
        state.installing -> {
            Downloading(state.percent)
        }

        state.asking -> Asking(state, onConfirm, onDismiss)

        state.outcome is InstallOutcome.Started -> Handed(onInstall)

        state.outcome != null -> Failed(state.outcome, onInstall)

        canInstall -> Button(label = words.install, onClick = onInstall)

        else -> Secondary(words.notSelfUpdatingLong)
    }
}

/**
 * Идёт скачивание.
 *
 * **Красным — то, что стоит человеку денег и времени; серым — то, что просто
 * происходит.** Решение заказчика 2026-09-06. Закрыть приложение сейчас значит начинать
 * заново: на ПК качает сам процесс приложения, на телефоне загрузку ведёт системная
 * служба, но наш счётчик умирает вместе с процессом, и докачанный файл мы теряем из
 * виду. Причины разные, для человека последствие одно.
 */
@Composable
private fun Downloading(percent: Int) {
    val words = Tima.words.update
    Caption(words.downloading(percent), fontSize = TimaType.sz3, weight = FontWeight.Bold)
    Alarm(words.dontCloseApp)
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
    val words = Tima.words.update
    Caption(
        words.installVersion(offer?.versionName ?: ""),
        fontSize = TimaType.sz3,
        weight = FontWeight.ExtraBold,
    )
    // Красным все три строки, и это решение заказчика 2026-09-06. Каждая — про то, что
    // человек обязан сделать сам: не испугаться исчезнувшего окна, подтвердить системный
    // вопрос, вернуться в приложение. Серым он их прочтёт как примечание и не сделает.
    Alarm(words.appWillClose)
    Alarm(words.confirmSystemAsk)
    Alarm(words.comeBackAfter)
    Secondary(words.dataStays)
    Button(label = words.installNow, onClick = onConfirm)
    Button(label = words.notNow, onClick = onDismiss, kind = ButtonKind.Quiet)
}

/**
 * Пакет отдан системе — дальше решает она.
 *
 * **Состояние, которого не было**, и его отсутствие было тупиком: на Android приложение
 * после запуска установщика живёт, а человек, отказавшийся от системного окна, попадал
 * обратно на «Скачиваем 100%» без кнопок. Отсюда правило: **из любого исхода должен быть
 * выход**. На ПК этот блок никто не увидит — приложение закрывается раньше.
 */
@Composable
private fun Handed(onRetry: () -> Unit) {
    val words = Tima.words.update
    Caption(words.installerStarted, fontSize = TimaType.sz3, weight = FontWeight.Bold)
    Secondary(words.confirmInSystem)
    Secondary(words.pressAgain)
    Button(label = words.installNow, onClick = onRetry)
}

/** Не получилось. Причина названа своими словами: от неё зависит, что делать дальше. */
@Composable
private fun Failed(outcome: InstallOutcome, onRetry: () -> Unit) {
    val words = Tima.words.update
    val (what, whatNext) = when (outcome) {
        InstallOutcome.NoConnection -> words.notDownloaded to words.notDownloadedAbout
        InstallOutcome.BadPackage -> words.badPackage to words.badPackageAbout
        InstallOutcome.NoHash -> words.noHash to words.noHashAbout
        is InstallOutcome.Refused -> words.installNotStarted to outcome.why
        InstallOutcome.Started -> "" to ""
    }
    Trouble(what)
    Secondary(whatNext)
    Button(label = words.tryAgain, onClick = onRetry)
}

/** Мегабайты с одним знаком: точные байты человеку не говорят ничего. */
private fun megabytes(bytes: Long): String {
    val tenths = (bytes * 10 + 524_288) / 1_048_576
    return (tenths / 10).toString() + "," + (tenths % 10)
}
