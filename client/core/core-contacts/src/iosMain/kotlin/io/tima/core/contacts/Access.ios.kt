package io.tima.core.contacts

import platform.Contacts.CNContactStore
import platform.Contacts.CNAuthorizationStatus
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

/**
 * Apple: состояние спрашивается у системы, а не запоминается нами.
 *
 * `notDetermined` — диалог ещё будет. `denied` и `restricted` — не будет никогда, и
 * единственный путь лежит через настройки. `authorized` сюда не доходит: экран
 * разрешения при выданном доступе не показывается вовсе.
 */
actual fun contactsAccessWay(): ContactsAccessWay =
    when (CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts)) {
        CNAuthorizationStatus.CNAuthorizationStatusNotDetermined -> ContactsAccessWay.Ask
        CNAuthorizationStatus.CNAuthorizationStatusDenied,
        CNAuthorizationStatus.CNAuthorizationStatusRestricted,
        -> ContactsAccessWay.Settings
        else -> ContactsAccessWay.Ask
    }
