package io.tima.core.outbox

/**
 * Состояние входящего сообщения — [Plan.md §3.4](../../../../../../../../doc_mig/Plan.md).
 *
 * ```
 * RECEIVED → STORED → READ
 *     └→ UNDECRYPTABLE (ключа нет; не теряем, помечаем)
 * ```
 *
 * **Почему `DECRYPTED` не отдельное состояние, хотя в схеме плана оно есть.**
 * Состояние — это то, в чём можно **застать** запись после падения процесса.
 * Расшифровка и запись содержимого идут одной транзакцией: упал после расшифровки,
 * но до записи — застанешь `RECEIVED`, а не `DECRYPTED`. Заводить состояние, в
 * котором нельзя оказаться, значит рисовать в схеме то, чего в жизни не бывает, и
 * потом писать для него ветку восстановления.
 *
 * **`UNDECRYPTABLE` — состояние, а не потеря.** Ключ может приехать позже: своё
 * устройство ещё не получило обёртку, групповой ключ ротировался, история пришла
 * раньше ключей. Сообщение остаётся видимым как «нечитаемое» и расшифровывается,
 * когда ключ появился. Выбросить его было бы потерей переписки без следа.
 */
enum class IncomingState {
    /** Конверт принят и записан; содержимое ещё не разобрано. */
    RECEIVED,

    /**
     * Ключа для расшифровки нет. **Не терминальное:** попытка повторяется, когда
     * появляется ключ.
     */
    UNDECRYPTABLE,

    /** Содержимое разобрано и лежит в базе. */
    STORED,

    /** Человек прочитал. Терминальное. */
    READ,
}

/**
 * Запись входящего.
 *
 * @param chatId и [messageId] вместе образуют ключ уникальности. Идентификатор
 *   назначает **отправитель**, и он входит в подпись — то есть подделать его нельзя,
 *   а значит по нему можно опознавать повтор. Это тот самый приём из v1 (инвентарь,
 *   пункт 8): одно и то же сообщение, пришедшее по живому каналу и потом в догоне
 *   истории, даёт одну строку.
 * @param envelope принятые байты конверта. Хранятся до расшифровки: если разобрать
 *   не удалось, второй попытке нужен исходник.
 * @param attempts сколько раз пытались расшифровать. Растёт только при неудаче.
 */
data class IncomingEntry(
    val chatId: String,
    val messageId: Long,
    val envelope: ByteArray,
    val state: IncomingState = IncomingState.RECEIVED,
    val attempts: Int = 0,
    val receivedAtMs: Long = 0,
    /** Почему не расшифровалось — для диагностики и для показа человеку. */
    val undecryptableReason: String? = null,
) {
    val key: String get() = "$chatId/$messageId"

    override fun equals(other: Any?): Boolean = other is IncomingEntry &&
        chatId == other.chatId && messageId == other.messageId &&
        envelope.contentEquals(other.envelope) && state == other.state &&
        attempts == other.attempts && receivedAtMs == other.receivedAtMs &&
        undecryptableReason == other.undecryptableReason

    override fun hashCode(): Int {
        var h = chatId.hashCode()
        h = 31 * h + messageId.hashCode()
        h = 31 * h + envelope.contentHashCode()
        h = 31 * h + state.hashCode()
        h = 31 * h + attempts
        h = 31 * h + receivedAtMs.hashCode()
        h = 31 * h + (undecryptableReason?.hashCode() ?: 0)
        return h
    }
}

/** Чем закончилась попытка разобрать конверт. */
sealed interface OpenOutcome {
    /**
     * Разобрано. [body] — те же байты, что лягут в `body_enc` под локальным ключом:
     * один кодек на провод и на диск.
     */
    data class Opened(val body: ByteArray) : OpenOutcome

    /**
     * Ключа нет — попробуем позже. Не ошибка данных: обёртка для этого устройства
     * могла ещё не прийти.
     */
    data class NoKey(val reason: String) : OpenOutcome

    /**
     * Конверт негоден по сути: подпись не сошлась, обязательство по ключу указывает
     * на другой ключ. Повтор не поможет, и **это возможная подмена**, а не поломка.
     */
    data class Rejected(val reason: String) : OpenOutcome
}

/** Хранилище входящих. Реализуется `core-database` поверх той же таблицы `messages`. */
interface InboxStore {

    /** @return `false`, если такое сообщение уже принято. Не ошибка: повтор штатен. */
    fun putIfAbsent(entry: IncomingEntry): Boolean

    fun byKey(chatId: String, messageId: Long): IncomingEntry?

    /** Следующее, что нужно разобрать: [IncomingState.RECEIVED]. */
    fun nextReceived(): IncomingEntry?

    /** Всё, что не расшифровалось: для повтора, когда появился ключ. */
    fun undecryptable(): List<IncomingEntry>

    fun update(entry: IncomingEntry)

    /** Незавершённое — что видно как «принято» и «нечитаемое». */
    fun pending(): List<IncomingEntry>
}

/**
 * Приём входящих: конверт записывается **до** попытки разбора.
 *
 * Порядок именно такой, и это главное свойство машины. Разбор может упасть по любой
 * причине — нет ключа, повреждённые байты, ошибка в нашем коде, — и если сначала
 * разбирать, а записывать потом, то каждое такое падение теряет сообщение
 * безвозвратно: живой канал его больше не пришлёт.
 *
 * Машина синхронная и без корутин — по той же причине, что [Outbox]: её правильность
 * зависит от порядка переходов, а не от планировщика.
 */
class Inbox(
    private val store: InboxStore,
    private val nowMs: () -> Long,
) {

    /**
     * Принимает конверт. Идемпотентно: повтор того же сообщения ничего не меняет.
     *
     * @return `true`, если принято впервые.
     */
    fun receive(chatId: String, messageId: Long, envelope: ByteArray): Boolean {
        require(chatId.isNotBlank()) { "chatId пустой" }
        require(messageId > 0) { "messageId должен быть положительным: получено $messageId" }
        require(envelope.isNotEmpty()) { "конверт пустой" }
        return store.putIfAbsent(
            IncomingEntry(
                chatId = chatId,
                messageId = messageId,
                envelope = envelope,
                state = IncomingState.RECEIVED,
                receivedAtMs = nowMs(),
            ),
        )
    }

    /**
     * Разбирает следующее принятое.
     *
     * @param open расшифровка — это `core-encryption`; очередь про криптографию
     *   ничего не знает.
     * @param persist куда лечь разобранному содержимому. Вызывается **до** смены
     *   состояния: если запись содержимого упала, сообщение останется `RECEIVED` и
     *   будет разобрано снова.
     * @return запись после перехода, либо `null`, если разбирать нечего.
     */
    fun openNext(
        open: (IncomingEntry) -> OpenOutcome,
        persist: (IncomingEntry, ByteArray) -> Unit,
    ): IncomingEntry? {
        val entry = store.nextReceived() ?: return null
        val updated = when (val outcome = open(entry)) {
            is OpenOutcome.Opened -> {
                persist(entry, outcome.body)
                entry.copy(state = IncomingState.STORED, undecryptableReason = null)
            }
            is OpenOutcome.NoKey -> entry.copy(
                state = IncomingState.UNDECRYPTABLE,
                attempts = entry.attempts + 1,
                undecryptableReason = outcome.reason,
            )
            // Отвергнутый конверт остаётся нечитаемым, а не исчезает: человек должен
            // видеть, что сообщение было, и что оно не прошло проверку. Молчаливое
            // исчезновение — худший вариант из всех: подмена становится незаметной.
            is OpenOutcome.Rejected -> entry.copy(
                state = IncomingState.UNDECRYPTABLE,
                attempts = entry.attempts + 1,
                undecryptableReason = outcome.reason,
            )
        }
        store.update(updated)
        return updated
    }

    /**
     * Повторяет разбор всего нечитаемого — вызывается, когда появился ключ.
     *
     * @return сколько записей вернулось в [IncomingState.RECEIVED]. Ноль означает, что
     *   ждать было нечего.
     */
    fun retryUndecryptable(): Int {
        var n = 0
        for (entry in store.undecryptable()) {
            store.update(entry.copy(state = IncomingState.RECEIVED))
            n++
        }
        return n
    }

    /** Человек прочитал. */
    fun markRead(chatId: String, messageId: Long) {
        val entry = store.byKey(chatId, messageId)
            ?: error("нет входящего $chatId/$messageId")
        require(entry.state == IncomingState.STORED) {
            "прочитанным становится разобранное, а состояние ${entry.state}"
        }
        store.update(entry.copy(state = IncomingState.READ))
    }

    fun pending(): List<IncomingEntry> = store.pending()
}
