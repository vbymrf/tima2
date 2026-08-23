package io.tima.domain.chat

/**
 * Начать личную переписку по номеру телефона.
 *
 * **Правило продукта здесь одно, и оно важнее остального: `chat_id` личной переписки
 * ВЫВОДИТСЯ, а не выдаётся сервером.** Он считается из пары идентификаторов участников
 * (`PersonalChatId`), поэтому оба устройства получают один и тот же идентификатор, ничего
 * друг у друга не спрашивая, а сервер не участвует в его назначении вовсе. Отсюда и
 * порядок шагов: сначала найти человека, потом посчитать идентификатор, потом запомнить
 * переписку.
 *
 * **Своей переписки с собой здесь нет** — это отдельный случай (`self-chat`), и путать его
 * с личной перепиской нельзя: у него другие правила восстановления ключей.
 *
 * Вычисление идентификатора приходит **портом**: оно живёт в `messenger-crypto`, а Domain
 * криптографию не видит по архитектурному правилу — иначе слой перестал бы быть слоем.
 */
class StartPersonalChat(
    private val directory: UserDirectory,
    private val chats: ChatBook,
    private val ids: PersonalChatIds,
) {

    /**
     * @param myUserId кто я — из сессии.
     * @param phone номер собеседника в E.164.
     */
    suspend fun byPhone(myUserId: String, phone: String): StartChatResult {
        require(myUserId.isNotBlank()) { "myUserId пустой" }
        val номер = phone.trim()
        if (номер.isEmpty()) return StartChatResult.BadPhone("номер пустой")

        return when (val найден = directory.byPhone(номер)) {
            is UserLookup.Found -> запомнить(myUserId, найден, номер)
            UserLookup.NotFound -> StartChatResult.NotFound
            is UserLookup.BadPhone -> StartChatResult.BadPhone(найден.reason)
            is UserLookup.Offline -> StartChatResult.Offline(найден.retryAfterMs)
            is UserLookup.Refused -> StartChatResult.Refused(найден.reason)
        }
    }

    private fun запомнить(myUserId: String, найден: UserLookup.Found, номер: String): StartChatResult {
        if (найден.userId == myUserId) {
            // Переписка с собой — другой случай с другими правилами, и делать вид, что это
            // обычная личная, значит однажды применить к ней чужие правила.
            return StartChatResult.Myself
        }
        val chatId = ids.personalChatId(myUserId, найден.userId)
        chats.remember(
            chatId = chatId,
            kind = ChatKind.Personal,
            // Имя — то, что знает сервер, иначе номер: он человеку известен, он его и
            // вводил. Пустая строка в списке выглядела бы поломкой.
            title = найден.name?.takeIf { it.isNotBlank() } ?: номер,
            peerId = найден.userId,
        )
        return StartChatResult.Started(chatId)
    }
}

/** Чем закончилась попытка начать переписку. */
sealed interface StartChatResult {
    /** Переписка есть: её идентификатор посчитан из пары, а не выдан сервером. */
    data class Started(val chatId: String) : StartChatResult

    /** Такого номера в TIMA нет. Не отказ — предложение позвать человека. */
    data object NotFound : StartChatResult

    /** Это мой собственный номер: переписка с собой — отдельный случай. */
    data object Myself : StartChatResult
    data class BadPhone(val reason: String) : StartChatResult
    data class Offline(val retryAfterMs: Long) : StartChatResult
    data class Refused(val reason: String) : StartChatResult
}

/** Порт к справочнику: кто скрывается за номером. Реализуется `core-network`. */
fun interface UserDirectory {
    suspend fun byPhone(phone: String): UserLookup
}

/** Что вернул справочник. */
sealed interface UserLookup {
    /** @param name отображаемое имя, если сервер его знает. */
    data class Found(val userId: String, val name: String? = null) : UserLookup
    data object NotFound : UserLookup
    data class BadPhone(val reason: String) : UserLookup
    data class Offline(val retryAfterMs: Long) : UserLookup
    data class Refused(val reason: String) : UserLookup
}

/**
 * Порт к записи переписок. Реализуется `core-database`.
 *
 * Заводит имя переписке, а не саму переписку: список выводится из сообщений, и строка здесь
 * необязательна. Поэтому метод называется «запомнить», а не «создать».
 */
fun interface ChatBook {
    fun remember(chatId: String, kind: ChatKind, title: String?, peerId: String?)
}

/**
 * Порт к вычислению `chat_id` личной переписки. Реализуется `core-encryption`.
 *
 * Отдельный порт, потому что вычисление живёт в `messenger-crypto`, а Domain
 * криптографию не видит. Порядок пары внутри не важен — это свойство самого вычисления, и
 * проверено оно там же, известным ответом.
 */
fun interface PersonalChatIds {
    fun personalChatId(a: String, b: String): String
}
