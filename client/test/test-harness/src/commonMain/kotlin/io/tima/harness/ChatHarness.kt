package io.tima.harness

import app.cash.sqldelight.db.SqlDriver
import io.tima.core.database.SqlInboxStore
import io.tima.core.database.SqlOutboxStore
import io.tima.core.database.TimaDatabase
import io.tima.core.encryption.TextBodyCodec
import io.tima.core.outbox.Inbox
import io.tima.core.outbox.Outbox
import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.OutboxPump
import io.tima.core.outbox.OutboxState
import io.tima.core.outbox.UuidDedupKeys
import io.tima.domain.chat.SendMessage
import io.tima.domain.chat.SendMessageResult

/**
 * Сквозной путь отправки **без сервера** — К4.6.
 *
 * Собрано из настоящих частей: `SendMessage` (правила), `Outbox` над **настоящей
 * базой** (SQLDelight), `OutboxPump` (планировщик), `TextBodyCodec` (protobuf + zstd).
 * Подменён только транспорт — [FakeTransport]. Смысл именно в этом: сценарий
 * «отправил — обрыв — повтор — доставлено» обязан проверять наши правила, а не наш
 * макет наших правил.
 *
 * **Запечатывание здесь не крипто, и это осознанно.** Конверт собирается пометкой
 * эпохи и телом: сценарий проверяет очередь — что ничего не потеряно и ничего не
 * удвоено, — а криптография проверена своим кругом (`core-encryption`) и векторами на
 * двух платформах (гейт К2). Смешав их, я бы получил падения, по которым непонятно,
 * где сломалось.
 *
 * Часы искусственные ([time]): лестница задержек проверяется точными числами, а не
 * ожиданием в секундах.
 */
class ChatHarness(
    private val driver: SqlDriver,
    startTime: Long = 1_000L,
    startEpoch: Int = 1,
    /**
     * Чем запечатывать. По умолчанию — пометка эпохи и тело: сценарии очереди проверяют
     * очередь, и настоящая криптография в них только мешала бы понять, что сломалось.
     *
     * Настоящий сборщик конвертов подставляется отдельным сценарием — тем, который
     * проверяет весь круг: текст → тело → очередь → конверт → приём → текст.
     */
    private val sealWith: ((OutboxEntry) -> ByteArray)? = null,
) {

    /** Искусственные часы. Сдвигаются [passTime]. */
    var time: Long = startTime
        private set

    /** Текущая эпоха escrow. Меняется [changeEpoch]. */
    var epochKeyId: Int = startEpoch
        private set

    private val db = TimaDatabase(driver)

    val outbox = Outbox(SqlOutboxStore(db), nowMs = { time })
    val inbox = Inbox(SqlInboxStore(db), nowMs = { time })

    /**
     * Транспорт. Один на харнесс и **не переживает перезапуск** — как настоящее
     * соединение; «сервер» же (что он уже принял) живёт в самом транспорте, поэтому
     * при перезапуске передаётся дальше.
     */
    var transport = FakeTransport()
        private set

    private val pump = OutboxPump(outbox, maxConcurrent = 3)

    private val sender = SendMessage(queue = outbox, codec = TextBodyCodec, keys = UuidDedupKeys)

    /** Ставит сообщение в очередь — как нажатие «отправить». */
    fun send(chatId: String, text: String): SendMessageResult = sender.send(chatId, text)

    /** Один проход насоса: запечатать готовое и отдать транспорту. */
    suspend fun pumpOnce(): Int = pump.runOnce(epochKeyId, ::seal, transport::send)

    /** Прошло время: сроки повторов наступили. */
    fun passTime(ms: Long) {
        time += ms
    }

    /**
     * Убийство процесса: память потеряна, база осталась.
     *
     * Возвращает новый харнесс над **тем же драйвером** — это и есть перезапуск
     * приложения. Кэш конвертов при этом пуст по определению: он был в памяти.
     * «Сервер» переносится, потому что он-то не перезапускался.
     */
    fun restart(): ChatHarness {
        val прежнийСервер = transport
        return ChatHarness(driver, startTime = time, startEpoch = epochKeyId, sealWith = sealWith).also {
            it.transport = прежнийСервер
            it.outbox.recoverOnStart()
        }
    }

    /** Смена эпохи escrow: запечатанное под прошлую негодно (ADR-0016). */
    fun changeEpoch(next: Int) {
        epochKeyId = next
        outbox.onEpochChanged(next)
    }

    /**
     * Сколько незавершённых записей в этом состоянии.
     *
     * Только незавершённые: терминальные (`SENT`, `DEAD`) в `pending` не входят, и
     * доставленное проверяется по «серверу» — [FakeTransport.deliveredCount].
     */
    fun pendingIn(state: OutboxState): Int = outbox.pending().count { it.state == state }

    /** Все незавершённые — для внятного текста падения. */
    fun pending(): List<OutboxEntry> = outbox.pending()

    /**
     * Переписка так, как её увидит человек: **все** состояния, включая терминальные.
     *
     * Нужно именно это, а не `pending`: незавершённое — не то же, что видимое.
     * Неотправленное сообщение (`DEAD`) в очереди уже не стоит, а в переписке стоять
     * обязано — иначе человек не узнает, что оно не ушло.
     */
    fun chatPage(chatId: String, limit: Long = 50): List<ChatRow> =
        db.messagesQueries.chatPage(chatId, limit).executeAsList().map {
            ChatRow(
                dedupKey = it.dedup_key,
                state = OutboxState.entries[it.state.toInt()],
                outgoing = it.direction == 0L,
                serverId = it.server_id,
            )
        }

    /** Строка переписки — то немногое из неё, что нужно сценарию. */
    data class ChatRow(
        val dedupKey: String,
        val state: OutboxState,
        val outgoing: Boolean,
        val serverId: Long?,
    )

    private fun seal(entry: OutboxEntry): ByteArray =
        sealWith?.invoke(entry) ?: ("эпоха=$epochKeyId|".encodeToByteArray() + entry.body)
}
