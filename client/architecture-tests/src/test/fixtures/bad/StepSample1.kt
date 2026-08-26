// Заведомо плохой файл: он доказывает, что три правила шага 1 действительно ловят.
//
// Лежит в src/test/fixtures/, вне наборов исходников: не компилируется и в общую
// проверку не попадает. Правило, которое ни разу не срабатывало, может быть просто
// выключено — и узнать об этом надо сразу, а не через полгода.
package плохой

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.tima.crypto.MessageSerializer

class ОбразецШага1(val драйвер: SqlDriver, val клиент: HttpClient) {
    fun разобрать(байты: ByteArray) = MessageSerializer.decodeEnvelope(байты)
    fun прочитать() = драйвер.chatsQueries
}
