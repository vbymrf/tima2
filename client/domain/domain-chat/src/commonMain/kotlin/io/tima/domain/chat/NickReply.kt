package io.tima.domain.chat

/**
 * `@Ник` набранным текстом — ответ на **последнее** сообщение этого человека в переписке
 * (Решения §7). Через «Ответить» — на указанное, и тогда ник ничего не меняет. Сообщений человека
 * нет — ответа нет: блок ответа ссылается на сообщение, и без него остаётся упоминание.
 */
object NickReply {

    private val MENTION = Regex("""(?:^|[\s(\[{"'«])@([\p{L}\p{N}_.]+)""")

    /** Первый `@Ник` в набранном; ника нет — `null`. */
    fun nickIn(text: String): String? = MENTION.find(text)?.groupValues?.get(1)?.trimEnd('.')?.takeIf { it.isNotEmpty() }

    /**
     * Кому и на что ответ. [people] — участники по идентификатору; [lines] — переписка, новые
     * первыми (как отдаёт база).
     */
    fun target(text: String, people: Map<String, ChatPerson>, lines: List<ChatLine>): Pair<String, Long>? {
        val nick = nickIn(text) ?: return null
        val who = people.entries.firstOrNull { (_, p) ->
            p.nick.equals(nick, ignoreCase = true) || p.userName.equals(nick, ignoreCase = true)
        }?.key ?: return null
        val last = lines.firstOrNull { it.senderId == who && !it.outgoing && it.serverId > 0 && !it.deleted } ?: return null
        return who to last.serverId
    }
}
