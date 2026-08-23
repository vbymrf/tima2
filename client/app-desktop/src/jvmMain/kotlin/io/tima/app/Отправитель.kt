package io.tima.app

import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.EscrowKeyVerifier
import io.tima.core.encryption.EscrowTrust
import io.tima.core.encryption.OutgoingSealer
import io.tima.core.encryption.RecipientDevice
import io.tima.core.network.EscrowKeyResult
import io.tima.core.network.DeviceKeysResult
import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.OutboxPump
import io.tima.core.outbox.OutboxState
import io.tima.domain.account.Session

/**
 * Отправка в сеть: один проход очереди.
 *
 * **Собирает то, что уже есть, и ничего своего не решает.** Очередь решает, что
 * отправлять и повторять ли; `core-encryption` собирает конверт; транспорт отвечает
 * исходом. Здесь только сведение: для каждой переписки с ожидающими сообщениями взять
 * **её** ключ эпохи escrow и устройства адресатов, а потом отдать это насосу.
 *
 * ── ПОРЯДОК, КОТОРЫЙ ВАЖНЕЕ КОДА ────────────────────────────────────────────
 *
 * 1. **Ключ эпохи проверяется подписью анклава до всякого шифрования.** Непроверенный
 *    ключ означал бы шифрование в обход анклава — то есть переписку, которую не сможет
 *    восстановить ни человек, ни закон, при том что мы обещали обратное. Ключ выпуска
 *    анклава здесь пока стендовый ([EscrowTrust]); без него отправка **отказывает**, а не
 *    идёт мимо проверки.
 * 2. **Адресаты — устройства собеседника И свои остальные.** Без своих второе устройство
 *    человека не прочитает отправленное с первого.
 * 3. **Переписка без известного собеседника не отправляется.** `peer_id` берётся из
 *    таблицы `chats`; нет его — нечего спрашивать у сервера, и сообщение честно ждёт.
 */
class Отправитель(
    private val окружение: Окружение,
    private val сеть: Сеть,
    private val сессия: Session,
    личность: DeviceIdentity,
) {

    private val насос = OutboxPump(окружение.очередь)

    private val сборщик = OutgoingSealer(сессия.userId, сессия.deviceId, личность)

    /** Что помешало проходу. Показывается человеку одной строкой, а не глотается. */
    var последняяБеда: String? = null
        private set

    /**
     * Один проход.
     *
     * @return сколько сообщений получили исход. Ноль означает «нечего отправлять» либо
     *   «не удалось подготовиться» — второе видно по [последняяБеда].
     */
    suspend fun проход(): Int {
        val ждущие = окружение.очередь.pending()
            .filter { it.state == OutboxState.QUEUED }
            .map { it.chatId }
            .distinct()
        if (ждущие.isEmpty()) return 0

        val эпохи = HashMap<String, Long>()
        val сборщики = HashMap<String, (OutboxEntry) -> ByteArray>()
        for (chatId in ждущие) {
            val готовое = подготовить(chatId) ?: continue
            эпохи[chatId] = готовое.first
            сборщики[chatId] = готовое.second
        }
        if (эпохи.isEmpty()) return 0

        return насос.runOnce(
            эпохи = эпохи,
            seal = { запись ->
                // Сборщик выбирается по переписке записи: ключ эпохи и адресаты у каждой
                // свои, и подставить чужой сборщик значило бы зашифровать не тем ключом.
                val свой = сборщики[запись.chatId]
                    ?: error("нет сборщика для переписки ${запись.chatId}: насос взял чужое")
                свой(запись)
            },
            send = { готовое -> сеть.транспорт.send(готовое.entry.dedupKey, готовое.envelope) },
        )
    }

    /**
     * Ключ эпохи и сборщик конвертов для одной переписки.
     *
     * @return `null`, если подготовиться не удалось. Причина попадает в [последняяБеда]:
     *   отправка, которая молча не происходит, — худшее из состояний, потому что человек
     *   видит «ждёт» и не знает, чего ждать.
     */
    private suspend fun подготовить(chatId: String): Pair<Long, (OutboxEntry) -> ByteArray>? {
        val анклав = EscrowTrust.enclaveSigningPub ?: run {
            последняяБеда = "нет ключа подписи анклава: отправка отказана целиком"
            return null
        }

        val собеседник = окружение.db.chatsQueries.chatById(chatId).executeAsOneOrNull()?.peer_id
        if (собеседник == null) {
            последняяБеда = "у переписки $chatId неизвестен собеседник — некому адресовать"
            return null
        }

        val адресаты = устройства(собеседник) ?: return null
        val свои = устройства(сессия.userId) ?: return null
        val все = (адресаты + свои).distinctBy { it.deviceId }
        if (все.isEmpty()) {
            последняяБеда = "у собеседника нет ни одного устройства"
            return null
        }

        val ключ = when (val исход = сеть.escrow.keyForChat(chatId)) {
            is EscrowKeyResult.Keys -> исход.current
            EscrowKeyResult.NoEnclave -> {
                // Не «нет сети»: без анклава отправка невозможна в принципе, и человеку
                // надо сказать именно это.
                последняяБеда = "сервер без анклава escrow: отправлять нельзя"
                return null
            }
            is EscrowKeyResult.Offline -> {
                последняяБеда = "нет связи с сервером"
                return null
            }
            is EscrowKeyResult.Refused -> {
                последняяБеда = "сервер отказал в ключе эпохи: ${исход.code}"
                return null
            }
        }

        val проверенный = EscrowKeyVerifier.verify(
            enclaveSigningPub = анклав,
            id = ключ.id,
            region = ключ.region,
            chatId = ключ.chatId,
            epoch = ключ.epoch,
            publicKey = ключ.publicKey,
            signature = ключ.signature,
            validFromMs = ключ.validFromMs,
            validToMs = ключ.validToMs,
            destroyAtMs = ключ.destroyAtMs,
            nowMs = System.currentTimeMillis(),
        ).getOrElse {
            // Подпись не сошлась — это не «повторим позже», а подмена или наша ошибка.
            последняяБеда = "подпись анклава не сошлась: ${it.message}"
            return null
        }

        последняяБеда = null
        return ключ.id to сборщик.sealerFor(проверенный, все)
    }

    private suspend fun устройства(userId: String): List<RecipientDevice>? =
        when (val исход = сеть.ключи.devicesOf(userId)) {
            is DeviceKeysResult.Devices ->
                исход.devices.map { RecipientDevice(it.deviceId, it.encryptionPub) }

            is DeviceKeysResult.Offline -> {
                последняяБеда = "нет связи с сервером"
                null
            }

            is DeviceKeysResult.Refused -> {
                последняяБеда = "сервер отказал в ключах устройств: ${исход.code}"
                null
            }
        }
}
