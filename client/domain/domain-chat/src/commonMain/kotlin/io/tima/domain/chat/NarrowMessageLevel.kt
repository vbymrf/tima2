package io.tima.domain.chat

/**
 * Сужение круга у уже отправленного сообщения (ADR-0019 §6, ПЛАН-СОЦИУМА Г7).
 *
 * **Расширять нельзя никому, и отказ живёт здесь, а не только на сервере.** Понизить
 * уровень значит выложить наружу написанное для узкого круга, а согласия автора на это
 * никто не давал. Сервер это отвергает (`cannot_widen`), но экран не должен доводить до
 * отказа: человеку нельзя предлагать действие, которое заведомо не состоится. Отсюда
 * правило продукта — в домене, где его видят и экран, и проверка.
 *
 * **Шифр не сужается и не расширяется.** Уровень −1 — это отсутствие показа вовсе: у
 * такого сообщения нет круга, который можно ужать. Обратное — сделать открытое
 * зашифрованным — невозможно физически: сообщение уже лежит на сервере открытым, и
 * ключа сервер не видит по построению.
 */
class NarrowMessageLevel(private val levels: MessageLevels) {

    /**
     * @param was круг, который у сообщения сейчас. Берётся из строки на экране, а не
     *   спрашивается у сервера: решение принимается по тому, что видит человек.
     * @param to круг, до которого сужаем. Больше номер — уже круг (шкала монотонна).
     */
    suspend fun narrow(groupId: String, messageId: Long, was: Int, to: Int): NarrowStep {
        if (groupId.isBlank() || messageId <= 0) return NarrowStep.Refused("bad_request")
        if (to == LEVEL_SECRET) return NarrowStep.CannotEncryptLater
        if (was == LEVEL_SECRET) return NarrowStep.AlreadySecret
        if (to <= was) return NarrowStep.Wider
        return levels.narrow(groupId, messageId, to)
    }
}

/** Что вышло из попытки сузить. */
sealed interface NarrowStep {
    data class Narrowed(val level: Int) : NarrowStep

    /** Просили расширить. Отказ продукта, а не сервера: до сети дело не доходит. */
    data object Wider : NarrowStep

    /** Зашифрованному кругá нет: сужать нечего. */
    data object AlreadySecret : NarrowStep

    /** Открытое сообщение задним числом не шифруется. */
    data object CannotEncryptLater : NarrowStep

    /** Чужое сообщение сужает админ, а мы не он. */
    data object NotAllowed : NarrowStep

    /** Сообщения в группе нет: удалено либо мы смотрим не туда. */
    data object NotFound : NarrowStep

    data class Offline(val retryAfterMs: Long) : NarrowStep
    data class Refused(val reason: String) : NarrowStep
}

/**
 * Порт к серверу: `PATCH /groups/{id}/messages/{messageID}`.
 *
 * Правило «только сужение» проверено ДО вызова — здесь остаётся перевод ответов.
 */
fun interface MessageLevels {
    suspend fun narrow(groupId: String, messageId: Long, level: Int): NarrowStep
}
