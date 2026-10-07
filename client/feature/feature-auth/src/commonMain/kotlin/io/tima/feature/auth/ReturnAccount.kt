package io.tima.feature.auth

import io.tima.domain.account.AccountTitle

/**
 * Отложенный выходом аккаунт на экране входа (А6; заказчик 2026-10-06).
 *
 * Прежде строка была «Вернуть: …f8c860» — хвост идентификатора, для человека ни к чему не
 * привязанный. Теперь — имя, номер с закрытой серединой и день, с которого аккаунт на этом
 * устройстве; хвост остаётся только у записей, отложенных до этой версии.
 *
 * @property gone сервер сказал, что аккаунта больше нет (устройство отключено или аккаунт
 *   удалён) — вместо возврата кнопка «Забыть».
 * @property checking идёт проверка на сервере после нажатия «Вернуть».
 */
data class ReturnAccount(
    val userId: String,
    val label: String,
    val gone: Boolean = false,
    val checking: Boolean = false,
)

/**
 * Номер с закрытой серединой: первые 3 цифры, с кодом страны — 4 знака вместе с «+», и
 * последние 2 (заказчик 2026-10-06). «+79990000101» → «+799 ••• 01». Короткий номер
 * показывается как есть: прятать в нём нечего.
 */
fun maskPhone(phone: String): String {
    val p = phone.filter { it == '+' || it.isDigit() }
    val head = if (p.startsWith("+")) 4 else 3
    if (p.length <= head + 2) return p
    return p.take(head) + " ••• " + p.takeLast(2)
}

/**
 * Подпись строки: «Имя · +799 ••• 01 · с 06.10.2026». Первое — подпись аккаунта, одна на всё
 * приложение ([AccountTitle], заказчик 2026-10-07): имя, без имени — @ник, без ника — служебное имя.
 */
fun returnLabel(userId: String, name: String, phone: String, since: String?, nickname: String = ""): String {
    val who = listOfNotNull(
        AccountTitle.of(name, nickname, userId),
        phone.takeIf { it.isNotBlank() }?.let(::maskPhone),
    )
    return (who + listOfNotNull(since)).joinToString(" · ")
}

/**
 * Вторая строка подписи аккаунта (заказчик 2026-10-07): @ник и номер с закрытой серединой; у
 * виртуального вместо номера — «виртуальный». Ник не повторяется, если он уже стоит первой строкой.
 */
fun accountDetail(name: String, nickname: String, phone: String, virtual: Boolean, virtualWord: String): String {
    val nick = nickname.trim().trimStart('@').takeIf { it.isNotBlank() && name.isNotBlank() }?.let { "@$it" }
    val tail = if (virtual) virtualWord else phone.takeIf { it.isNotBlank() }?.let(::maskPhone)
    return listOfNotNull(nick, tail).joinToString(" · ")
}
