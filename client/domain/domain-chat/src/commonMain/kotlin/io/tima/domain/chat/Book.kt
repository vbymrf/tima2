package io.tima.domain.chat

import kotlinx.coroutines.flow.Flow

/**
 * Книга контактов — ПЛАН-КОНТАКТОВ.md, Д2.
 *
 * **Своя, а не зеркало телефонной.** Приложение читает книгу устройства, но держит
 * собственный список: иначе не выполняются два обещания — удалённый не возвращается при
 * следующей синхронизации, а заведённый вручную не исчезает оттого, что в телефоне его
 * нет.
 *
 * **Отсюда же отменяется прежнее решение** «адресную книгу телефона мы не читаем»
 * (см. [ObserveContacts]): оно принималось, когда чтения не было вовсе. Заказчик отменил
 * его 2026-09-05, и вместе с чтением появилось обещание «сверяем, не читая» — на сервер
 * уходит слепой индекс номера, а не номер.
 */
class ObserveBook(private val book: Book) {

    /** Вся книга, кроме убранного. Порядок и разбиение по разделам — дело экрана. */
    fun list(): Flow<List<BookEntry>> = book.list()

    /** Разделы, включая пустые: раздел существует до того, как в него кого-то положили. */
    fun sections(): Flow<List<String>> = book.sections()
}

/**
 * Строка книги.
 *
 * **Два имени, а не одно** (решение заказчика 2026-09-05). Одно поле означало бы, что
 * правка ложится поверх прочитанного из телефона, и следующая синхронизация обязана
 * выбрать: затереть правку или навсегда перестать замечать, что имя в телефоне
 * изменилось. Оба ответа плохие.
 *
 * @param phone номер в E.164 — ключ строки: имя меняется, [userId] появляется позже,
 *   а номер и есть то, чем человек назван.
 * @param namePhone имя из телефонной книги; перезаписывается синхронизацией.
 * @param nameOwn имя, записанное человеком; не затирается никем.
 * @param userId заполняется после сверки; `null` — в TIMa его нет либо ещё не сверяли.
 * @param manual заведён вручную: такой не пропадает, когда его нет в телефоне.
 */
data class BookEntry(
    val phone: String,
    val namePhone: String? = null,
    val nameOwn: String? = null,
    val section: String = "",
    val userId: String? = null,
    val manual: Boolean = false,
) {
    /**
     * Имя для показа: своё перебивает книжное.
     *
     * Возвращает `null`, когда имени нет вовсе, — тогда строку называет номер. Пустой
     * строки здесь не бывает: пустое имя и отсутствующее для экрана одно и то же, и
     * различать их значило бы показывать пустоту там, где ждут имя.
     */
    val name: String? get() = nameOwn?.ifBlank { null } ?: namePhone?.ifBlank { null }

    /** Есть в TIMa: можно написать и позвонить внутри приложения. */
    val inTima: Boolean get() = !userId.isNullOrBlank()
}

/** Порт книги. Реализуется `core-database`. */
interface Book {
    fun list(): Flow<List<BookEntry>>
    fun sections(): Flow<List<String>>

    /**
     * Положить прочитанное из телефонной книги.
     *
     * Своего имени не трогает, раздела не трогает и **убранного не воскрешает**: без
     * последнего удаление выглядело бы неработающим — человек убрал, а синхронизация
     * вернула.
     */
    suspend fun fromPhoneBook(entries: List<PhoneBookEntry>)

    /** Завести руками. Номер обязателен, остальное — нет. */
    suspend fun addManually(phone: String, name: String?, section: String)

    suspend fun rename(phone: String, name: String?)
    suspend fun moveTo(phone: String, section: String)
    suspend fun hide(phone: String)

    /** Итог сверки: чей номер нашёлся в TIMa. Не найденные приходят с `null`. */
    suspend fun matched(found: Map<String, String?>)

    suspend fun addSection(name: String)

    /** Убрать раздел. Люди из него возвращаются в общий, а не исчезают вместе с ним. */
    suspend fun removeSection(name: String)
}

/** Что прочитано с телефона: только имя и номер, больше ничего нам не нужно. */
data class PhoneBookEntry(val phone: String, val name: String?)

/**
 * Нормализация номера в E.164 — до всего остального.
 *
 * «8 916 …», «+7 (916) …» и «916…» — один человек. Разошедшиеся написания дали бы дубли
 * в книге и промахи сверки: сервер сравнивает слепые индексы, а они у разных написаний
 * разные.
 *
 * **Страна по умолчанию — параметр, а не константа.** Приложение работает не только в
 * России, и зашитая «+7» превратила бы чужой местный номер в чужой российский.
 *
 * @return номер вида `+79160001122` или `null`, если из строки номера не выходит.
 */
fun normalizePhone(raw: String, defaultCode: String = "+7"): String? {
    val digits = raw.filter { it.isDigit() }
    if (digits.isEmpty()) return null
    val plus = raw.trimStart().startsWith('+')
    return when {
        // Уже международный: как записан, так и берём.
        plus && digits.length in 8..15 -> "+$digits"
        // Российское «8 916…» — та же длина, что «+7 916…», но первая цифра своя.
        !plus && digits.length == 11 && digits.startsWith("8") -> "${defaultCode}${digits.drop(1)}"
        !plus && digits.length == 11 && digits.startsWith(defaultCode.drop(1)) -> "+$digits"
        // Местный без кода страны.
        !plus && digits.length == 10 -> "$defaultCode$digits"
        else -> null
    }
}
