package io.tima.core.outbox

/**
 * Состояние исходящего сообщения — [Plan.md §3.4](../../../../../../../../doc_mig/Plan.md).
 *
 * ```
 * QUEUED → SEALED → SENDING → SENT
 *   ↑                  ↓
 *   └── отказ временный ┘        отказ по сути → DEAD
 * ```
 *
 * **Почему `SEALED` отдельно от `QUEUED`.** Запечатывание позднее: в очереди лежит
 * открытый текст (под шифрованием хранилища), а конверт собирается **перед
 * посылкой**. Так требует ADR-0016, и причина не в экономии: за время ожидания в
 * очереди — а после суток офлайна это сутки — успевает **смениться ключ эпохи
 * escrow**. Заранее запечатанный конверт унёс бы устаревший ключ, и сообщение стало
 * бы недоступно по ордеру, то есть нарушило бы ADR-0004 молча.
 *
 * **Чего здесь нет и почему.** В схеме плана есть ещё `DRAFT` и `ACKED`.
 *
 * `DRAFT` — это черновик в редакторе: он не в очереди, у него нет ни попыток, ни
 * ключа идемпотентности, и очередь про него ничего не знает. Заводить его состоянием
 * здесь значило бы отдать очереди работу редактора.
 *
 * `ACKED` — подтверждение доставки на устройство получателя. Такого признака на
 * сервере **нет**: сверка Д3 показала, что `POST /messages` отдаёт свой
 * идентификатор, а «доставлено на устройство» не отслеживается вовсе. Ввести
 * состояние, в которое нечем перейти, — значит нарисовать функцию, которой нет.
 * Появится признак на сервере — появится и состояние.
 */
enum class OutboxState {
    /** В очереди: тело есть, конверта ещё нет. */
    QUEUED,

    /**
     * Конверт собран под конкретную эпоху escrow и ждёт отправки.
     *
     * Живёт недолго и **не переживает перезапуск**: конверт держится в памяти, а не
     * на диске. Так задумано — устаревший конверт на диске хуже, чем повторное
     * запечатывание.
     */
    SEALED,

    /** Конверт отдан транспорту, ответа нет. */
    SENDING,

    /** Сервер принял. Терминальное. */
    SENT,

    /**
     * Отправка невозможна и повторять бессмысленно: конверт отвергнут по сути.
     * Терминальное — в отличие от временного отказа, который возвращает в [QUEUED].
     */
    DEAD,
}

/**
 * Запись очереди.
 *
 * @param dedupKey ключ идемпотентности, назначенный **клиентом до первой попытки** —
 *   иначе повтор после обрыва даёт дубль у собеседника. Он же уезжает в заголовке
 *   `X-Client-Msg-Id`, по нему же сервер опознаёт повтор, он же уникален в базе
 *   (инвентарь, пункт 8).
 * @param body `zstd(protobuf(MessageBody))` — те же байты, что уйдут в конверт, но
 *   пока под шифрованием хранилища. Один кодек на провод и на диск.
 * @param sealedForEpoch идентификатор ключа эпохи escrow, под который собран конверт.
 *   Смена эпохи делает конверт негодным, и по этому полю это видно.
 */
data class OutboxEntry(
    val dedupKey: String,
    val chatId: String,
    val body: ByteArray,
    val state: OutboxState = OutboxState.QUEUED,
    val attempts: Int = 0,
    val nextAttemptAtMs: Long = 0,
    val createdAtMs: Long = 0,
    val serverMessageId: Long? = null,
    val sealedForEpoch: Int? = null,
) {
    override fun equals(other: Any?): Boolean = other is OutboxEntry &&
        dedupKey == other.dedupKey && chatId == other.chatId &&
        body.contentEquals(other.body) && state == other.state &&
        attempts == other.attempts && nextAttemptAtMs == other.nextAttemptAtMs &&
        createdAtMs == other.createdAtMs && serverMessageId == other.serverMessageId &&
        sealedForEpoch == other.sealedForEpoch

    override fun hashCode(): Int {
        var h = dedupKey.hashCode()
        h = 31 * h + chatId.hashCode()
        h = 31 * h + body.contentHashCode()
        h = 31 * h + state.hashCode()
        h = 31 * h + attempts
        h = 31 * h + nextAttemptAtMs.hashCode()
        h = 31 * h + createdAtMs.hashCode()
        h = 31 * h + (serverMessageId?.hashCode() ?: 0)
        h = 31 * h + (sealedForEpoch ?: 0)
        return h
    }
}

/** Запись вместе с собранным конвертом — то, что уходит транспорту. */
data class ReadyToSend(
    val entry: OutboxEntry,
    val envelope: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is ReadyToSend &&
        entry == other.entry && envelope.contentEquals(other.envelope)

    override fun hashCode(): Int = 31 * entry.hashCode() + envelope.contentHashCode()
}

/** Чем закончилась попытка отправки. */
sealed interface SendOutcome {
    /** Сервер принял конверт. */
    data class Accepted(val serverMessageId: Long) : SendOutcome

    /**
     * Сервер уже видел этот `dedup_key` — то есть предыдущая попытка **дошла**, а
     * ответ до нас не добрался.
     *
     * Это подтверждение, а не ошибка. Без отдельного случая дедупликация на сервере
     * превратилась бы в вечный цикл повторов на клиенте.
     */
    data class Duplicate(val serverMessageId: Long) : SendOutcome

    /** Временный отказ: сеть, 5xx, таймаут. Вернётся в очередь. */
    data class Retry(val afterMs: Long = 0) : SendOutcome

    /**
     * Отказ по сути: подпись не сходится, конверт не проходит проверку сервера.
     * Повтор ничего не изменит.
     */
    data class Permanent(val reason: String) : SendOutcome
}

/**
 * Хранилище очереди. Реализуется `core-database`; в тестах — в памяти.
 *
 * По плану (§3.4.2) это **колонки в таблице `messages`, а не отдельная таблица**:
 * иначе у одного сообщения снова два источника правды, как было в v1 с `chats.json`
 * против SQLite. Интерфейс от этого не меняется — меняется только реализация.
 */
interface OutboxStore {

    /**
     * Кладёт запись, если такого `dedupKey` ещё нет.
     *
     * @return `false`, если запись уже была. **Не ошибка:** повторная постановка
     *   случается штатно, когда догон истории пересёкся с живым каналом.
     */
    fun putIfAbsent(entry: OutboxEntry): Boolean

    fun byDedupKey(dedupKey: String): OutboxEntry?

    /**
     * Следующая запись, готовая к запечатыванию: [OutboxState.QUEUED] и срок пришёл.
     * Состояние **не меняет** — переводит [Outbox].
     */
    fun nextQueued(nowMs: Long): OutboxEntry?

    /**
     * Забирает запечатанную запись и переводит её в [OutboxState.SENDING].
     *
     * Взятие и перевод — **одно действие**: между «выбрал» и «пометил» нельзя
     * оказаться, иначе два вызова возьмут одну запись и сообщение уйдёт дважды.
     */
    fun claimSealed(): OutboxEntry?

    fun update(entry: OutboxEntry)

    /**
     * Возвращает в [OutboxState.QUEUED] всё, что застряло в [OutboxState.SENDING] и
     * [OutboxState.SEALED].
     *
     * @return сколько записей вернулось.
     */
    fun requeueStuck(): Int

    /** Незавершённое — для показа очереди и для диагностики. */
    fun pending(): List<OutboxEntry>
}

/**
 * Очередь исходящих: единственное место, где живёт жизненный цикл отправки.
 *
 * **Что гарантирует** (выход этапа К3): не терять и не дублировать **ни в одном
 * состоянии**. Оба свойства держатся на устройстве, а не на аккуратности вызывающего:
 *
 * - **не теряет** — при старте всё зависшее в `SENDING` и `SEALED` возвращается в
 *   `QUEUED` ([recoverOnStart]). В v1 убитое посреди отправки сообщение оставалось в
 *   `SENDING` навсегда, то есть пропадало без следа для человека (инвентарь, пункт 7);
 * - **не дублирует** — ключ записи это `dedupKey`, и сервер по нему же опознаёт
 *   повтор. Ответ [SendOutcome.Duplicate] считается успехом.
 *
 * Машина **синхронная и без корутин** намеренно: её правильность зависит от порядка
 * переходов и от часов, а не от планировщика. Кто её крутит — дело вызывающего (К4).
 */
class Outbox(
    private val store: OutboxStore,
    private val nowMs: () -> Long,
    /**
     * Задержки повторов по числу сделанных попыток. Значения — из живых испытаний v1
     * (`LinkState.retryDelayMs`): секунда, пять, две минуты. Последняя повторяется.
     */
    private val backoffMs: List<Long> = listOf(1_000, 5_000, 120_000),
) {

    /**
     * Кэш конвертов, привязанный к эпохе. **В памяти, а не в базе** — конверт,
     * переживший перезапуск, унёс бы ключ эпохи, которой уже нет.
     *
     * Смысл кэша один: не шифровать заново на каждом повторе **внутри одной эпохи**.
     */
    private val sealedEnvelopes = HashMap<String, ByteArray>()

    /** Эпоха, под которую собран кэш. `null` — кэш пуст. */
    private var cachedEpoch: Int? = null

    fun enqueue(dedupKey: String, chatId: String, body: ByteArray): Boolean {
        require(dedupKey.isNotBlank()) { "dedupKey пустой: по нему опознаётся повтор" }
        require(body.isNotEmpty()) { "тело пустое" }
        val now = nowMs()
        return store.putIfAbsent(
            OutboxEntry(
                dedupKey = dedupKey,
                chatId = chatId,
                body = body,
                state = OutboxState.QUEUED,
                nextAttemptAtMs = now,
                createdAtMs = now,
            ),
        )
    }

    /**
     * Вызывается **при каждом запуске**, до первой попытки отправки.
     *
     * Кэш конвертов при этом пуст по определению — он в памяти, — поэтому и `SEALED`
     * возвращается в очередь: конверта для него больше нет.
     *
     * @return сколько записей вернулось. Ненулевое значит, что предыдущий запуск был
     *   прерван, и это стоит видеть в диагностике.
     */
    fun recoverOnStart(): Int {
        sealedEnvelopes.clear()
        cachedEpoch = null
        return store.requeueStuck()
    }

    /**
     * Запечатывает следующее готовое сообщение под **текущую** эпоху escrow.
     *
     * @param epochKeyId идентификатор ключа эпохи, полученный от сервера
     *   (`GET /api/v1/escrow/key`). Смена значения обнуляет кэш и возвращает
     *   запечатанное в очередь: конверт под прошлую эпоху негоден.
     * @param seal собирает конверт из тела. Здесь это `core-encryption`; очередь про
     *   криптографию ничего не знает и знать не должна.
     * @return `null`, если сейчас нечего: очередь пуста либо срок не пришёл.
     */
    fun sealNext(epochKeyId: Int, seal: (OutboxEntry) -> ByteArray): OutboxEntry? {
        if (cachedEpoch != null && cachedEpoch != epochKeyId) discardSealed()
        cachedEpoch = epochKeyId

        val entry = store.nextQueued(nowMs()) ?: return null
        val envelope = seal(entry)
        require(envelope.isNotEmpty()) { "запечатывание вернуло пустой конверт" }
        sealedEnvelopes[entry.dedupKey] = envelope
        val updated = entry.copy(state = OutboxState.SEALED, sealedForEpoch = epochKeyId)
        store.update(updated)
        return updated
    }

    /**
     * Забирает запечатанное и переводит в `SENDING`.
     *
     * @return запись вместе с конвертом, либо `null`, если запечатанного нет.
     */
    fun claimForSend(): ReadyToSend? {
        val entry = store.claimSealed() ?: return null
        val envelope = sealedEnvelopes[entry.dedupKey]
            // Конверта в кэше нет — значит эпоха сменилась или кэш очищен. Молча
            // отправлять нечего, и терять запись нельзя: возвращаем в очередь.
            ?: run {
                store.update(entry.copy(state = OutboxState.QUEUED, sealedForEpoch = null))
                return null
            }
        return ReadyToSend(entry, envelope)
    }

    /**
     * Учитывает результат попытки.
     *
     * Вызов обязателен для каждой записи, полученной из [claimForSend]: без него она
     * останется в `SENDING` до следующего [recoverOnStart]. Это страховка, а не
     * рабочий путь.
     */
    fun onOutcome(dedupKey: String, outcome: SendOutcome) {
        val entry = store.byDedupKey(dedupKey)
            ?: error("нет записи $dedupKey: результат пришёл не на своё сообщение")
        require(entry.state == OutboxState.SENDING) {
            "результат для записи в состоянии ${entry.state}, а попытка идёт только из SENDING"
        }
        val updated = when (outcome) {
            is SendOutcome.Accepted -> {
                sealedEnvelopes.remove(dedupKey)
                entry.copy(state = OutboxState.SENT, serverMessageId = outcome.serverMessageId)
            }
            // Повтор дошедшего — успех. Отличается только тем, откуда узнали.
            is SendOutcome.Duplicate -> {
                sealedEnvelopes.remove(dedupKey)
                entry.copy(state = OutboxState.SENT, serverMessageId = outcome.serverMessageId)
            }
            is SendOutcome.Retry -> entry.copy(
                // В QUEUED, а не в SEALED: пока запись ждёт, эпоха может смениться, и
                // решать это должен sealNext, а не память о прошлом конверте.
                state = OutboxState.QUEUED,
                sealedForEpoch = null,
                attempts = entry.attempts + 1,
                nextAttemptAtMs = nowMs() + delayFor(entry.attempts, outcome.afterMs),
            )
            is SendOutcome.Permanent -> {
                sealedEnvelopes.remove(dedupKey)
                entry.copy(state = OutboxState.DEAD, attempts = entry.attempts + 1)
            }
        }
        store.update(updated)
    }

    /**
     * Эпоха escrow сменилась: кэш конвертов негоден целиком.
     *
     * Вызывать при получении нового `key_id` от сервера. Отдельный метод, а не
     * побочный эффект [sealNext], потому что смена может прийти событием, когда
     * отправлять нечего.
     */
    fun onEpochChanged(newEpochKeyId: Int) {
        if (cachedEpoch == newEpochKeyId) return
        discardSealed()
        cachedEpoch = newEpochKeyId
    }

    /** Незавершённое: что человек видит как «в очереди» и «отправляется». */
    fun pending(): List<OutboxEntry> = store.pending()

    /** Сколько конвертов держится в памяти — для проверок и диагностики. */
    fun cachedEnvelopeCount(): Int = sealedEnvelopes.size

    private fun discardSealed() {
        sealedEnvelopes.clear()
        for (entry in store.pending()) {
            if (entry.state == OutboxState.SEALED) {
                store.update(entry.copy(state = OutboxState.QUEUED, sealedForEpoch = null))
            }
        }
    }

    /**
     * Задержка перед следующей попыткой.
     *
     * Подсказка сервера (`Retry-After`) сильнее нашей лестницы: он знает про свою
     * перегрузку больше нас. Ноль означает «подсказки нет».
     */
    private fun delayFor(attemptsDone: Int, hintMs: Long): Long =
        if (hintMs > 0) hintMs else backoffMs[minOf(attemptsDone, backoffMs.lastIndex)]
}
