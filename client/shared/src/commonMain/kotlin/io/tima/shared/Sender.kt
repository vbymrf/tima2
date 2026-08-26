package io.tima.shared

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
class Sender(
    private val environment: Environment,
    private val network: Network,
    private val session: Session,
    identity: DeviceIdentity,
) {

    private val pump = OutboxPump(environment.queue)

    private val builder = OutgoingSealer(session.userId, session.deviceId, identity)

    /** Что помешало проходу. Показывается человеку одной строкой, а не глотается. */
    var lastTrouble: String? = null
        private set

    /**
     * Один проход.
     *
     * @return сколько сообщений получили исход. Ноль означает «нечего отправлять» либо
     *   «не удалось подготовиться» — второе видно по [последняяБеда].
     */
    suspend fun pass(): Int {
        val waiting = environment.queue.pending()
            .filter { it.state == OutboxState.QUEUED }
            .map { it.chatId }
            .distinct()
        if (waiting.isEmpty()) return 0

        val epoch = HashMap<String, Long>()
        val builders = HashMap<String, (OutboxEntry) -> ByteArray>()
        for (chatId in waiting) {
            val ready = prepare(chatId) ?: continue
            epoch[chatId] = ready.first
            builders[chatId] = ready.second
        }
        if (epoch.isEmpty()) return 0

        return pump.runOnce(
            epoch = epoch,
            seal = { entry ->
                // Сборщик выбирается по переписке записи: ключ эпохи и адресаты у каждой
                // свои, и подставить чужой сборщик значило бы зашифровать не тем ключом.
                val own = builders[entry.chatId]
                    ?: error("нет сборщика для переписки ${entry.chatId}: насос взял чужое")
                own(entry)
            },
            send = { ready -> network.transport.send(ready.entry.dedupKey, ready.envelope) },
        )
    }

    /**
     * Ключ эпохи и сборщик конвертов для одной переписки.
     *
     * @return `null`, если подготовиться не удалось. Причина попадает в [последняяБеда]:
     *   отправка, которая молча не происходит, — худшее из состояний, потому что человек
     *   видит «ждёт» и не знает, чего ждать.
     */
    private suspend fun prepare(chatId: String): Pair<Long, (OutboxEntry) -> ByteArray>? {
        val enclave = EscrowTrust.enclaveSigningPub ?: run {
            lastTrouble = "нет ключа подписи анклава: отправка отказана целиком"
            return null
        }

        val peer = environment.chatFacts.peerOf(chatId)
        if (peer == null) {
            lastTrouble = "у переписки $chatId неизвестен собеседник — некому адресовать"
            return null
        }

        val recipients = devices(peer) ?: return null
        val own = devices(session.userId) ?: return null
        val all = (recipients + own).distinctBy { it.deviceId }
        if (all.isEmpty()) {
            lastTrouble = "у собеседника нет ни одного устройства"
            return null
        }

        val key = when (val outcome = network.escrow.keyForChat(chatId)) {
            is EscrowKeyResult.Keys -> outcome.current
            EscrowKeyResult.NoEnclave -> {
                // Не «нет сети»: без анклава отправка невозможна в принципе, и человеку
                // надо сказать именно это.
                lastTrouble = "сервер без анклава escrow: отправлять нельзя"
                return null
            }
            is EscrowKeyResult.Offline -> {
                lastTrouble = "нет связи с сервером"
                return null
            }
            is EscrowKeyResult.Refused -> {
                lastTrouble = "сервер отказал в ключе эпохи: ${outcome.code}"
                return null
            }
        }

        val verified = EscrowKeyVerifier.verify(
            enclaveSigningPub = enclave,
            id = key.id,
            region = key.region,
            chatId = key.chatId,
            epoch = key.epoch,
            publicKey = key.publicKey,
            signature = key.signature,
            validFromMs = key.validFromMs,
            validToMs = key.validToMs,
            destroyAtMs = key.destroyAtMs,
            nowMs = msNow(),
        ).getOrElse {
            // Подпись не сошлась — это не «повторим позже», а подмена или наша ошибка.
            lastTrouble = "подпись анклава не сошлась: ${it.message}"
            return null
        }

        lastTrouble = null
        return key.id to builder.sealerFor(verified, all)
    }

    private suspend fun devices(userId: String): List<RecipientDevice>? =
        when (val outcome = network.keys.devicesOf(userId)) {
            is DeviceKeysResult.Devices ->
                outcome.devices.map { RecipientDevice(it.deviceId, it.encryptionPub) }

            is DeviceKeysResult.Offline -> {
                lastTrouble = "нет связи с сервером"
                null
            }

            is DeviceKeysResult.Refused -> {
                lastTrouble = "сервер отказал в ключах устройств: ${outcome.code}"
                null
            }
        }
}
