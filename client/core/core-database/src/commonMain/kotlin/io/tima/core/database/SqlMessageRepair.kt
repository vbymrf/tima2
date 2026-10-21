package io.tima.core.database

/**
 * Разовые починки местной переписки, когда поломка была не здесь, а след остался здесь.
 */
class SqlMessageRepair(db: TimaDatabase) {

    private val messages = db.messagesQueries

    /**
     * Убрать двойники входящих, рождённые округлённым номером сообщения (сервер до
     * 2026-10-02 отдавал номер в живом канале через float64).
     *
     * @return сколько строк убрано.
     */
    fun dropRoundedTwins(): Long = messages.transactionWithResult {
        val before = messages.countAll().executeAsOne()
        messages.dropRoundedTwins()
        before - messages.countAll().executeAsOne()
    }

    /**
     * Убрать свои сообщения, записанные второй строкой — входящей копией рядом с исходящей того же
     * номера (беда 2026-10-21: открытая группа приносила с сервера и собственные отправленные).
     * [chatId] — только эту переписку; `null` — всю базу.
     *
     * @return сколько строк убрано.
     */
    fun dropOwnEchoes(chatId: String? = null): Long = messages.transactionWithResult {
        val before = messages.countAll().executeAsOne()
        if (chatId == null) messages.dropOwnEchoes() else messages.dropOwnEchoesIn(chatId)
        before - messages.countAll().executeAsOne()
    }
}
