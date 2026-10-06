package io.tima.feature.auth

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
 * Подпись строки: «Имя · +799 ••• 01 · с 06.10.2026». Нет ни имени, ни номера — хвост
 * идентификатора, как раньше: различить два своих аккаунта этого хватает.
 */
fun returnLabel(userId: String, name: String, phone: String, since: String?): String {
    val who = listOfNotNull(
        name.trim().ifBlank { null },
        phone.takeIf { it.isNotBlank() }?.let(::maskPhone),
    ).ifEmpty { listOf("…" + userId.takeLast(6)) }
    return (who + listOfNotNull(since)).joinToString(" · ")
}
