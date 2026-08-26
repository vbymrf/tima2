package io.tima.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.tima.core.outbox.FieldCipher
import io.tima.domain.chat.Contact
import io.tima.domain.chat.ContactBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Контакты из местной базы — переходник к порту `domain-chat`.
 *
 * **Имя расшифровывается здесь**, потому что оно лежит под ключом покоя: имя собеседника
 * — это содержимое переписки, а не метаданные, и сервер его не видит. Не открылось —
 * строка остаётся без имени, но остаётся: человек, с которым есть переписка, не должен
 * исчезать из списка из-за одной испорченной записи.
 *
 * **Порядок задаётся здесь, а не запросом.** Сортировать в SQL нечем: имя в базе
 * зашифровано, и `ORDER BY title_enc` дал бы порядок по шифртексту — то есть случайный,
 * меняющийся при каждой перезаписи.
 */
class SqlContacts(
    private val db: TimaDatabase,
    private val cipher: FieldCipher,
) : ContactBook {

    override fun list(): Flow<List<Contact>> =
        db.chatsQueries.personalContacts()
            // Поток от самой базы:новая переписка появляется в контактах сама, без опроса.
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { lines ->
                lines.map { line ->
                    Contact(
                        userId = line.peer_id.orEmpty(),
                        chatId = line.chat_id,
                        name = line.title_enc?.let { cipher.open(it) }?.decodeToString(),
                    )
                }.sortedWith(
                    // Безымянные — в конец: у них нечего читать глазами, и держать их
                    // среди названных значит мешать поиску взглядом.
                    compareBy({ it.name == null }, { it.name ?: it.userId }),
                )
            }
}
