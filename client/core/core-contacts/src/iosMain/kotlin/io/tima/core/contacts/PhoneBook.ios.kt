package io.tima.core.contacts

import io.tima.domain.chat.PhoneBookEntry
import io.tima.domain.chat.PhoneBookRead
import io.tima.domain.chat.PhoneBookSource
import io.tima.domain.chat.normalizePhone
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Contacts.CNContact
import platform.Contacts.CNContactFamilyNameKey
import platform.Contacts.CNContactFetchRequest
import platform.Contacts.CNContactGivenNameKey
import platform.Contacts.CNContactPhoneNumbersKey
import platform.Contacts.CNContactStore
import platform.Contacts.CNEntityType
import platform.Contacts.CNAuthorizationStatusAuthorized
import platform.Contacts.CNLabeledValue
import platform.Contacts.CNPhoneNumber

/**
 * Apple: `CNContactStore`.
 *
 * Дубли схлопываются по номеру, как и на Android: у одного человека бывает несколько
 * номеров и несколько карточек, а ключ книги — номер.
 *
 * **Разрешение только проверяется, но не запрашивается.** Спрашивает его экран, когда
 * человек открыл вкладку: диалог iOS показывается один раз за установку, и потратить
 * его на запуск приложения значит потратить впустую.
 */
@OptIn(ExperimentalForeignApi::class)
private class ContactStoreBook : PhoneBookSource {

    override suspend fun read(): PhoneBookRead {
        val status = CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts)
        if (status != CNAuthorizationStatusAuthorized) return PhoneBookRead.Denied

        val store = CNContactStore()
        val request = CNContactFetchRequest(
            keysToFetch = listOf(
                CNContactGivenNameKey,
                CNContactFamilyNameKey,
                CNContactPhoneNumbersKey,
            ),
        )
        val byPhone = LinkedHashMap<String, PhoneBookEntry>()
        store.enumerateContactsWithFetchRequest(request, null) { contact: CNContact?, _ ->
            val карточка = contact ?: return@enumerateContactsWithFetchRequest
            val имя = listOfNotNull(
                карточка.givenName.ifBlank { null },
                карточка.familyName.ifBlank { null },
            ).joinToString(" ").ifBlank { null }
            карточка.phoneNumbers.forEach { value ->
                val labeled = value as? CNLabeledValue ?: return@forEach
                val number = (labeled.value as? CNPhoneNumber)?.stringValue ?: return@forEach
                val phone = normalizePhone(number) ?: return@forEach
                byPhone.getOrPut(phone) { PhoneBookEntry(phone, имя) }
            }
        }
        return PhoneBookRead.Entries(byPhone.values.toList())
    }
}

actual fun platformPhoneBook(): PhoneBookSource = ContactStoreBook()
