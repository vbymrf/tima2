package io.tima.core.contacts

import io.tima.domain.chat.PhoneBookRead
import io.tima.domain.chat.PhoneBookSource

/**
 * ПК: телефонной книги нет.
 *
 * Не заглушка и не «пока не сделано»: у настольной системы нет книги, из которой можно
 * взять имена и номера так, как это делает телефон. Windows, macOS и Linux хранят
 * контакты каждый по-своему, а чаще не хранят вовсе — то, что человек называет своими
 * контактами, лежит у почтового клиента или в вебе.
 *
 * Поэтому здесь честный [PhoneBookRead.NoBook], а не пустой список: экран должен
 * сказать «добавьте вручную», а не «разрешите доступ» — разрешать нечего.
 */
private object NoPhoneBook : PhoneBookSource {
    override suspend fun read(): PhoneBookRead = PhoneBookRead.NoBook
}

actual fun platformPhoneBook(): PhoneBookSource = NoPhoneBook
