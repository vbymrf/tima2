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
class СоставStore(
    private val участники: ManageGroupMembers,
    private val groupId: String,
    private val myUserId: String,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(СоставState())
    val state: StateFlow<СоставState> = _state.asStateFlow()

    /** Открыли экран. */
    fun обновить() {
        _state.value = _state.value.copy(ждём = true, беда = null)
        scope.launch {
            _state.value = when (val исход = участники.состав(groupId)) {
                is MembersStep.Members -> _state.value.copy(
                    ждём = false,
                    участники = исход.members,
                    мояРоль = исход.members.firstOrNull { it.userId == myUserId }?.role
                        ?: GroupRole.Неизвестная,
                )
                is MembersStep.Offline -> _state.value.копияСБедой(
                    "Нет связи с сервером — список может быть устаревшим",
                )
                is MembersStep.Refused -> _state.value.копияСБедой(исход.reason)
            }
        }
    }

    fun номерИзменён(текст: String) {
        _state.value = _state.value.copy(номер = текст, беда = null, предупреждение = null)
    }

    fun позвать() {
        val текущее = _state.value
        if (текущее.ждём || текущее.номер.isBlank()) return
        _state.value = текущее.copy(ждём = true, беда = null, предупреждение = null)

        scope.launch {
            применить(участники.позвать(groupId, текущее.номер), очиститьНомер = true)
        }
    }

    fun исключить(userId: String) {
        if (_state.value.ждём) return
        _state.value = _state.value.copy(ждём = true, беда = null, предупреждение = null)
        scope.launch { применить(участники.исключить(groupId, userId), очиститьНомер = false) }
    }

    private fun применить(шаг: MembershipStep, очиститьНомер: Boolean) {
        val база = _state.value.copy(ждём = false, номер = if (очиститьНомер) "" else _state.value.номер)
        _state.value = when (шаг) {
            is MembershipStep.Done -> база

            // Отдельная ветвь, а не текст беды: состав правлен, и показывать это красным
            // словом «ошибка» значило бы заставить человека повторять сделанное.
            is MembershipStep.DoneWithoutRotation -> база.copy(предупреждение = шаг.предупреждение)

            MembershipStep.NoSuchUser -> база.copy(
                беда = "Этого номера в TIMA нет — позовите человека в мессенджер",
                номер = _state.value.номер,
            )
            MembershipStep.Forbidden -> база.copy(беда = "Менять состав может владелец или админ")
            is MembershipStep.Offline -> база.copy(
                беда = "Нет связи с сервером — повторим через ${(шаг.retryAfterMs / 1000).coerceAtLeast(1)} с",
            )
            is MembershipStep.Refused -> база.copy(беда = шаг.reason)
        }
        // Состав мог измениться — перечитываем его у сервера, а не правим у себя: свой
        // список, собранный из догадок, разойдётся с настоящим на первой же гонке.
        if (шаг is MembershipStep.Done || шаг is MembershipStep.DoneWithoutRotation) обновить()
    }
}

/** Что видно на экране состава. */
data class СоставState(
    val участники: List<GroupMember> = emptyList(),
    val мояРоль: GroupRole = GroupRole.Неизвестная,
    val номер: String = "",
    val ждём: Boolean = false,
    val беда: String? = null,
    /** Состав изменён, а ключ — нет. Не ошибка, но человек обязан знать. */
    val предупреждение: String? = null,
) {
    /** Звать и исключать могут владелец и админ — правило сервера, повторённое здесь. */
    val правлюСоставом: Boolean get() = мояРоль.правитПоставом

    fun копияСБедой(текст: String) = copy(беда = текст, ждём = false)
}
