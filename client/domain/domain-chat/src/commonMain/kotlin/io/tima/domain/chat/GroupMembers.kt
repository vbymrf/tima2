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
    /** Справочник по нику; `null` — позвать можно только по номеру. */
    private val nicknames: NicknameDirectory? = null,
) {

    suspend fun members(groupId: String): MembersStep = groups.members(groupId)

    /**
     * Позвать по номеру телефона.
     *
     * Номера, а не идентификатора: человек знает номер. Незарегистрированный номер — не
     * ошибка ввода, а повод позвать человека в мессенджер, и называется он отдельным
     * исходом, чтобы экран не заставлял искать опечатку.
     */
    suspend fun invite(groupId: String, number: String): MembershipStep {
        val found = when (val answer = directory.byPhone(number.trim())) {
            is UserLookup.Found -> answer.userId
            else -> return MembershipStep.NoSuchUser
        }
        return apply(groupId, groups.addMember(groupId, found), RotationReason.MemberJoin)
    }

    /**
     * Исключить участника.
     *
     * Ротация здесь — не гигиена, а смысл действия: без неё исключение означает лишь то,
     * что человек не увидит группу в своём списке, продолжая расшифровывать её сообщения.
     */
    /** Позвать по нику (решение заказчика 2026-09-18). `@` в начале допускается. */
    suspend fun inviteByNick(groupId: String, nick: String): MembershipStep {
        val book = nicknames ?: return MembershipStep.NoSuchUser
        val found = when (val answer = book.byNickname(nick.trim().removePrefix("@"))) {
            is UserLookup.Found -> answer.userId
            else -> return MembershipStep.NoSuchUser
        }
        return apply(groupId, groups.addMember(groupId, found), RotationReason.MemberJoin)
    }

    /** Позвать того, чей идентификатор уже известен — из книги. */
    suspend fun inviteUser(groupId: String, userId: String): MembershipStep =
        apply(groupId, groups.addMember(groupId, userId), RotationReason.MemberJoin)

    suspend fun remove(groupId: String, userId: String): MembershipStep =
        apply(groupId, groups.removeMember(groupId, userId), RotationReason.MemberLeave)

    private suspend fun apply(groupId: String, step: MemberStep, reason: RotationReason): MembershipStep = when (step) {
        MemberStep.Done -> when (val rotation = rotator.rotate(groupId, reason)) {
            RotateStep.Rotated -> MembershipStep.Done(switchedKey = true)

            // Кто-то ротировал раньше нас: версия уже другая, и наша попытка не нужна.
            // Для состава это успех, а не отказ.
            RotateStep.VersionConflict -> MembershipStep.Done(switchedKey = true)

            is RotateStep.Offline -> MembershipStep.DoneWithoutRotation(
                "Состав изменён, но ключ не сменился: нет связи. Повторите при связи",
            )
            RotateStep.NotAdmin -> MembershipStep.DoneWithoutRotation(
                "Состав изменён, но сменить ключ может только владелец или админ",
            )
            is RotateStep.Refused -> MembershipStep.DoneWithoutRotation(
                "Состав изменён, но ключ не сменился: ${rotation.reason}",
            )
        }
        MemberStep.NoSuchUser -> MembershipStep.NoSuchUser
        MemberStep.Forbidden -> MembershipStep.Forbidden
        is MemberStep.Offline -> MembershipStep.Offline(step.retryAfterMs)
        is MemberStep.Refused -> MembershipStep.Refused(step.reason)
    }
}

// ── порт ────────────────────────────────────────────────────────────────────

/**
 * Порт ротации. Реализуется составлением в `shared`: ротации нужны ключ эпохи escrow,
 * устройства участников, крипта и сеть разом — то есть ровно то, что домен не видит.
 */
fun interface GroupKeyRotator {
    /**
     * @param reason зачем ротируем — уходит серверу и решает, срочная ли ротация.
     *
     * До 2026-09-18 клиент слал одну строку на всё — `member_change`, — которой в списке
     * сервера (ADR-0017 §7) **нет**. Сервер отвечал 400 `bad_reason`, и ни одна ротация с
     * клиента v2 не проходила: ни при приглашении, ни при исключении, ни по счётчику.
     * Группы жили без ключа, а экран винил «устройство». Причина теперь перечнем: строку
     * не с чем сверить, перечень — не соврёт.
     */
    suspend fun rotate(groupId: String, reason: RotationReason): RotateStep
}

/**
 * Причины ротации — ровно те, что принимает сервер (`groups.go`, `reasonAllowed`).
 *
 * Срочные (`MemberJoin`, `MemberLeave`, `Compromise`) сервер проводит сразу; несрочные
 * (`Epoch`, `Periodic`) — не чаще раза в пятнадцать минут. Первый выпуск ключа идёт как
 * `MemberJoin`: так делал v1, и порог к нему не применяется — предыдущей ротации нет.
 */
enum class RotationReason(val wire: String) {
    Epoch("epoch"),
    Periodic("periodic"),
    MemberJoin("member_join"),
    MemberLeave("member_leave"),
    Compromise("compromise"),
    ;

    companion object {
        /** Причина из кадра сервера; неизвестная — `Periodic`, самая безопасная. */
        fun fromWire(wire: String): RotationReason = entries.firstOrNull { it.wire == wire } ?: Periodic
    }
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
    data class Done(val switchedKey: Boolean) : MembershipStep

    /**
     * Состав правлен, ключ — нет. Отдельный исход, а не «успех»: пока ключ прежний,
     * исключённый читает новые сообщения, и человек имеет право об этом знать.
     */
    data class DoneWithoutRotation(val warning: String) : MembershipStep

    data object NoSuchUser : MembershipStep
    data object Forbidden : MembershipStep
    data class Offline(val retryAfterMs: Long) : MembershipStep
    data class Refused(val reason: String) : MembershipStep
}
