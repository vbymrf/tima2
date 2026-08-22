package io.tima.core.outbox

/**
 * Хранилище входящих в памяти — для проверок машины.
 *
 * Ключ составной: идентификатор сообщения уникален **в пределах чата**, а не
 * глобально. Без chatId второе сообщение с тем же номером затёрло бы первое, и
 * потеря выглядела бы как «не пришло».
 */
class InMemoryInboxStore : InboxStore {

    private val rows = LinkedHashMap<String, IncomingEntry>()
    private val тела = LinkedHashMap<String, ByteArray>()

    /**
     * Отказ записи содержимого — для проверки «убили посреди записи».
     *
     * Раньше падение изображали лямбдой, переданной в `openNext`. Лямбды больше нет:
     * обязанность записать тело принадлежит хранилищу, значит и отказ изображает оно.
     */
    var падатьНаЗаписиТела: Boolean = false

    private fun key(chatId: String, messageId: Long) = "$chatId/$messageId"

    override fun putIfAbsent(entry: IncomingEntry): Boolean {
        if (rows.containsKey(entry.key)) return false
        rows[entry.key] = entry
        return true
    }

    override fun byKey(chatId: String, messageId: Long): IncomingEntry? = rows[key(chatId, messageId)]

    override fun nextReceived(): IncomingEntry? =
        rows.values.firstOrNull { it.state == IncomingState.RECEIVED }

    override fun undecryptable(): List<IncomingEntry> =
        rows.values.filter { it.state == IncomingState.UNDECRYPTABLE }

    override fun update(entry: IncomingEntry) {
        rows[entry.key] = entry
    }

    override fun storeBody(chatId: String, messageId: Long, body: ByteArray) {
        if (падатьНаЗаписиТела) error("диск отказал")
        тела[key(chatId, messageId)] = body
    }

    /** Записанное тело — чтобы проверка могла убедиться, что оно действительно легло. */
    fun body(chatId: String, messageId: Long): ByteArray? = тела[key(chatId, messageId)]

    override fun pending(): List<IncomingEntry> = rows.values.filter {
        it.state == IncomingState.RECEIVED || it.state == IncomingState.UNDECRYPTABLE
    }

    fun all(): List<IncomingEntry> = rows.values.toList()
}
