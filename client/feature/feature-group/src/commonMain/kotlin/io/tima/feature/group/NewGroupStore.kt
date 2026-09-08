package io.tima.feature.group

import io.tima.domain.chat.ChannelStep
import io.tima.domain.chat.CommunityItem
import io.tima.domain.chat.CommunityStep
import io.tima.domain.chat.CreateChannel
import io.tima.domain.chat.CreateCommunity
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
    /**
     * Создание канала. `null` — раздел «Канал» остаётся серым: показывать шаг, которым
     * нечего выполнить, значит обещать несуществующее.
     */
    private val channels: CreateChannel? = null,
    /** Создание сообщества. `null` — раздел серый по той же причине. */
    private val communities: CreateCommunity? = null,
    /** Что можно внести в сообщество: свои группы и каналы, ещё не связанные ни с чем. */
    private val linkable: (suspend () -> List<CommunityItem>)? = null,
) {

    private val _state = MutableStateFlow(NewGroupState())
    val state: StateFlow<NewGroupState> = _state.asStateFlow()

    // ── шаги ────────────────────────────────────────────────────────────────

    /**
     * Готов ли раздел: показывается ли он выбираемым.
     *
     * Считается по наличию того, чем его выполнить, а не отдельным признаком: два
     * источника одной правды разошлись бы, и человек нажал бы на раздел, который некому
     * обслужить.
     */
    fun ready(section: Section): Boolean = when (section) {
        Section.Group -> true
        Section.Channel -> channels != null
        Section.Community -> communities != null
        // Звуковой чат ждёт реализации (решение заказчика 2026-09-08): ни сервера, ни
        // хранения. Серым он остаётся по решению, а не потому, что не дошли руки.
        Section.VoiceRoom -> false
    }

    /** Выбрать раздел. Недоступные молча игнорируются: они и не нажимаются. */
    fun choseSection(section: Section) {
        if (!ready(section)) return
        _state.value = _state.value.copy(section = section, trouble = null)
        // Сообществу на последнем шаге нужен список того, что можно внести. Спрашивается
        // при входе в раздел, а не при открытии мастера: за списком идёт сеть, и тому,
        // кто создаёт группу, он не нужен вовсе.
        if (section == Section.Community) loadLinkable()
    }

    private fun loadLinkable() {
        val ask = linkable ?: return
        scope.launch { _state.value = _state.value.copy(linkable = ask()) }
    }

    /** Отметить или снять элемент в списке «что вносим». */
    fun choseItem(item: CommunityItem) {
        val chosen = _state.value.bringing
        _state.value = _state.value.copy(
            bringing = if (chosen.any { it.id == item.id }) {
                chosen.filterNot { it.id == item.id }
            } else {
                chosen + item
            },
        )
    }

    /** Виден ли канал в каталоге. «По подписке» значит «не в каталоге». */
    fun choseCatalogue(inCatalogue: Boolean) {
        _state.value = _state.value.copy(inCatalogue = inCatalogue, trouble = null)
    }

    /** Принимает ли канал обсуждения (ADR-0024 §6). */
    fun choseComments(comments: Boolean) {
        _state.value = _state.value.copy(comments = comments, trouble = null)
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
        _state.value = current.copy(step = stepAfter(current), trouble = null)
    }

    /** Назад по шагам; с первого — выход из мастера (решает вызывающий по [NewGroupState.step]). */
    fun back() {
        val current = _state.value
        _state.value = current.copy(step = stepBefore(current), trouble = null)
    }

    /**
     * Порядок шагов **зависит от раздела**, и это не украшение.
     *
     * У группы спрашивают вид и способ вступления; у канала — каталог и обсуждения; у
     * сообщества вида нет вовсе, зато есть «что вносим». Один линейный порядок заставил
     * бы показывать вопросы, у которых в этом разделе нет ответа.
     */
    private fun stepAfter(state: NewGroupState): Step = when (state.section) {
        Section.Group -> when (state.step) {
            Step.Section -> Step.Kind
            Step.Kind -> Step.Joining
            else -> Step.Naming
        }

        Section.Channel -> when (state.step) {
            Step.Section -> Step.Catalogue
            Step.Catalogue -> Step.Comments
            else -> Step.Naming
        }

        Section.Community -> when (state.step) {
            Step.Section -> Step.Naming
            else -> Step.Bringing
        }

        Section.VoiceRoom -> state.step
    }

    private fun stepBefore(state: NewGroupState): Step = when (state.section) {
        Section.Group -> when (state.step) {
            Step.Naming -> Step.Joining
            Step.Joining -> Step.Kind
            else -> Step.Section
        }

        Section.Channel -> when (state.step) {
            Step.Naming -> Step.Comments
            Step.Comments -> Step.Catalogue
            else -> Step.Section
        }

        Section.Community -> when (state.step) {
            Step.Bringing -> Step.Naming
            else -> Step.Section
        }

        Section.VoiceRoom -> Step.Section
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

        when (current.section) {
            Section.Channel -> return createChannel(current)
            Section.Community -> return createCommunity(current)
            else -> Unit
        }

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

    private fun createChannel(current: NewGroupState) {
        val case = channels ?: return
        scope.launch {
            val outcome = case.create(
                title = current.title,
                description = current.description,
                inCatalogue = current.inCatalogue,
                comments = current.comments,
            )
            _state.value = when (outcome) {
                is ChannelStep.Created -> current.copy(expect = false, created = outcome.channelId)
                is ChannelStep.BadTitle -> current.copyWithTrouble(outcome.reason)
                is ChannelStep.Offline -> current.copyWithTrouble("Нет связи с сервером")
                is ChannelStep.Refused -> current.copyWithTrouble("Сервер отказал: " + outcome.reason)
            }
        }
    }

    private fun createCommunity(current: NewGroupState) {
        val case = communities ?: return
        scope.launch {
            val outcome = case.create(
                title = current.title,
                description = current.description,
                items = current.bringing,
            )
            _state.value = when (outcome) {
                is CommunityStep.Created -> current.copy(
                    expect = false,
                    created = outcome.communityId,
                    // Что не внеслось — названо поимённо. Молчание здесь означало бы,
                    // что человек считает связанным то, чего в сообществе нет.
                    notLinked = outcome.notLinked,
                )

                is CommunityStep.BadTitle -> current.copyWithTrouble(outcome.reason)
                is CommunityStep.Offline -> current.copyWithTrouble("Нет связи с сервером")
                is CommunityStep.Refused -> current.copyWithTrouble("Сервер отказал: " + outcome.reason)
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
    /** Канал: виден ли в каталоге. «По подписке» значит «не в каталоге». */
    val inCatalogue: Boolean = true,
    /** Канал: принимает ли обсуждения (ADR-0024 §6). */
    val comments: Boolean = true,
    /** Сообщество: что можно внести — свои группы и каналы, ещё не связанные ни с чем. */
    val linkable: List<CommunityItem> = emptyList(),
    /** Сообщество: что человек отметил на шаге «что вносим». */
    val bringing: List<CommunityItem> = emptyList(),
    /** Сообщество создано, но эти элементы внести не удалось: они уже в другом. */
    val notLinked: List<String> = emptyList(),
) {
    fun copyWithTrouble(text: String) = copy(trouble = text, expect = false)

    /** У личной группы открытого вступления не бывает — строка выбора неактивна. */
    val openJoiningAllowed: Boolean get() = kind == GroupKind.Public
}

/** Шаги мастера. Названы по тому, что человек выбирает, а не по номеру. */
enum class Step {
    Section,

    // Группа: вид и способ вступления.
    Kind,
    Joining,

    // Канал: каталог и обсуждения.
    Catalogue,
    Comments,

    Naming,

    // Сообщество: что вносим. Последний шаг именно здесь — сначала называют, потом
    // наполняют: список внесённого без названия сообщества читается как список ничего.
    Bringing,
}

/**
 * Четыре раздела верхнего уровня.
 *
 * `ready = false` значит «показываем, но не выбирается»: у таких строк круг выбора
 * пунктирный и подпись «скоро».
 */
enum class Section(val title: String, val about: String) {
    Group("Группа", "Общение нескольких участников. Личная или публичная"),
    Channel("Канал", "Публикации для подписчиков"),
    Community("Сообщество", "Контейнер: группы и каналы. Связывает готовое, а не создаёт новое"),
    VoiceRoom("Звуковой чат", "Голосовая комната. Ждёт реализации"),
}

/** Способ вступления — вторая ось (ADR-0019, `doc_UI/33` шаг 3). */
enum class Joining(val title: String, val about: String) {
    Open("Открытая", "Нашёл и вступил сам"),
    Closed("Закрытая", "Подал заявку, админ разрешил"),
}
