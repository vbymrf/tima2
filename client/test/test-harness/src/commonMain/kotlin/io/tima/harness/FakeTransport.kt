package io.tima.harness

import io.tima.core.outbox.ReadyToSend
import io.tima.core.outbox.SendOutcome

/**
 * Фейковый транспорт — К4.6.
 *
 * **Что он изображает и что проверяет.** Не сеть, а **поведение сервера в ответ на
 * повторы**: у настоящего сервера есть дедупликация по `client_msg_id`, и без её
 * подобия сценарий «обрыв — повтор» проверял бы половину пути. Здесь повтор
 * дошедшего сообщения честно получает [SendOutcome.Duplicate] с тем же
 * идентификатором, как на живом сервере.
 *
 * Сценарий задаётся списком поведений: сколько попыток и чем каждая кончается. Так
 * проверка не зависит ни от часов, ни от планировщика — иначе тест был бы то зелёным,
 * то красным без изменения кода.
 */
class FakeTransport(
    /**
     * Что делать на каждой попытке, по порядку. Когда список кончился, транспорт
     * принимает: «сеть починилась» — обычный конец любого сценария с обрывом.
     */
    private val script: MutableList<Behaviour> = mutableListOf(),
) {

    /** Что транспорт сделает на очередной попытке. */
    sealed interface Behaviour {
        /** Обрыв связи: та же беда, что таймаут и отсутствие сети. */
        data class Offline(val retryAfterMs: Long = 1_000) : Behaviour

        /** Сервер жив, но отвечает 5xx. */
        data object ServerError : Behaviour

        /** Ограничитель частоты. */
        data class RateLimited(val retryAfterMs: Long) : Behaviour

        /** Конверт негоден по сути: подпись, размер, чужой отправитель. */
        data class Rejected(val reason: String) : Behaviour

        /** Приём. */
        data object Accept : Behaviour

        /**
         * Пропажа ответа: сервер **принял**, но ответ до клиента не дошёл.
         *
         * Самый неприятный случай и главная причина, по которой `dedup_key`
         * назначается клиентом: сообщение уже доставлено, а клиент об этом не знает и
         * обязан повторить — не создав второго.
         */
        data object AcceptButLoseAnswer : Behaviour
    }

    /** Что транспорт видел: по одной записи на попытку. */
    val attempts = mutableListOf<Attempt>()

    data class Attempt(val dedupKey: String, val envelope: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Attempt &&
            dedupKey == other.dedupKey && envelope.contentEquals(other.envelope)
        override fun hashCode(): Int = 31 * dedupKey.hashCode() + envelope.contentHashCode()
    }

    /** Что «сервер» принял: `dedup_key` → присвоенный идентификатор. */
    val accepted = mutableMapOf<String, Long>()

    private var nextId = 1L

    /** Добавить поведение в конец сценария. */
    fun then(behaviour: Behaviour): FakeTransport {
        script += behaviour
        return this
    }

    /** Одна попытка отправки — то, что подставляется в насос очереди. */
    suspend fun send(ready: ReadyToSend): SendOutcome {
        val key = ready.entry.dedupKey
        attempts += Attempt(key, ready.envelope)

        return when (val behaviour = script.removeFirstOrNull() ?: Behaviour.Accept) {
            is Behaviour.Offline -> SendOutcome.Retry(behaviour.retryAfterMs)
            Behaviour.ServerError -> SendOutcome.Retry()
            is Behaviour.RateLimited -> SendOutcome.Retry(behaviour.retryAfterMs)
            is Behaviour.Rejected -> SendOutcome.Permanent(behaviour.reason)

            Behaviour.Accept -> accepted[key]
                // Повтор уже дошедшего: именно так отвечает сервер, дедуплицируя по
                // client_msg_id. Считать это ошибкой значило бы повторять вечно.
                ?.let { SendOutcome.Duplicate(it) }
                ?: SendOutcome.Accepted(nextId++.also { accepted[key] = it })

            Behaviour.AcceptButLoseAnswer -> {
                // Сервер принял — запись об этом остаётся, — а клиент ответа не увидел.
                if (key !in accepted) accepted[key] = nextId++
                SendOutcome.Retry(0)
            }
        }
    }

    /** Сколько разных сообщений «доставлено». Дубли здесь были бы видны сразу. */
    fun deliveredCount(): Int = accepted.size
}
