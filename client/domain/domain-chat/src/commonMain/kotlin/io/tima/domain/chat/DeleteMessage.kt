package io.tima.domain.chat

/**
 * Удаление своего сообщения (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §8, §11.1): одна кнопка с выбором у
 * своего — «Удалить у себя» и «Удалить у всех».
 *
 * **У себя** — только на устройстве: строки нет, правки уходят вместе с ней; на сервере и у
 * собеседника всё как было. **У всех** — сервер ставит пометку, получившие видят «Сообщение
 * удалено»; вернуть нельзя, поэтому экран спрашивает подтверждение до вызова.
 */
class DeleteMessage(
    private val local: LocalDelete,
    private val remote: RemoteDelete,
) {

    /** Только на этом устройстве. */
    fun forMe(chatId: String, dedupKey: String, serverId: Long) = local.forMe(chatId, dedupKey, serverId)

    /** У всех. [group] — переписка групповая: маршрут другой, право шире (модератор, админ). */
    suspend fun forAll(chatId: String, serverId: Long, group: Boolean): DeleteResult {
        if (serverId <= 0) return DeleteResult.NotSent
        return when (val step = remote.delete(chatId, serverId, group)) {
            DeleteStep.Done -> {
                local.markDeleted(chatId, serverId)
                DeleteResult.Deleted
            }
            // Уже удалено — значит удалено: строка у нас должна сказать то же, что у остальных.
            DeleteStep.Gone -> {
                local.markDeleted(chatId, serverId)
                DeleteResult.Deleted
            }
            is DeleteStep.Refused -> DeleteResult.Refused(step.code)
            DeleteStep.Offline -> DeleteResult.Offline
        }
    }
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    /** Номера у сообщения ещё нет — у всех удалять нечего, только у себя. */
    data object NotSent : DeleteResult
    data class Refused(val code: String) : DeleteResult
    data object Offline : DeleteResult
}

/** Ответ сервера на удаление у всех. */
sealed interface DeleteStep {
    data object Done : DeleteStep
    /** Сообщения уже нет или оно удалено раньше (`404`). */
    data object Gone : DeleteStep
    data class Refused(val code: String) : DeleteStep
    data object Offline : DeleteStep
}

/** Порт к серверу. Реализуется `core-network`. */
fun interface RemoteDelete {
    suspend fun delete(chatId: String, serverId: Long, group: Boolean): DeleteStep
}

/** Порт к базе устройства. Реализуется `core-database`. */
interface LocalDelete {
    /** Строки нет на этом устройстве — и её правок. */
    fun forMe(chatId: String, dedupKey: String, serverId: Long)

    /** «Сообщение удалено»: содержимое стёрто, строка осталась, правки ушли. */
    fun markDeleted(chatId: String, serverId: Long)
}
