package io.tima.domain.chat

import kotlinx.coroutines.flow.Flow

/**
 * Журнал уведомлений — ПЛАН-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ1.
 *
 * ── ЗАЧЕМ ТАБЛИЦА, А НЕ СЧЁТЧИК ─────────────────────────────────────────────
 *
 * До журнала числа на вкладках и окнах считались каждое своим путём: окно — суммой
 * непрочитанных сообщений, «Звонки» — числом пропущенных звонков, строка в шторке — по
 * событию, звук — на каждое событие. Где и откуда что взялось и куда исчезло, не знал
 * никто (заказчик 2026-09-30: «иначе мы получим то же самое»). Теперь каждое уведомление —
 * строка: когда пришло, от кого, какого вида, откуда, что с ним сделали и **когда и чем
 * снято**. Все числа — из неё и только из неё.
 *
 * ── ЧТО ТАКОЕ «УВЕДОМЛЕНИЕ» И «СУЩНОСТЬ» ────────────────────────────────────
 *
 * Сущность — от кого новое: личная переписка, группа, а для «Звонков» — звонивший человек.
 * Строка журнала — одно событие (сообщение, пропущенный звонок), но **число** считается по
 * сущностям: три сообщения от redmi — одно уведомление одной сущности, два пропущенных —
 * одно уведомление (заказчик 2026-09-30).
 *
 * Строки не удаляются при снятии — помечаются временем и причиной. Старые снятые убирает
 * [purge].
 */
interface NoticeJournal {

    /**
     * Записать событие.
     *
     * @return `true` — новая строка; `false` — событие с тем же [NoticeRecord.what] и
     *   [NoticeRecord.ref] уже записано: повтор (разрыв ленты, два прохода разом), и
     *   уведомлять о нём второй раз нельзя (ЖУ0).
     */
    fun record(record: NoticeRecord): Boolean

    /** Что с событием сделали: `строка+звук`, `строка`, `тишина: …` — для разбора. */
    fun done(what: NoticeWhat, ref: String, done: String)

    /** Есть ли у сущности на вкладке неснятое уведомление этого вида. */
    fun isActive(tab: NoticeTab, entity: String, what: NoticeWhat): Boolean

    /** Снять уведомления сущности — открыли переписку, группу. @return сколько строк снято. */
    fun clearEntity(tab: NoticeTab, entity: String, by: String, atMs: Long): Int

    /** Снять всё на вкладке — открыли «Звонки». */
    fun clearTab(tab: NoticeTab, by: String, atMs: Long): Int

    /** Снять одно событие — пропущенный просмотрен на другом устройстве (`seen`). */
    fun clearRef(what: NoticeWhat, ref: String, by: String, atMs: Long): Int

    /** Неснятые уведомления — по одному на сущность и вид. Меняется вместе с таблицей. */
    fun active(): Flow<List<ActiveNotice>>

    /** То же, сейчас. */
    fun activeNow(): List<ActiveNotice>

    /** Убрать снятые строки старше [beforeMs]. @return сколько убрано. */
    fun purge(beforeMs: Long): Int
}

/** Вкладка, на которой считается уведомление. Каналов и сообществ пока нет: у них нет источника. */
enum class NoticeTab(val wire: String) {
    Chats("chats"),
    Calls("calls"),
    Groups("groups"),
    ;

    companion object {
        fun of(wire: String): NoticeTab? = entries.firstOrNull { it.wire == wire }
    }
}

/** Вид события. */
enum class NoticeWhat(val wire: String) {
    Message("message"),
    Missed("missed"),
    ;

    companion object {
        fun of(wire: String): NoticeWhat? = entries.firstOrNull { it.wire == wire }
    }
}

/** Откуда событие пришло — от этого зависит, звучит ли оно (ЖУ3). */
enum class NoticeFrom(val wire: String) {
    /** Живой канал: пришло только что. */
    Live("live"),

    /** Догонка: запуск, разрыв канала, очередь, разрыв ленты звонков. Без звука. */
    CatchUp("catchup"),

    /** Сверка при запуске: непрочитанное есть, строки в журнале не было. Без звука и строки. */
    Seed("seed"),
}

/**
 * Одно событие журнала.
 *
 * @param entity для «Чатов» и «Групп» — переписка, для «Звонков» — звонивший человек.
 * @param ref что именно: номер сообщения или звонок. Вместе с [what] опознаёт повтор.
 */
data class NoticeRecord(
    val tab: NoticeTab,
    val entity: String,
    val what: NoticeWhat,
    val ref: String,
    val atMs: Long,
    val from: NoticeFrom,
)

/** Неснятое уведомление: сущность на вкладке, этого вида. */
data class ActiveNotice(val tab: NoticeTab, val entity: String, val what: NoticeWhat)

/**
 * Числа из журнала — ЖУ2, ЖУ4 (решения заказчика 2026-09-30).
 *
 * - **Вкладка** — сколько сущностей на ней с новым: три сообщения от redmi и одно от Moi —
 *   «Чаты» 2; два пропущенных от redmi — «Звонки» 1.
 * - **Сущность** — сколько у неё уведомлений: redmi с сообщениями и пропущенными — 2.
 * - **Значок приложения** — сумма чисел вкладок: 2 + 1 = 3. Одна сущность на двух вкладках
 *   считается на каждой — числа не складываются в одно, а суммируются по вкладкам.
 */
class NoticeCounts(active: List<ActiveNotice>) {
    private val byTab: Map<NoticeTab, Set<String>> =
        active.groupBy { it.tab }.mapValues { (_, rows) -> rows.map { it.entity }.toSet() }

    /** Число вкладки. */
    fun tab(tab: NoticeTab): Int = byTab[tab]?.size ?: 0

    /** Сущности вкладки с новым. */
    fun entities(tab: NoticeTab): Set<String> = byTab[tab].orEmpty()

    /**
     * Число переписки в списке: её сообщения и, у личной, пропущенные звонки её
     * собеседника ([peerId]).
     */
    fun chat(chatId: String, peerId: String?): Int =
        (if (chatId in entities(NoticeTab.Chats) || chatId in entities(NoticeTab.Groups)) 1 else 0) +
            (if (peerId != null && peerId in entities(NoticeTab.Calls)) 1 else 0)

    /** Значок приложения — сумма чисел вкладок. */
    val total: Int get() = NoticeTab.entries.sumOf { tab(it) }

    companion object {
        val NONE = NoticeCounts(emptyList())
    }
}
