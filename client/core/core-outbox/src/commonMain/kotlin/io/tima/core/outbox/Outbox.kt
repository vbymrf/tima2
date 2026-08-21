package io.tima.core.outbox

/**
 * Состояние исходящего сообщения.
 *
 * Четыре, а не два: «отправляется» и «не отправилось» — разные вещи, и путь из них
 * тоже разный. Именно отсутствие отдельного `SENDING` в v1 приводило к тому, что
 * убитое посреди отправки сообщение оставалось в нём навсегда (инвентарь, пункт 7).
 */
enum class OutboxState {
    /** Лежит в очереди, ждёт своей попытки. */
    QUEUED,

    /** Попытка идёт: конверт отдан транспорту, ответа нет. */
    SENDING,

    /** Сервер принял. Терминальное. */
    SENT,

    /**
     * Отправка невозможна и повторять бессмысленно: сервер отверг конверт по сути.
     * Терминальное — в отличие от временного отказа, который возвращает в [QUEUED].
     */
    FAILED,
}

/**
 * Запись очереди.
 *
 * @param clientMsgId **свой** идентификатор, назначенный при постановке в очередь.
 *   Он же ключ уникальности и он же уезжает в заголовке `X-Client-Msg-Id`. Именно он
 *   превращает повторную отправку в обновление, а не в дубль (инвентарь, пункт 8).
 * @param envelope готовые байты конверта из `core-encryption`. Очередь их не
 *   разбирает и не пересобирает: сообщение подписано, и любая пересборка ломает
 *   подпись.
 */
data class OutboxEntry(
    val clientMsgId: String,
    val chatId: String,
    val envelope: ByteArray,
    val state: OutboxState = OutboxState.QUEUED,
    val attempts: Int = 0,
    /** Раньше этого момента попытку не делать. */
    val nextAttemptAtMs: Long = 0,
    val createdAtMs: Long = 0,
    /** Идентификатор, присвоенный сервером; заполняется при переходе в [OutboxState.SENT]. */
    val serverMessageId: Long? = null,
) {
    override fun equals(other: Any?): Boolean = other is OutboxEntry &&
        clientMsgId == other.clientMsgId &&
        chatId == other.chatId &&
        envelope.contentEquals(other.envelope) &&
        state == other.state &&
        attempts == other.attempts &&
        nextAttemptAtMs == other.nextAttemptAtMs &&
        createdAtMs == other.createdAtMs &&
        serverMessageId == other.serverMessageId

    override fun hashCode(): Int {
        var h = clientMsgId.hashCode()
        h = 31 * h + chatId.hashCode()
        h = 31 * h + envelope.contentHashCode()
        h = 31 * h + state.hashCode()
        h = 31 * h + attempts
        h = 31 * h + nextAttemptAtMs.hashCode()
        h = 31 * h + createdAtMs.hashCode()
        h = 31 * h + (serverMessageId?.hashCode() ?: 0)
        return h
    }
}

/** Чем закончилась попытка отправки. */
sealed interface SendOutcome {
    /** Сервер принял конверт. */
    data class Accepted(val serverMessageId: Long) : SendOutcome

    /**
     * Сервер уже видел этот `client_msg_id` — то есть предыдущая попытка **дошла**,
     * а ответ до нас не добрался.
     *
     * Это не ошибка и не повод повторять: это подтверждение. Без отдельного случая
     * дедупликация на сервере превратилась бы в вечный цикл повторов на клиенте.
     */
    data class Duplicate(val serverMessageId: Long) : SendOutcome

    /** Временный отказ: сеть, 5xx, таймаут. Вернётся в очередь. */
    data class Retry(val afterMs: Long) : SendOutcome

    /**
     * Отказ по сути: конверт не проходит проверку сервера, подпись не сходится,
     * ключа эпохи больше нет. Повтор ничего не изменит.
     */
    data class Permanent(val reason: String) : SendOutcome
}

/**
 * Хранилище очереди. Реализуется `core-database`; в тестах — в памяти.
 *
 * **Почему интерфейс, а не сразу SQL.** Правильность машины состояний — «не теряет и
 * не дублирует ни в одном состоянии» — проверяется без базы, и проверяться должна
 * без неё: иначе тест на падение посреди отправки превращается в тест на SQLite.
 */
interface OutboxStore {

    /**
     * Кладёт запись, если такого `clientMsgId` ещё нет.
     *
     * @return `false`, если запись уже была. **Не ошибка:** повторная постановка
     *   случается штатно, когда интерфейс отправил то же сообщение дважды или когда
     *   догон истории пересёкся с живым каналом.
     */
    fun putIfAbsent(entry: OutboxEntry): Boolean

    /** Запись по своему идентификатору. */
    fun byClientMsgId(clientMsgId: String): OutboxEntry?

    /**
     * Забирает следующую готовую запись и переводит её в [OutboxState.SENDING].
     *
     * Взятие и перевод — **одно действие**: между «выбрал» и «пометил» нельзя
     * оказаться, иначе два вызова возьмут одну запись и сообщение уйдёт дважды.
     */
    fun claimNext(nowMs: Long): OutboxEntry?

    fun update(entry: OutboxEntry)

    /**
     * Возвращает всё зависшее в [OutboxState.SENDING] обратно в [OutboxState.QUEUED].
     *
     * @return сколько записей вернулось.
     */
    fun requeueStuck(): Int

    /** Записи в незавершённых состояниях — для диагностики и для показа очереди. */
    fun pending(): List<OutboxEntry>
}

/**
 * Очередь исходящих: единственное место, где живёт жизненный цикл отправки.
 *
 * **Что она обязана гарантировать** (выход этапа К3): не терять и не дублировать **ни
 * в одном состоянии**. Оба свойства держатся не на аккуратности вызывающего, а на
 * устройстве:
 *
 * - **не теряет** — потому что при старте всё зависшее в `SENDING` возвращается в
 *   `QUEUED` ([recoverOnStart]). Приложение убили посреди отправки — сообщение
 *   уйдёт после перезапуска. В v1 такое сообщение оставалось в `SENDING` навсегда,
 *   то есть пропадало без следа для человека;
 * - **не дублирует** — потому что ключ записи это `clientMsgId`, и сервер по нему же
 *   опознаёт повтор. Ответ [SendOutcome.Duplicate] считается успехом, а не поводом
 *   повторять.
 *
 * Машина **синхронная и без корутин** намеренно: её правильность зависит от порядка
 * переходов и от часов, а не от планировщика. Кто и когда её крутит — дело
 * вызывающего (К4).
 */
class Outbox(
    private val store: OutboxStore,
    private val nowMs: () -> Long,
    /**
     * Задержки повторов по номеру попытки. Значения — из живых испытаний v1
     * (`LinkState.retryDelayMs`): секунда, пять, две минуты. Последняя повторяется.
     */
    private val backoffMs: List<Long> = listOf(1_000, 5_000, 120_000),
) {

    /**
     * Ставит сообщение в очередь.
     *
     * @return `true`, если поставлено; `false`, если такое уже есть. Повторный вызов
     *   с тем же [clientMsgId] **ничего не меняет** — в том числе не сбрасывает
     *   счётчик попыток и не двигает время следующей.
     */
    fun enqueue(clientMsgId: String, chatId: String, envelope: ByteArray): Boolean {
        require(clientMsgId.isNotBlank()) { "clientMsgId пустой: по нему опознаётся повтор" }
        require(envelope.isNotEmpty()) { "конверт пустой" }
        val now = nowMs()
        return store.putIfAbsent(
            OutboxEntry(
                clientMsgId = clientMsgId,
                chatId = chatId,
                envelope = envelope,
                state = OutboxState.QUEUED,
                nextAttemptAtMs = now,
                createdAtMs = now,
            ),
        )
    }

    /**
     * Вызывается **при каждом запуске** приложения, до первой попытки отправки.
     *
     * @return сколько записей вернулось из `SENDING` в очередь. Ненулевое значение
     *   означает, что предыдущий запуск был прерван — это стоит видеть в диагностике.
     */
    fun recoverOnStart(): Int = store.requeueStuck()

    /**
     * Следующее к отправке, уже переведённое в `SENDING`.
     *
     * `null` означает «сейчас нечего»: очередь пуста либо время следующей попытки
     * ещё не пришло.
     */
    fun next(): OutboxEntry? = store.claimNext(nowMs())

    /**
     * Учитывает результат попытки.
     *
     * Вызов обязателен для каждой записи, полученной из [next]: без него она
     * останется в `SENDING` до следующего [recoverOnStart]. Это не утечка, а
     * страховка — но лучше не проверять её каждый день.
     */
    fun onOutcome(clientMsgId: String, outcome: SendOutcome) {
        val entry = store.byClientMsgId(clientMsgId)
            ?: error("нет записи $clientMsgId: результат пришёл не на своё сообщение")
        require(entry.state == OutboxState.SENDING) {
            "результат для записи в состоянии ${entry.state}, а попытка идёт только из SENDING"
        }
        val updated = when (outcome) {
            is SendOutcome.Accepted -> entry.copy(
                state = OutboxState.SENT,
                serverMessageId = outcome.serverMessageId,
            )
            // Повтор дошедшего — это успех. Отличается только тем, откуда узнали.
            is SendOutcome.Duplicate -> entry.copy(
                state = OutboxState.SENT,
                serverMessageId = outcome.serverMessageId,
            )
            is SendOutcome.Retry -> entry.copy(
                state = OutboxState.QUEUED,
                attempts = entry.attempts + 1,
                nextAttemptAtMs = nowMs() + delayFor(entry.attempts, outcome.afterMs),
            )
            is SendOutcome.Permanent -> entry.copy(
                state = OutboxState.FAILED,
                attempts = entry.attempts + 1,
            )
        }
        store.update(updated)
    }

    /** Незавершённое: что человек видит как «отправляется» и «в очереди». */
    fun pending(): List<OutboxEntry> = store.pending()

    /**
     * Задержка перед следующей попыткой.
     *
     * Подсказка сервера (`Retry-After`) сильнее нашей лестницы: он знает про свою
     * перегрузку больше нас. Ноль означает «подсказки нет».
     */
    private fun delayFor(attemptsDone: Int, hintMs: Long): Long =
        if (hintMs > 0) hintMs else backoffMs[minOf(attemptsDone, backoffMs.lastIndex)]
}
