package io.tima.core.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import io.tima.domain.chat.PhoneBookEntry
import io.tima.domain.chat.PhoneBookRead
import io.tima.domain.chat.PhoneBookSource
import io.tima.domain.chat.normalizePhone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Контекст приложения для чтения книги.
 *
 * Тот же приём, что у `AndroidSecrets`: общий код про `Context` не знает и знать не
 * должен — на ПК и на Apple его нет. Приложение отдаёт контекст один раз при запуске.
 */
object AndroidContacts {

    @Volatile
    private var appContext: Context? = null

    /** Вызывается из `Application.onCreate` — до открытия вкладки «Контакты». */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    internal fun contextOrNull(): Context? = appContext
}

/**
 * Android: `ContactsContract`.
 *
 * **Дубли схлопываются здесь, а не в базе.** У одного человека в книге бывает несколько
 * номеров и несколько записей (импорт из двух аккаунтов), и все они дают одну строку на
 * номер: ключ книги — номер, и два «Бориса» с одним номером это один Борис.
 *
 * **Строки без разбираемого номера отбрасываются молча.** В книге лежат служебные
 * записи, короткие номера операторов и просто мусор; спрашивать о них человека не о
 * чем, а показать их в контактах значит показать то, чему нельзя ни написать, ни
 * позвонить.
 */
private class ContactsContractBook : PhoneBookSource {

    override suspend fun read(): PhoneBookRead = withContext(Dispatchers.IO) {
        val context = AndroidContacts.contextOrNull() ?: return@withContext PhoneBookRead.Denied
        val granted = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return@withContext PhoneBookRead.Denied

        // Карта, а не список: схлопывание дублей по номеру. Первое встреченное имя
        // выигрывает — у второй записи того же номера имя обычно хуже (импорт кладёт
        // туда сам номер).
        val byPhone = LinkedHashMap<String, PhoneBookEntry>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " ASC",
        ) ?: return@withContext PhoneBookRead.Entries(emptyList())

        cursor.use {
            val номер = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val имя = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            while (it.moveToNext()) {
                val raw = if (номер >= 0) it.getString(номер) else null
                val phone = raw?.let(::normalizePhone) ?: continue
                val name = if (имя >= 0) it.getString(имя)?.trim()?.ifBlank { null } else null
                byPhone.getOrPut(phone) { PhoneBookEntry(phone, name) }
            }
        }
        PhoneBookRead.Entries(byPhone.values.toList())
    }
}

actual fun platformPhoneBook(): PhoneBookSource = ContactsContractBook()
