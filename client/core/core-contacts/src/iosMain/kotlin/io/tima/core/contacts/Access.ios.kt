package io.tima.core.contacts

import platform.Contacts.CNContactStore
import platform.Contacts.CNEntityType

/**
 * Apple: `requestAccessForEntityType`.
 *
 * Система сама решает, показывать диалог или ответить прежним решением: спрошенное один
 * раз второй раз не спрашивается. Нам от этого ничего не нужно знать — ответ один и тот
 * же по смыслу: доступ есть или его нет.
 */
actual fun askContactsAccess(onResult: (Boolean) -> Unit) {
    CNContactStore().requestAccessForEntityType(CNEntityType.CNEntityTypeContacts) { granted, _ ->
        onResult(granted)
    }
}
