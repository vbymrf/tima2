package io.tima.app.platform

// Desktop: системной телефонной книги нет — экран контактов скрыт.
actual fun contactsSupported(): Boolean = false

actual fun contactsGranted(): Boolean = false

actual suspend fun readDeviceContacts(): List<DeviceContact> = emptyList()
