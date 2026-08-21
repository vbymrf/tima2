package io.tima.core.outbox

/**
 * Хранилище очереди в памяти — для проверок машины состояний.
 *
 * **Зачем оно, если по плану очередь живёт колонками в `messages`.** Свойства «не
 * теряет» и «не дублирует» проверяются убийством процесса в каждом состоянии. С
 * настоящей базой такой тест превращается в тест на SQLite: медленный, платформенно
 * зависимый и падающий по своим причинам. Здесь «падение процесса» — один вызов.
 *
 * Контракт [OutboxStore] повторён буквально, включая то, что легко потерять в
 * реализации на SQL: взятие записи и перевод её в `SENDING` — одно действие.
 */
class InMemoryOutboxStore : OutboxStore {

    private val rows = LinkedHashMap<String, OutboxEntry>()

    /** Сколько раз забирали запечатанное — чтобы отличить «отправили дважды» от «взяли дважды». */
    var claims = 0
        private set

    override fun putIfAbsent(entry: OutboxEntry): Boolean {
        if (rows.containsKey(entry.dedupKey)) return false
        rows[entry.dedupKey] = entry
        return true
    }

    override fun byDedupKey(dedupKey: String): OutboxEntry? = rows[dedupKey]

    override fun nextQueued(nowMs: Long): OutboxEntry? = rows.values.firstOrNull {
        it.state == OutboxState.QUEUED && it.nextAttemptAtMs <= nowMs
    }

    override fun claimSealed(): OutboxEntry? {
        val ready = rows.values.firstOrNull { it.state == OutboxState.SEALED } ?: return null
        val claimed = ready.copy(state = OutboxState.SENDING)
        rows[claimed.dedupKey] = claimed
        claims++
        return claimed
    }

    override fun update(entry: OutboxEntry) {
        rows[entry.dedupKey] = entry
    }

    override fun requeueStuck(): Int {
        var n = 0
        for ((id, e) in rows.entries.map { it.key to it.value }) {
            if (e.state == OutboxState.SENDING || e.state == OutboxState.SEALED) {
                rows[id] = e.copy(state = OutboxState.QUEUED, sealedForEpoch = null)
                n++
            }
        }
        return n
    }

    override fun pending(): List<OutboxEntry> = rows.values.filter {
        it.state == OutboxState.QUEUED ||
            it.state == OutboxState.SEALED ||
            it.state == OutboxState.SENDING
    }

    /** Для проверок: сколько записей в данном состоянии. */
    fun countBy(state: OutboxState): Int = rows.values.count { it.state == state }

    fun all(): List<OutboxEntry> = rows.values.toList()
}
