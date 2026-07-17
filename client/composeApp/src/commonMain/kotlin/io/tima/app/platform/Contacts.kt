package io.tima.app.platform

/** Контакт устройства (сырой номер до нормализации). */
class DeviceContact(val name: String, val phone: String)

/** Поддерживается ли чтение контактов (Android — да, Desktop — нет). */
expect fun contactsSupported(): Boolean

/**
 * Выдан ли доступ к контактам ПРЯМО СЕЙЧАС, без запроса разрешения. Подстановка
 * имени из книги в заголовок чата не должна сама по себе поднимать системный диалог —
 * его показывает только экран «Контакты», где пользователь этого и ждёт.
 */
expect fun contactsGranted(): Boolean

/** Читает контакты устройства (Android запрашивает READ_CONTACTS); Desktop — пусто. */
expect suspend fun readDeviceContacts(): List<DeviceContact>

/**
 * Нормализация телефона к E.164 (RU-friendly). null — не удалось привести.
 *  8XXXXXXXXXX → +7XXXXXXXXXX; 7XXXXXXXXXX → +7...; 10 цифр → +7...; уже +… — как есть.
 */
fun normalizePhone(raw: String): String? {
    val hasPlus = raw.trimStart().startsWith("+")
    val d = raw.filter { it.isDigit() }
    return when {
        hasPlus && d.length in 8..15 -> "+$d"
        d.length == 11 && d.startsWith("8") -> "+7" + d.substring(1)
        d.length == 11 && d.startsWith("7") -> "+$d"
        d.length == 10 -> "+7$d"
        else -> null
    }
}
