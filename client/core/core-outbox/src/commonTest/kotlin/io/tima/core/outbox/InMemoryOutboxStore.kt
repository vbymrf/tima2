package io.tima.core.outbox

/**
 * Хранилище очереди в памяти — для проверок машины состояний.
 *
 * **Зачем оно, если есть база.** Свойства «не теряет» и «не дублирует» проверяются
 * убийством процесса в каждом состоянии. С настоящей базой такой тест превращается в
 * тест на SQLite: медленный, платформенно-зависимый и падающий по другим причинам.
 * Здесь «падение процесса» — это [restart], то есть один вызов.
 *
 * Поведение повторяет контракт [OutboxStore] буквально, включая то, что легко
 * потерять в реализации на SQL: взятие записи и перевод её в `SENDING` — одно
 * действие.
 */
class InMemoryOutboxStore : OutboxStore {

    private val rows = LinkedHashMap<String, OutboxEntry>()

    /** Сколько раз забирали запись — чтобы отличить «отправили дважды» от «взяли дважды». */
    var claims = 0
        private set

    override fun putIfAbsent(entry: OutboxEntry): Boolean {
        if (rows.containsKey(entry.clientMsgId)) return false
        rows[entry.clientMsgId] = entry
        return true
    }

    override fun byClientMsgId(clientMsgId: String): OutboxEntry? = rows[clientMsgId]

    override fun claimNext(nowMs: Long): OutboxEntry? {
        val ready = rows.values.firstOrNull {
            it.state == OutboxState.QUEUED && it.nextAttemptAtMs <= nowMs
        } ?: return null
        val claimed = ready.copy(state = OutboxState.SENDING)
        rows[claimed.clientMsgId] = claimed
        claims++
        return claimed
    }

    override fun update(entry: OutboxEntry) {
        rows[entry.clientMsgId] = entry
    }

    override fun requeueStuck(): Int {
        var n = 0
        for ((id, e) in rows.entries.map { it.key to it.value }) {
            if (e.state == OutboxState.SENDING) {
                rows[id] = e.copy(state = OutboxState.QUEUED)
                n++
            }
        }
        return n
    }

    override fun pending(): List<OutboxEntry> =
        rows.values.filter { it.state == OutboxState.QUEUED || it.state == OutboxState.SENDING }

    /**
     * Имитация убийства процесса: всё, что было в памяти сверх хранимых строк,
     * теряется, а строки остаются — ровно как с настоящей базой на диске.
     *
     * Ничего не делает со строками намеренно: суть проверки в том, что **машина**
     * обязана привести их в рабочее состояние сама, вызовом `recoverOnStart`.
     */
    fun restart(): InMemoryOutboxStore = this

    /** Для проверок: сколько записей в каком состоянии. */
    fun countBy(state: OutboxState): Int = rows.values.count { it.state == state }

    fun all(): List<OutboxEntry> = rows.values.toList()
}
