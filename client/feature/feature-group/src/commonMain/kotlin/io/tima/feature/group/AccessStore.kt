package io.tima.feature.group

import io.tima.domain.chat.AccessGrant
import io.tima.domain.chat.AccessPort
import io.tima.domain.chat.AccessState
import io.tima.domain.chat.AskAccessStep
import io.tima.domain.chat.GrantStep
import io.tima.domain.chat.GrantsStep
import io.tima.domain.chat.LevelAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Подокно «Доступ» — третий круг (ПЛАН-СОЦИУМА Г9, макет `подокна/доступ.html`).
 *
 * Один экран для двоих, и различает их **ответ сервера**, а не догадка клиента: админу
 * приезжает состав, участнику — его собственный круг. Спрашивать «а не админ ли я» отдельно
 * значило бы держать второй источник правды о правах.
 *
 * **Отказ виден просившему** (решение заказчика 2026-09-04). Молчание хуже отказа: человек
 * ждёт ответа, которого не будет, и просит снова.
 */
class AccessStore(
    port: AccessPort,
    private val groupId: String,
    private val scope: CoroutineScope,
    /**
     * Эпоха через столько месяцев — «2026-10».
     *
     * Функцией снаружи, а не расчётом здесь: часы устройства и календарь живут в
     * `shared`, а экрану нужен готовый срок. Заодно проверка не зависит от того, какой
     * сегодня месяц.
     */
    epochAfter: (Int) -> String = { "" },
) {

    private val access = LevelAccess(port)

    private val _state = MutableStateFlow(
        AccessState2(
            terms = listOf(
                AccessTerm("Месяц", epochAfter(1)),
                AccessTerm("Три месяца", epochAfter(3)),
            ).filter { it.epoch.isNotBlank() },
        ),
    )
    val state: StateFlow<AccessState2> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            _state.value = when (val outcome = access.grants(groupId)) {
                is GrantsStep.Grants -> _state.value.copy(
                    admin = true,
                    grants = outcome.grants,
                    loaded = true,
                    trouble = null,
                )

                is GrantsStep.Mine -> _state.value.copy(
                    admin = false,
                    myLevel = outcome.level,
                    loaded = true,
                    trouble = null,
                )

                is GrantsStep.Offline -> _state.value.copy(loaded = true, trouble = "Нет связи с сервером")
                is GrantsStep.Refused -> _state.value.copy(loaded = true, trouble = "Сервер отказал: ${outcome.reason}")
            }
        }
    }

    /** Участник просит доступ. Второе нажатие не шлёт вторую просьбу. */
    fun ask() {
        if (_state.value.asking) return
        _state.value = _state.value.copy(asking = true, trouble = null)
        scope.launch {
            val outcome = access.ask(groupId)
            _state.value = _state.value.copy(
                asking = false,
                asked = outcome is AskAccessStep.Asked,
                // Повторная просьба возвращает то, что уже решено: отказ показывается
                // словом, а не выглядит как «ещё думают».
                mine = (outcome as? AskAccessStep.Asked)?.state ?: _state.value.mine,
                trouble = when (outcome) {
                    is AskAccessStep.Asked -> null
                    is AskAccessStep.Offline -> "Нет связи с сервером"
                    is AskAccessStep.Refused -> "Сервер отказал: ${outcome.reason}"
                },
            )
        }
    }

    /**
     * Админ решает: открыть или отказать.
     *
     * @param untilEpoch пусто — бессрочно; «2026-10» — до конца октября. Срок закрывает
     *   будущее: прочитанное до истечения остаётся у человека.
     */
    fun decide(userId: String, grant: Boolean, untilEpoch: String = "") {
        if (userId in _state.value.deciding) return
        _state.value = _state.value.copy(deciding = _state.value.deciding + userId)
        scope.launch {
            val outcome = access.decide(groupId, userId, grant, untilEpoch)
            _state.value = _state.value.copy(
                deciding = _state.value.deciding - userId,
                trouble = when (outcome) {
                    GrantStep.Done -> null
                    GrantStep.BadTerm -> "Срок пишется как 2026-10 — год и месяц"
                    GrantStep.NotAllowed -> "Доступ открывает админ группы"
                    is GrantStep.Offline -> "Нет связи с сервером"
                    is GrantStep.Refused -> "Сервер отказал: ${outcome.reason}"
                },
            )
            if (outcome == GrantStep.Done) refresh()
        }
    }

    fun troubleDismissed() {
        _state.value = _state.value.copy(trouble = null)
    }
}

/** Срок выдачи: то, что написано на кнопке, и то, что уйдёт на сервер. */
data class AccessTerm(val title: String, val epoch: String)

/**
 * Что видно в подокне «Доступ».
 *
 * Имя с двойкой — вынужденное: `AccessState` уже занято перечнем состояний доступа в
 * домене, а он важнее: его читают в трёх местах, и переименовывать понятие ради экрана
 * неверно.
 */
data class AccessState2(
    /**
     * Сроки, которые можно предложить: подпись и эпоха.
     *
     * Считаются один раз при открытии экрана. Пустой список — часов не дали, и тогда
     * остаётся только «бессрочно»: предлагать срок, которого мы не умеем посчитать,
     * значило бы обещать несбыточное.
     */
    val terms: List<AccessTerm> = emptyList(),
    /** Админ ли смотрит. От этого зависит, что вообще есть на экране. */
    val admin: Boolean = false,
    /** Состав: кто просит, у кого есть, до какого срока. Пусто у участника. */
    val grants: List<AccessGrant> = emptyList(),
    /** Свой круг: 3 — доступ есть, меньше — нет. */
    val myLevel: Int = 2,
    /** Своё состояние просьбы. */
    val mine: AccessState = AccessState.None,
    val asking: Boolean = false,
    val asked: Boolean = false,
    /** Решения в пути: второе нажатие по той же строке не шлёт второй запрос. */
    val deciding: Set<String> = emptySet(),
    /** Был ли ответ: пустой состав и «ещё не знаем» — разные состояния. */
    val loaded: Boolean = false,
    val trouble: String? = null,
)
