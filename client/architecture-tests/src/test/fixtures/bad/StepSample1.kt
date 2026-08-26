// Заведомо плохой файл: он доказывает, что три правила шага 1 действительно ловят.
//
// Лежит в src/test/fixtures/, вне наборов исходников: не компилируется и в общую
// проверку не попадает. Правило, которое ни разу не срабатывало, может быть просто
// выключено — и узнать об этом надо сразу, а не через полгода.
package bad

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.tima.crypto.MessageSerializer

class StepSample1(val driver: SqlDriver, val client: HttpClient) {
    fun parse(bytes: ByteArray) = MessageSerializer.decodeEnvelope(bytes)
    fun read() = driver.chatsQueries
}
