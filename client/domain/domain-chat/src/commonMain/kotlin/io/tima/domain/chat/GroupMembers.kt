package io.tima.domain.chat

/**
 * Состав группы: посмотреть, позвать, исключить.
 *
 * **Смена состава влечёт ротацию ключа, и это часть операции, а не отдельная кнопка.**
 * Исключённому участнику ничего не запрещают на сервере — ему перестают создавать обёртки
 * новой версии. Значит, пока ротация не прошла, исключённый продолжает читать всё новое
 * своим прежним ключом: состав изменился, доступ — нет.
 *
 * ── ПОЧЕМУ РОТАЦИЯ НЕ ОТМЕНЯЕТ ПРАВКУ СОСТАВА ───────────────────────────────
 *
 * Состав меняет сервер, и к моменту, когда мы беремся ротировать, участник уже исключён.
 * Откатить это мы не можем — да и не должны: «не смог сменить ключ, поэтому вернул человека
 * в группу» никому не объяснить. Поэтому исход честно называет обе части: состав правлен,
 * а ключ — сменился или нет. Второе показывается человеку, потому что от этого зависит,
 * читает ли исключённый переписку прямо сейчас.
 *
 * Ротация по счёту сообщений (`crypto-protocol §4`: каждые 100) здесь не делается: у неё
 * другая срочность. Она про давность ключа, а вход и выход — про доступ.
 */
class ManageGroupMembers(
    private val groups: GroupRegistry,
    private val directory: UserDirectory,
    private val rotator: GroupKeyRotator,
) {

    suspend fun состав(groupId: String): MembersStep = groups.members(groupId)

    /**
     * Позвать по номеру телефона.
     *
     * Номера, а не идентификатора: человек знает номер. Незарегистрированный номер — не
     * ошибка ввода, а повод позвать человека в мессенджер, и называется он отдельным
     * исходом, чтобы экран не заставлял искать опечатку.
     */
    suspend fun позвать(groupId: String, номер: String): MembershipStep {
        val найден = when (val ответ = directory.byPhone(номер.trim())) {
            is UserLookup.Found -> ответ.userId
            else -> return MembershipStep.NoSuchUser
        }
        return применить(groupId, groups.addMember(groupId, найден))
    }

    /**
     * Исключить участника.
     *
     * Ротация здесь — не гигиена, а смысл действия: без неё исключение означает лишь то,
     * что человек не увидит группу в своём списке, продолжая расшифровывать её сообщения.
     */
    suspend fun исключить(groupId: String, userId: String): MembershipStep =
        применить(groupId, groups.removeMember(groupId, userId))

    private suspend fun применить(groupId: String, шаг: MemberStep): MembershipStep = when (шаг) {
        MemberStep.Done -> when (val ротация = rotator.ротировать(groupId)) {
            RotateStep.Rotated -> MembershipStep.Done(ключСменён = true)

            // Кто-то ротировал раньше нас: версия уже другая, и наша попытка не нужна.
            // Для состава это успех, а не отказ.
            RotateStep.VersionConflict -> MembershipStep.Done(ключСменён = true)

            is RotateStep.Offline -> MembershipStep.DoneWithoutRotation(
                "Состав изменён, но ключ не сменился: нет связи. Повторите при связи",
            )
            RotateStep.NotAdmin -> MembershipStep.DoneWithoutRotation(
                "Состав изменён, но сменить ключ может только владелец или админ",
            )
            is RotateStep.Refused -> MembershipStep.DoneWithoutRotation(
                "Состав изменён, но ключ не сменился: ${ротация.reason}",
            )
        }
        MemberStep.NoSuchUser -> MembershipStep.NoSuchUser
        MemberStep.Forbidden -> MembershipStep.Forbidden
        is MemberStep.Offline -> MembershipStep.Offline(шаг.retryAfterMs)
        is MemberStep.Refused -> MembershipStep.Refused(шаг.reason)
    }
}

// ── порт ────────────────────────────────────────────────────────────────────

/**
 * Порт ротации. Реализуется составлением в `shared`: ротации нужны ключ эпохи escrow,
 * устройства участников, крипта и сеть разом — то есть ровно то, что домен не видит.
 */
fun interface GroupKeyRotator {
    suspend fun ротировать(groupId: String): RotateStep
}

sealed interface RotateStep {
    data object Rotated : RotateStep

    /** Успели раньше нас. Для состава это успех: ключ всё равно другой. */
    data object VersionConflict : RotateStep
    data object NotAdmin : RotateStep
    data class Offline(val retryAfterMs: Long) : RotateStep
    data class Refused(val reason: String) : RotateStep
}

// ── исходы ──────────────────────────────────────────────────────────────────

sealed interface MembershipStep {
    /** Состав правлен и ключ сменён — то есть доступ действительно изменился. */
    data class Done(val ключСменён: Boolean) : MembershipStep

    /**
     * Состав правлен, ключ — нет. Отдельный исход, а не «успех»: пока ключ прежний,
     * исключённый читает новые сообщения, и человек имеет право об этом знать.
     */
    data class DoneWithoutRotation(val предупреждение: String) : MembershipStep

    data object NoSuchUser : MembershipStep
    data object Forbidden : MembershipStep
    data class Offline(val retryAfterMs: Long) : MembershipStep
    data class Refused(val reason: String) : MembershipStep
}
