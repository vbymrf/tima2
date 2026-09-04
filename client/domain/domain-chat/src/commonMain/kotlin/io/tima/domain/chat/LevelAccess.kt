package io.tima.domain.chat

/**
 * Доступ к закрытым записям — третий круг (ADR-0019, ПЛАН-СОЦИУМА Г9).
 *
 * **Третий круг единственный, который не расходится по лентам.** Доступ к нему админ
 * открывает поимённо и может ограничить сроком; условие проверяет сервер.
 *
 * **Срок — в месяцах, не в днях.** Он хранится эпохой `YYYY-MM` и заканчивается вместе с
 * ней: месячная эпоха уже есть и уже двигает ключ, а срок в днях потребовал бы ежедневной
 * фоновой задачи ради того же результата.
 *
 * **Срок закрывает будущее, а не прошлое.** Прочитанное до истечения остаётся у человека:
 * отобрать показанное нельзя, и обещать этого нельзя тоже.
 */
class LevelAccess(private val access: AccessPort) {

    /** Попросить доступ. Повторная просьба не плодит строк — это решает сервер. */
    suspend fun ask(groupId: String): AskAccessStep {
        if (groupId.isBlank()) return AskAccessStep.Refused("bad_request")
        return access.ask(groupId)
    }

    /** Состав глазами админа: кто просит, у кого есть, до какого срока. */
    suspend fun grants(groupId: String): GrantsStep = access.grants(groupId)

    /**
     * Открыть доступ или отказать.
     *
     * @param untilEpoch срок в виде `YYYY-MM`; пусто — бессрочно. Проверяется здесь, а не
     *   только на сервере: неверный срок это опечатка в интерфейсе, и узнавать о ней из
     *   отказа сервера человеку незачем.
     */
    suspend fun decide(groupId: String, userId: String, grant: Boolean, untilEpoch: String = ""): GrantStep {
        if (groupId.isBlank() || userId.isBlank()) return GrantStep.Refused("bad_request")
        if (untilEpoch.isNotEmpty() && !isEpoch(untilEpoch)) return GrantStep.BadTerm
        return access.decide(groupId, userId, grant, untilEpoch)
    }

    companion object {
        /** Эпоха escrow: «2026-10». Тот же формат, что у сервера и у ротации ключа. */
        fun isEpoch(value: String): Boolean =
            value.length == 7 && value[4] == '-' &&
                value.take(4).all { it.isDigit() } &&
                value.takeLast(2).all { it.isDigit() } &&
                value.takeLast(2).toInt() in 1..12
    }
}

/** Состояние доступа одного человека. */
enum class AccessState {
    /** Просит и ещё не решено. */
    Asked,

    /** Доступ открыт. */
    Granted,

    /** Отказано. **Виден просившему** — молчание хуже отказа. */
    Declined,

    /** Не просил и не имеет. */
    None,
    ;

    companion object {
        fun from(wire: String): AccessState = when (wire) {
            "asked" -> Asked
            "granted" -> Granted
            "declined" -> Declined
            else -> None
        }
    }
}

/** Строка состава: чей доступ и какой. */
data class AccessGrant(
    val userId: String,
    val level: Int,
    val state: AccessState,
    /** Пусто — бессрочно. */
    val untilEpoch: String = "",
)

sealed interface AskAccessStep {
    /** Просьба ушла. `state` сервера: `asked`, а при повторе — то, что уже решено. */
    data class Asked(val state: AccessState) : AskAccessStep
    data class Offline(val retryAfterMs: Long) : AskAccessStep
    data class Refused(val reason: String) : AskAccessStep
}

sealed interface GrantsStep {
    /** Админу — весь состав. */
    data class Grants(val grants: List<AccessGrant>) : GrantsStep

    /** Участнику — только своё: какой у него круг и до какого срока. */
    data class Mine(val level: Int) : GrantsStep
    data class Offline(val retryAfterMs: Long) : GrantsStep
    data class Refused(val reason: String) : GrantsStep
}

sealed interface GrantStep {
    data object Done : GrantStep

    /** Срок не похож на эпоху. Отказ продукта: до сети дело не доходит. */
    data object BadTerm : GrantStep
    data object NotAllowed : GrantStep
    data class Offline(val retryAfterMs: Long) : GrantStep
    data class Refused(val reason: String) : GrantStep
}

/** Порт к серверу: просьбы и выдача доступа. */
interface AccessPort {
    suspend fun ask(groupId: String): AskAccessStep
    suspend fun grants(groupId: String): GrantsStep
    suspend fun decide(groupId: String, userId: String, grant: Boolean, untilEpoch: String): GrantStep
}
