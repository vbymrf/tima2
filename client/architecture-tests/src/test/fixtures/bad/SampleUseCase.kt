// ОБРАЗЕЦ НАРУШЕНИЯ. Не компилируется и не участвует в сборке: лежит вне наборов
// исходников. Нужен, чтобы доказать, что проверка правил действительно ловит, —
// правило, которое ни разу не срабатывало, может быть просто выключено.
package io.tima.domain.sample

import io.ktor.client.HttpClient

class SampleUseCase(private val client: HttpClient)
