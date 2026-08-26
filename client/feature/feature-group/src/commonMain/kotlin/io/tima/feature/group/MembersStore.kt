package io.tima.feature.group

import io.tima.domain.chat.GroupMember
import io.tima.domain.chat.GroupRole
import io.tima.domain.chat.ManageGroupMembers
import io.tima.domain.chat.MembersStep
import io.tima.domain.chat.MembershipStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Состав группы: кто в ней, кого позвать, кого исключить.
 *
 * **Предупреждение о несменившемся ключе живёт в состоянии наравне с бедой, а не вместо
 * неё.** Это не ошибка — состав действительно изменён, — но и не успех: пока ключ прежний,
 * исключённый читает новые сообщения. Молчаливое «готово» на этом месте было бы враньём,
 * которое человек обнаружит слишком поздно.
 *
 * **Права проверяются по своей роли, а не по ответу сервера.** Кнопки исключения не
 * показываются тому, кому нельзя: узнавать о запрете нажатием — значит предлагать человеку
 * то, чего он не может, и объяснять отказ там, где вопроса быть не должно.
 */
class MembersStore(
    private val members: ManageGroupMembers,
    private val groupId: String,
    private val myUserId: String,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(MembersState())
    val state: StateFlow<MembersState> = _state.asStateFlow()

    /** Открыли экран. */
    fun refresh() {
        _state.value = _state.value.copy(expect = true, trouble = null)
        scope.launch {
            _state.value = when (val outcome = members.members(groupId)) {
                is MembersStep.Members -> _state.value.copy(
                    expect = false,
                    members = outcome.members,
                    myRole = outcome.members.firstOrNull { it.userId == myUserId }?.role
                        ?: GroupRole.Unknown,
                )
                is MembersStep.Offline -> _state.value.copyWithTrouble(
                    "Нет связи с сервером — список может быть устаревшим",
                )
                is MembersStep.Refused -> _state.value.copyWithTrouble(outcome.reason)
            }
        }
    }

    fun changedNumber(text: String) {
        _state.value = _state.value.copy(number = text, trouble = null, warning = null)
    }

    fun invite() {
        val current = _state.value
        if (current.expect || current.number.isBlank()) return
        _state.value = current.copy(expect = true, trouble = null, warning = null)

        scope.launch {
            apply(members.invite(groupId, current.number), clearNumber = true)
        }
    }

    fun remove(userId: String) {
        if (_state.value.expect) return
        _state.value = _state.value.copy(expect = true, trouble = null, warning = null)
        scope.launch { apply(members.remove(groupId, userId), clearNumber = false) }
    }

    private fun apply(step: MembershipStep, clearNumber: Boolean) {
        val database = _state.value.copy(expect = false, number = if (clearNumber) "" else _state.value.number)
        _state.value = when (step) {
            is MembershipStep.Done -> database

            // Отдельная ветвь, а не текст беды: состав правлен, и показывать это красным
            // словом «ошибка» значило бы заставить человека повторять сделанное.
            is MembershipStep.DoneWithoutRotation -> database.copy(warning = step.warning)

            MembershipStep.NoSuchUser -> database.copy(
                trouble = "Этого номера в TIMA нет — позовите человека в мессенджер",
                number = _state.value.number,
            )
            MembershipStep.Forbidden -> database.copy(trouble = "Менять состав может владелец или админ")
            is MembershipStep.Offline -> database.copy(
                trouble = "Нет связи с сервером — повторим через ${(step.retryAfterMs / 1000).coerceAtLeast(1)} с",
            )
            is MembershipStep.Refused -> database.copy(trouble = step.reason)
        }
        // Состав мог измениться — перечитываем его у сервера, а не правим у себя: свой
        // список, собранный из догадок, разойдётся с настоящим на первой же гонке.
        if (step is MembershipStep.Done || step is MembershipStep.DoneWithoutRotation) refresh()
    }
}

/** Что видно на экране состава. */
data class MembersState(
    val members: List<GroupMember> = emptyList(),
    val myRole: GroupRole = GroupRole.Unknown,
    val number: String = "",
    val expect: Boolean = false,
    val trouble: String? = null,
    /** Состав изменён, а ключ — нет. Не ошибка, но человек обязан знать. */
    val warning: String? = null,
) {
    /** Звать и исключать могут владелец и админ — правило сервера, повторённое здесь. */
    val memberEdit: Boolean get() = myRole.deliveryEdits

    fun copyWithTrouble(text: String) = copy(trouble = text, expect = false)
}
