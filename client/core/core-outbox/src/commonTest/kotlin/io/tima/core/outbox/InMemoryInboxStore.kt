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
    private val авторы = LinkedHashMap<String, String>()

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

    override fun storeParsed(chatId: String, messageId: Long, body: ByteArray, senderId: String) {
        if (падатьНаЗаписиТела) error("диск отказал")
        тела[key(chatId, messageId)] = body
        авторы[key(chatId, messageId)] = senderId
    }

    /** Записанный автор — проверка обязана видеть, что он вообще записан. */
    fun author(chatId: String, messageId: Long): String? = авторы[key(chatId, messageId)]

    /** Записанное тело — чтобы проверка могла убедиться, что оно действительно легло. */
    fun body(chatId: String, messageId: Long): ByteArray? = тела[key(chatId, messageId)]

    override fun markChatRead(chatId: String): Int {
        val разобранные = rows.values.filter {
            it.chatId == chatId && it.state == IncomingState.STORED
        }
        for (запись in разобранные) rows[запись.key] = запись.copy(state = IncomingState.READ)
        return разобранные.size
    }

    override fun pending(): List<IncomingEntry> = rows.values.filter {
        it.state == IncomingState.RECEIVED || it.state == IncomingState.UNDECRYPTABLE
    }

    fun all(): List<IncomingEntry> = rows.values.toList()
}
