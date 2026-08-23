package io.tima.core.encryption

import io.tima.crypto.PersonalChatId
import io.tima.domain.chat.PersonalChatIds

/**
 * Вычисление `chat_id` личной переписки — переходник к порту `domain-chat`.
 *
 * Идентификатор **выводится из пары участников**, а не выдаётся сервером: оба устройства
 * получают одно и то же число, ничего друг у друга не спрашивая, а сервер в назначении не
 * участвует вовсе. Порядок пары не важен — это свойство самого вычисления, и проверено оно
 * известным ответом в `messenger-crypto`.
 *
 * Переходник тонкий до неприличия, и это правильно: он существует ради границы. Domain не
 * видит `io.tima.crypto` по архитектурному правилу, иначе слой перестал бы быть слоем.
 */
object PersonalChatIdsOverKodium : PersonalChatIds {
    override fun personalChatId(a: String, b: String): String = PersonalChatId.of(a, b)
}
