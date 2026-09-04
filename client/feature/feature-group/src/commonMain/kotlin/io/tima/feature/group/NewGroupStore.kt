package io.tima.feature.group

import io.tima.domain.chat.CreateGroupChat
import io.tima.domain.chat.CreateGroupStep
import io.tima.domain.chat.GroupKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Мастер создания: четыре раздела на входе, работает один.
 *
 * Перерисован 2026-09-04 по `doc_UI/33` и макету `подокна/создание-группы.html`. Прежний
 * экран — название и номера одним листом — был придуман под одну личную группу, когда
 * места для мастера ещё не было.
 *
 * **Три раздела показаны и не выбираются.** Замысел виден целиком, а нажать нельзя: это
 * честнее, чем притвориться, будто группа — единственное, что бывает.
 *
 * **Личная группа не бывает открытой** ([Joining]): не запрет, а следствие — её не
 * находят поиском, а «вступить самому» требует сначала найти.
 *
 * Правила ввода прежние и по прежней причине: набранное не теряется при отказе, второе
 * нажатие не посылает второй запрос, отказ называется словами.
 */
class NewGroupStore(
    private val creation: CreateGroupChat,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(NewGroupState())
    val state: StateFlow<NewGroupState> = _state.asStateFlow()

    // ── шаги ────────────────────────────────────────────────────────────────

    /** Выбрать раздел. Недоступные молча игнорируются: они и не нажимаются. */
    fun choseSection(section: Section) {
        if (!section.ready) return
        _state.value = _state.value.copy(section = section, trouble = null)
    }

    /**
     * Выбрать вид группы.
     *
     * Личной доступно только закрытое вступление, и выбор поправляется здесь же — иначе
     * человек прошёл бы дальше с невозможным сочетанием и узнал бы об этом от сервера.
     */
    fun choseKind(kind: GroupKind) {
        val fixed = if (kind == GroupKind.Personal) Joining.Closed else _state.value.joining
        _state.value = _state.value.copy(kind = kind, joining = fixed, trouble = null)
    }

    /** Выбрать способ вступления. У личной группы открытого не бывает. */
    fun choseJoining(joining: Joining) {
        if (_state.value.kind == GroupKind.Personal && joining == Joining.Open) return
        _state.value = _state.value.copy(joining = joining, trouble = null)
    }

    /** Вперёд по шагам. Дальше последнего не идёт: там создание. */
    fun forward() {
        val current = _state.value
        _state.value = current.copy(step = current.step.next(), trouble = null)
    }

    /** Назад по шагам; с первого — выход из мастера (решает вызывающий по [NewGroupState.step]). */
    fun back() {
        val current = _state.value
        _state.value = current.copy(step = current.step.previous(), trouble = null)
    }

    /** Открыть или закрыть подокно «что это такое» — круг с вопросом у строки выбора. */
    fun explain(what: String?) {
        _state.value = _state.value.copy(explaining = what)
    }

    // ── поля последнего шага ────────────────────────────────────────────────

    fun changedTitle(text: String) {
        _state.value = _state.value.copy(title = text, trouble = null)
    }

    fun changedDescription(text: String) {
        _state.value = _state.value.copy(description = text, trouble = null)
    }

    fun changedNumber(text: String) {
        _state.value = _state.value.copy(number = text, trouble = null)
    }

    /**
     * Добавить набранный номер в список приглашаемых.
     *
     * Номера накапливаются до создания, а не после: группу создают один раз, и звать в неё
     * по одному, каждый раз через сеть, — это ротация ключа на каждого приглашённого.
     */
    fun addNumber() {
        val current = _state.value
        val number = current.number.trim()
        if (number.isEmpty()) return
        if (number in current.numbers) {
            // Молча проглотить повтор нельзя: человек будет жать снова, думая, что не
            // сработало. Сказать словами — дешевле.
            _state.value = current.copy(number = "", trouble = "Этот номер уже в списке")
            return
        }
        _state.value = current.copy(numbers = current.numbers + number, number = "", trouble = null)
    }

    fun removeNumber(number: String) {
        _state.value = _state.value.copy(numbers = _state.value.numbers - number)
    }

    /** Человек нажал «Создать». */
    fun create() {
        val current = _state.value
        if (current.expect) return
        _state.value = current.copy(expect = true, trouble = null)

        scope.launch {
            val outcome = creation.create(
                title = current.title,
                number = current.numbers,
                kind = current.kind,
                description = current.description,
            )
            _state.value = when (outcome) {
                is CreateGroupStep.Created -> current.copy(
                    expect = false,
                    created = outcome.groupId,
                    notInvited = outcome.notInvited,
                )
                is CreateGroupStep.BadTitle -> current.copyWithTrouble(outcome.reason)
                is CreateGroupStep.Offline -> current.copyWithTrouble(
                    "Нет связи с сервером — повторим через ${(outcome.retryAfterMs / 1000).coerceAtLeast(1)} с",
                )
                is CreateGroupStep.Refused -> current.copyWithTrouble(outcome.reason)
            }
        }
    }

    /** Экран закрыт: следующее открытие начинается с первого шага. */
    fun reset() {
        _state.value = NewGroupState()
    }
}

/** Что видно на экране мастера. */
data class NewGroupState(
    val step: Step = Step.Section,
    val section: Section = Section.Group,
    val kind: GroupKind = GroupKind.Personal,
    val joining: Joining = Joining.Closed,
    val title: String = "",
    val description: String = "",
    val number: String = "",
    /** Кого зовут: накопленные номера. */
    val numbers: List<String> = emptyList(),
    /** Открыто подокно «что это такое»; null — закрыто. */
    val explaining: String? = null,
    val trouble: String? = null,
    val expect: Boolean = false,
    /** Группа создана: её идентификатор. Приложение открывает её и закрывает мастер. */
    val created: String? = null,
    /** Номера, которых нет в TIMA. Группа при этом создана. */
    val notInvited: List<String> = emptyList(),
) {
    fun copyWithTrouble(text: String) = copy(trouble = text, expect = false)

    /** У личной группы открытого вступления не бывает — строка выбора неактивна. */
    val openJoiningAllowed: Boolean get() = kind == GroupKind.Public
}

/** Шаги мастера. Названы по тому, что человек выбирает, а не по номеру. */
enum class Step {
    Section, Kind, Joining, Naming;

    fun next(): Step = entries.getOrElse(ordinal + 1) { this }
    fun previous(): Step = entries.getOrElse(ordinal - 1) { this }
}

/**
 * Четыре раздела верхнего уровня.
 *
 * `ready = false` значит «показываем, но не выбирается»: у таких строк круг выбора
 * пунктирный и подпись «скоро».
 */
enum class Section(val title: String, val about: String, val ready: Boolean) {
    Group("Группа", "Общение нескольких участников. Личная или публичная", true),
    Channel("Канал", "Публикации для подписчиков", false),
    Community("Сообщество", "Контейнер: группы, каналы и звуковые чаты", false),
    VoiceRoom("Звуковой чат", "Голосовая комната внутри сообщества", false),
}

/** Способ вступления — вторая ось (ADR-0019, `doc_UI/33` шаг 3). */
enum class Joining(val title: String, val about: String) {
    Open("Открытая", "Нашёл и вступил сам"),
    Closed("Закрытая", "Подал заявку, админ разрешил"),
}
