package io.tima.feature.group

import io.tima.domain.chat.AskStep
import io.tima.domain.chat.CardsStep
import io.tima.domain.chat.GroupCard
import io.tima.domain.chat.GroupInfo
import io.tima.domain.chat.GroupRegistry
import io.tima.domain.chat.GroupsStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Окно 2 «Социум»: каталог своих групп и карточки, которые открыли контакты.
 *
 * **Два списка, и они не пересекаются.** «Каталог» — где я состою; «Друзья» — то, что
 * положили себе люди из книги и куда я ещё не вступил. Группа, в которую я вступил,
 * уходит из «Друзей» сама — сервер её оттуда не отдаёт.
 *
 * **Пустой список и несостоявшаяся загрузка — разные состояния.** «Групп нет» и «не
 * дошли до сервера» выглядят одинаково, если хранить только список; поэтому есть
 * [SocialState.trouble] и [SocialState.loaded].
 */
class SocialStore(
    private val groups: GroupRegistry,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(SocialState())
    val state: StateFlow<SocialState> = _state.asStateFlow()

    /** Обновить оба списка. Вызывается при открытии окна и по возвращении в него. */
    fun refresh() {
        val current = _state.value
        if (current.expect) return
        _state.value = current.copy(expect = true, trouble = null)

        scope.launch {
            val mine = groups.mine()
            val cards = groups.cards()
            _state.value = _state.value.copy(
                expect = false,
                loaded = true,
                mine = (mine as? GroupsStep.Groups)?.groups ?: _state.value.mine,
                cards = (cards as? CardsStep.Cards)?.cards ?: _state.value.cards,
                trouble = troubleOf(mine, cards),
            )
        }
    }

    /**
     * Попроситься в чужую группу.
     *
     * Состояние просьбы держится по идентификатору группы: человек может попроситься в
     * несколько, и «попрошено» относится к строке, а не ко всему экрану.
     */
    fun ask(groupId: String) {
        if (_state.value.asking.contains(groupId)) return
        _state.value = _state.value.copy(asking = _state.value.asking + groupId, trouble = null)

        scope.launch {
            val answer = groups.askToJoin(groupId)
            val current = _state.value
            _state.value = when (answer) {
                is AskStep.Asked -> current.copy(
                    asking = current.asking - groupId,
                    asked = current.asked + groupId,
                )
                is AskStep.Offline -> current.copy(
                    asking = current.asking - groupId,
                    trouble = "Нет связи с сервером — повторим через ${(answer.retryAfterMs / 1000).coerceAtLeast(1)} с",
                )
                is AskStep.Refused -> current.copy(
                    asking = current.asking - groupId,
                    trouble = "Не получилось попроситься: ${answer.reason}",
                )
            }
        }
    }

    private fun troubleOf(mine: GroupsStep, cards: CardsStep): String? = when {
        mine is GroupsStep.Offline -> "Нет связи с сервером — список групп может быть неполным"
        mine is GroupsStep.Refused -> "Сервер отказал: ${mine.reason}"
        cards is CardsStep.Offline -> "Нет связи с сервером — карточки друзей могут быть неполными"
        cards is CardsStep.Refused -> "Сервер отказал: ${cards.reason}"
        else -> null
    }
}

/** Что видно в окне «Социум». */
data class SocialState(
    /** Где я состою — вкладка «Каталог». */
    val mine: List<GroupInfo> = emptyList(),
    /** Что открыли контакты — вкладка «Друзья». */
    val cards: List<GroupCard> = emptyList(),
    /** Идёт запрос: списки могли ещё не приехать. */
    val expect: Boolean = false,
    /** Списки хоть раз доехали. До этого «пусто» означает «не знаем», а не «нет». */
    val loaded: Boolean = false,
    /** По каким группам просьба уже ушла — строка показывает это словами. */
    val asked: Set<String> = emptySet(),
    /** По каким идёт запрос прямо сейчас. */
    val asking: Set<String> = emptySet(),
    val trouble: String? = null,
)
