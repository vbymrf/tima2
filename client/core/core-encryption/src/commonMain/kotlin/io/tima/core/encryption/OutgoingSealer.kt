package io.tima.core.encryption

import io.tima.crypto.EnvelopeMeta
import io.tima.crypto.MessageSerializer
import io.tima.core.outbox.OutboxEntry

/**
 * Сборка конверта для очереди — то, что подставляется в `OutboxPump.runOnce(seal = …)`.
 *
 * **Почему сборка синхронная, а ключи приходят снаружи.** Машина состояний очереди
 * синхронна намеренно: её правильность зависит от порядка переходов, а не от
 * планировщика. Значит ходить за ключами внутри запечатывания нельзя — их достаёт
 * вызывающий **один раз на проход** и передаёт сюда. Отсюда же следует, что смена
 * эпохи посреди прохода не страшна: следующий проход возьмёт новые ключи, а
 * запечатанное под прошлую эпоху очередь пересоберёт сама (ADR-0016).
 *
 * **`message_id` выводится из `dedup_key`, а не назначается заново.** Он лежит
 * **внутри подписи** (`meta.message_id`), и на повторе обязан быть тем же: иначе одно
 * и то же сообщение приходит с двумя разными идентификаторами, и то, что мы записали
 * себе, перестаёт совпадать с тем, что лежит у сервера. Вывод из `dedup_key` даёт это
 * бесплатно — без второго столбца в базе, который пришлось бы держать в согласии.
 */
class OutgoingSealer(
    /** Кто отправляет: идентификаторы из сессии, они же едут в подпись. */
    private val senderId: String,
    private val senderDeviceId: String,
    /** Ключ устройства: им подписывается конверт. */
    private val identity: DeviceIdentity,
) {

    /**
     * @param escrowKey **проверенный** ключ эпохи — только из [EscrowKeyVerifier].
     *   Непроверенный ключ означал бы шифрование в обход анклава.
     * @param recipients устройства получателя **и свои остальные**: без своих второе
     *   устройство не прочитает отправленное с первого.
     */
    fun sealerFor(
        escrowKey: EscrowEpochKey,
        recipients: List<RecipientDevice>,
    ): (OutboxEntry) -> ByteArray {
        require(recipients.isNotEmpty()) { "нужен хотя бы один адресат обёртки ключа" }
        val messages = PersonalMessages(escrowKey)

        return { entry ->
            val meta = EnvelopeMeta(
                messageId = messageIdOf(entry.dedupKey),
                chatId = entry.chatId,
                senderId = senderId,
                senderDevice = senderDeviceId,
                kind = KIND_TEXT,
                createdAtUnixMs = entry.createdAtMs,
            )
            // Тело в записи очереди уже упаковано (zstd(protobuf)) — один кодек на
            // провод и на диск. Разворачиваем его обратно в содержимое только потому,
            // что фасад принимает содержимое: упаковывать дважды нельзя, иначе байты
            // разойдутся с теми, что легли в базу.
            val content = MessageSerializer.decodeBody(entry.body)
                .map { io.tima.crypto.MessageContentCodec.fromBody(it) }
                .getOrElse { error("тело записи ${entry.dedupKey} не разбирается: $it") }

            messages.seal(content, meta, identity, recipients).getOrElse {
                // Наверх исключением: это не «сеть отказала», а невозможность собрать
                // конверт. Превратить такое в повтор значило бы повторять вечно.
                throw it
            }
        }
    }

    companion object {
        /** `CK_TEXT` из `envelope.proto`. */
        const val KIND_TEXT: Int = 1

        /**
         * Идентификатор сообщения из ключа идемпотентности: первые 8 байт UUID,
         * little-endian, **со сброшенным старшим битом**.
         *
         * Шестьдесят три бита случайного UUID — достаточная развязка для чисел, которые
         * сравниваются только внутри одной переписки. Главное свойство здесь не
         * уникальность, а **устойчивость**: один и тот же `dedup_key` обязан давать одно и
         * то же число на каждом повторе.
         *
         * ── ПОЧЕМУ СТАРШИЙ БИТ СБРОШЕН ──────────────────────────────────────────
         *
         * Потому что **сервер хранит `message_id` в `bigint`, то есть в ЗНАКОВОМ int64**.
         * Число с установленным старшим битом в него не влезает, и Postgres отвечает
         * ошибкой кодирования, а сервер — `500`. На проводе поле `uint64`, и по проводу
         * такое число проходит прекрасно: расхождение видно только на записи.
         *
         * Найдено прогоном по стенду: половина сообщений уходила, половина получала `500`.
         * Ровно половина — старший бит случайного UUID установлен в половине случаев,
         * поэтому и предыдущий прогон был зелёным. Проверка на «положительное» стоит
         * рядом и гоняет тысячу ключей.
         */
        fun messageIdOf(dedupKey: String): ULong {
            val hex = dedupKey.filter { it != '-' }
            require(hex.length >= 16) { "ключ идемпотентности короче 16 шестнадцатеричных знаков: $dedupKey" }
            var value = 0uL
            for (i in 0 until 8) {
                val byte = hex.substring(i * 2, i * 2 + 2).toUByte(16)
                value = value or (byte.toULong() shl (8 * i))
            }
            // Старший бит долой: серверный bigint знаковый.
            value = value and МАКСИМУМ_ЗНАКОВОГО
            // Ноль отдавать нельзя: в протоколе он значит «нет идентификатора»
            // (replyTo = 0 именно так и читается).
            return if (value == 0uL) 1uL else value
        }

        /** `Long.MAX_VALUE` в беззнаковом виде: предел серверного `bigint`. */
        private const val МАКСИМУМ_ЗНАКОВОГО: ULong = 0x7FFF_FFFF_FFFF_FFFFuL
    }
}
