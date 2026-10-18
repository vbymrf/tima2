package io.tima.domain.chat

/**
 * Отправка текста — К4.1.
 *
 * **Что здесь есть и чего нет.** Есть три правила продукта: ключ идемпотентности
 * назначается **до первой попытки**, тело собирается один раз и одним кодеком, пустое
 * сообщение не отправляется. Нет ни сети, ни базы, ни шифрования: очередь,
 * упаковка и ключи приходят портами.
 *
 * Почему это не «лишняя обёртка над `enqueue`». Потому что `enqueue` не знает, откуда
 * берётся `dedup_key`, а от этого зависит, увидит ли собеседник дубль после обрыва.
 * Правило «ключ назначает клиент до первой попытки» — ровно то, чего не было в v1
 * (инвентарь, пункт 8), и жить оно должно в одном месте, а не в каждом вызывающем.
 */
class SendMessage(
    private val queue: OutgoingQueue,
    private val codec: MessageBodyCodec,
    private val keys: DedupKeys,
    /**
     * Ранняя отсечка по размеру тела. Точный запас неизвестен: конверт это тело плюс
     * обёртка ключа на каждое устройство получателя, а их число заранее не знает никто.
     * Поэтому здесь дешёвая проверка, а последнее слово — у транспорта, который знает
     * предел сервера (`maxEnvelopeBytes`) и отвечает на его превышение окончательным
     * отказом.
     */
    private val maxBodyBytes: Int = 4 shl 20,
) {

    /**
     * @param chatId переписка, в которую уходит сообщение.
     * @param text то, что набрал человек.
     */
    /**
     * @param level круг сообщения (ADR-0019). По умолчанию шифр — прежнее поведение.
     */
    /**
     * @param threadRoot корень ветки (ADR-0024): 0 — обычное сообщение, иначе серверный
     *   номер того, на что отвечают. Ответ в ветке шифруется **тем же ключом группы**:
     *   ветка — это те же сообщения, а не второй контур.
     */
    fun send(
        chatId: String,
        text: String,
        level: Int = LEVEL_SECRET,
        threadRoot: Long = 0,
        kind: Int = KIND_TEXT,
        /**
         * «Знаки md» (ПЛАН-(РС) РС2, Решения §7): включены — набранные знаки становятся
         * оформлением; выключены — текст уходит как есть. Только у набранного текста
         * ([KIND_TEXT]): служебное и звонки знаков не разбирают.
         */
        marks: Boolean = true,
        /** Ответ на сообщение (Решения §11.1): блок ответа первым в разметке тела. */
        reply: ReplyTarget? = null,
    ): SendMessageResult {
        require(chatId.isNotBlank()) { "chatId пустой" }

        // Пустое отсекаем до всего остального: очередь, кодек и ключ на нём тратить
        // незачем, а «отправилось ничего» — это ещё и строка в переписке.
        if (text.isBlank()) return SendMessageResult.Empty

        val body = when {
            reply != null && kind == KIND_TEXT -> codec.encodeReply(text, marks, reply)
            kind == KIND_TEXT && marks -> codec.encodeTyped(text)
            else -> codec.encodeText(text)
        }
        if (body.size > maxBodyBytes) {
            return SendMessageResult.TooLarge(body.size, maxBodyBytes)
        }

        // Ключ назначается ЗДЕСЬ, до первой попытки. Назначь его при отправке — и
        // повтор после обрыва уйдёт с новым ключом, то есть собеседник получит два
        // одинаковых сообщения, а сервер не сможет их склеить.
        val dedupKey = keys.newKey()
        require(dedupKey.isNotBlank()) { "ключ идемпотентности пустой" }

        return if (queue.enqueue(dedupKey, chatId, body, level, threadRoot, kind)) {
            SendMessageResult.Queued(dedupKey)
        } else {
            // Такой ключ уже в очереди. Не ошибка: так выглядит повторное нажатие,
            // пересечение живого канала с догоном, восстановление после перезапуска.
            SendMessageResult.AlreadyQueued(dedupKey)
        }
    }
}

/** Чем закончилась постановка сообщения в очередь. */
sealed interface SendMessageResult {
    /** Принято. Дальше — дело очереди: доставка не мгновенна и не обязана быть. */
    data class Queued(val dedupKey: String) : SendMessageResult

    /** Такое сообщение уже в очереди. Не ошибка. */
    data class AlreadyQueued(val dedupKey: String) : SendMessageResult

    /** Пустой текст. Отдельный исход, а не исключение: это обычное нажатие мимо. */
    data object Empty : SendMessageResult

    /** Тело больше предела. Медиа ходит объектным хранилищем, а не этим путём. */
    data class TooLarge(val bytes: Int, val limit: Int) : SendMessageResult
}

/**
 * Порт к очереди исходящих. Реализуется `core-outbox`.
 *
 * @return `false`, если такой `dedupKey` уже есть.
 */
fun interface OutgoingQueue {
    /**
     * @param level круг сообщения (ADR-0019). −1 — шифр, 0…3 — открытое. Хранится в
     *   очереди, потому что решение «нужен ли ключ» принимается при отправке, а она
     *   может случиться после перезапуска.
     * @param kind вид содержимого — `ContentKind` из `envelope.proto` (Л14). Там же и по
     *   той же причине: вид уезжает в подписанные метаданные конверта, а запечатывание
     *   бывает после перезапуска.
     */
    fun enqueue(
        dedupKey: String,
        chatId: String,
        body: ByteArray,
        level: Int,
        threadRoot: Long,
        // Без умолчания: у функционального интерфейса его быть не может, и это к лучшему.
        // Умолчание здесь значило бы, что новый вид содержимого легко не передать.
        kind: Int,
    ): Boolean
}

/** Круг по умолчанию: шифр. Всё, что было в очереди до появления кругов, — именно он. */
const val LEVEL_SECRET: Int = -1

/** `CK_TEXT` из `envelope.proto` — написал человек. */
const val KIND_TEXT: Int = 1

/**
 * `CK_SYSTEM` из `envelope.proto` — автоответ заблокированному (Л14).
 *
 * Вид, а не текст: фраза иначе уехала бы на языке блокирующего — русский заблокировал
 * испанца, и тот получил бы русский текст. Системный вид рисуется словарём **читающего**.
 *
 * Вид лежит в подписанных метаданных, и потому ему можно верить: метку внутри тела
 * отправитель волен сочинить, и «системное сообщение от TIMa» рисовал бы кто угодно.
 */
const val KIND_SYSTEM: Int = 6

/**
 * Порт к упаковке тела. Реализуется `core-encryption`: `zstd(protobuf(MessageBody))`.
 *
 * **Один кодек на провод и на диск** — решение плана §3.4: в v1 тело склеивалось
 * разделителем `\n\0`, и разметка через такую склейку не проходила вовсе.
 */
interface MessageBodyCodec {
    fun encodeText(text: String): ByteArray

    /**
     * Набранный текст с быстрым оформлением (ПЛАН-(РС) РС2): знаки → узлы и разметка. Текст без
     * знаков уходит так же, как через [encodeText]. По умолчанию — как [encodeText]: заглушки и
     * фальшивые кодеки тестов оформления не знают.
     */
    fun encodeTyped(text: String): ByteArray = encodeText(text)

    /**
     * Строка переписки из тела: плоский текст и оформление, одним разбором. `null` — тело не
     * читается (см. [decodeText]). По умолчанию оформления нет.
     */
    fun decodeLine(body: ByteArray): DecodedLine? = decodeText(body)?.let { DecodedLine(it, null) }

    /**
     * Открытая запись — узлы и разметка открытым текстом (ADR-0011 §4): запись страницы, канала,
     * описания. По умолчанию — текст узлов без оформления.
     */
    fun decodeOpen(nodes: List<String>, markup: String): DecodedLine? =
        nodes.joinToString("").takeIf { it.isNotEmpty() }?.let { DecodedLine(it, null) }

    /**
     * Обратный ход: текст из тела, лежащего в базе.
     *
     * Не отдельный порт, а вторая половина этого: кодек один на провод и на диск, и
     * разойтись двум портам было бы негде, кроме одного места — реализации. Тогда
     * записанное одним кодеком читалось бы другим.
     *
     * @return `null`, если тело не читается. Это **обычное состояние**, а не поломка:
     *   у входящего сообщения могло не найтись ключа, а у записи из будущей версии —
     *   неизвестный формат. И то и другое человеку показывают, а не прячут.
     */
    fun decodeText(body: ByteArray): String?

    /**
     * Строка исходного с наложенными правками (ADR-0045), по порядку. По умолчанию правок не
     * знает — заглушки тестов показывают исходное.
     */
    fun decodeEdited(body: ByteArray, edits: List<ByteArray>): DecodedLine? = decodeLine(body)

    /**
     * Тело правки: что изменилось между исходным с прежними правками и набранным заново.
     * [marks] — «Знаки md» (Решения §7); у сообщения без оформления знаки остаются текстом.
     */
    fun encodeEdit(body: ByteArray, edits: List<ByteArray>, typed: String, marks: Boolean): EditBody =
        EditBody.Unsupported

    /**
     * Что положить в строку ввода для правки: набранный текст со знаками. `null` — знаками не
     * выразить (таблица, ряд, виджет) — такое правит редактор (РС6).
     */
    fun editSource(body: ByteArray, edits: List<ByteArray>): String? = null

    /** Набранное с ответом на сообщение. По умолчанию ответ не знает — уходит как набранное. */
    fun encodeReply(text: String, marks: Boolean, reply: ReplyTarget): ByteArray =
        if (marks) encodeTyped(text) else encodeText(text)
}

/**
 * На что отвечают: вид переписки (`personal`, `group`, `channel`), где, номер и чьё.
 */
data class ReplyTarget(val kind: String, val chatId: String, val messageId: Long, val userId: String)

/** Что вышло из правки на уровне тела. */
sealed interface EditBody {
    data class Changes(val body: ByteArray) : EditBody
    /** Ничего не изменилось — отправлять нечего. */
    data object Same : EditBody
    /** Другое строение (новый блок, уровень заголовка): правка передаёт только текст узлов. */
    data object Structure : EditBody
    /** Кодек правок не знает. */
    data object Unsupported : EditBody
}

/** Разобранное тело для строки переписки: текст для списка и поиска и оформление для показа. */
data class DecodedLine(val text: String, val rich: RichContent?)

/**
 * Порт к ключам идемпотентности. Отдельный порт, а не `Random` внутри: тест обязан
 * уметь задать ключ, иначе проверить «ключ назначен до попытки» нечем.
 */
fun interface DedupKeys {
    fun newKey(): String
}
