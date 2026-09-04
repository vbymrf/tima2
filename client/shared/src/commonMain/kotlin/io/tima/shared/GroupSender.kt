package io.tima.shared

import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.GroupMessageSealerOverKodium
import io.tima.core.network.GroupTransportOverHttp
import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.OutboxState
import io.tima.core.outbox.SendOutcome
import io.tima.domain.account.Session
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.GroupSendStep
import io.tima.domain.chat.LEVEL_SECRET

/**
 * Отправка в группы: второй проход очереди, рядом с личным.
 *
 * ── ПОЧЕМУ ОТДЕЛЬНО, А НЕ ВНУТРИ [Sender] ───────────────────────────────────
 *
 * У личного сообщения и у группового разное всё, что делается **до** отправки: личному
 * нужны ключ эпохи escrow, устройства собеседника и конверт на каждое из них; групповому
 * — версия группового ключа и ничего больше. Общий у них ровно один шаг: взять запись из
 * очереди. Поэтому очередь одна — офлайн, повторы и дедупликация достаются группам
 * даром, — а проходы разные.
 *
 * ── ГЛАВНАЯ ОСЬ: НУЖЕН ЛИ КЛЮЧ ──────────────────────────────────────────────
 *
 * Делить работу по виду переписки неверно: **личная группа шлёт открытые сообщения** — её
 * описание это уровень 0, и шифра у него нет по назначению (ADR-0019 §2, §4). Поэтому
 * первое, что спрашивается о записи, — `level == -1`; вид контейнера отвечает лишь на
 * второй вопрос, чей ключ брать.
 *
 * ── ЧЕГО ТРЕБУЕТ ADR-0017 ───────────────────────────────────────────────────
 *
 * Escrow-блоб у группы **один на версию ключа** и заворачивается в момент ротации. Отсюда
 * инвариант §2: в группе, где в эпоху E была отправка, последняя версия GK обязана быть
 * привязана к E. Значит перед зашифрованной отправкой мало иметь ключ — нужна свежая
 * версия, иначе сообщение окажется невосстановимым по ордеру **в день отправки**, и никто
 * этого не заметит: участники читают его нормально.
 *
 * Проверку делает клиент сам (§3): событие сервера можно пропустить, будучи офлайн.
 */
class GroupSender(
    private val environment: Environment,
    network: Network,
    session: Session,
    identity: DeviceIdentity,
    /** Ротация ключа группы — та же, что по событию сервера ([GroupKeyRotation]). */
    private val rotate: suspend (String) -> Boolean,
    /**
     * Устарела ли версия ключа: эпоха её выпуска ≠ текущая.
     *
     * Отдельной функцией, а не полем: знание об эпохах живёт в сети и крипте, а этому
     * проходу нужен ответ «да или нет».
     */
    private val stale: suspend (String) -> Boolean,
) {

    private val sealer = GroupMessageSealerOverKodium(session.userId, session.deviceId, identity)
    private val transport = GroupTransportOverHttp(network.groupMessages)

    /** Что помешало проходу. Показывается человеку одной строкой, а не глотается. */
    var lastTrouble: String? = null
        private set

    /**
     * Один проход по групповым записям очереди.
     *
     * @return сколько сообщений получили окончательный исход. Отложенные (нет ключа, нет
     *   связи, медленный режим) в счёт не идут: они остались ждать, и это не результат.
     */
    suspend fun pass(): Int {
        val groups = environment.queue.pending()
            .filter { it.state == OutboxState.QUEUED }
            .map { it.chatId }
            .distinct()
            .filter { environment.chatFacts.kindOf(it) == ChatKind.Group }
        if (groups.isEmpty()) return 0

        var done = 0
        for (groupId in groups) {
            while (true) {
                val entry = environment.queue.claimQueued(groupId) ?: break
                if (!sendOne(entry)) break
                done++
            }
        }
        return done
    }

    /** @return true, если исход окончательный (успех либо отказ навсегда). */
    private suspend fun sendOne(entry: OutboxEntry): Boolean {
        val secret = entry.level == LEVEL_SECRET

        // Ключ нужен только шифрованному. Открытое уходит без него — и это не обход
        // шифрования, а его отсутствие по назначению: такое сообщение читает тот, кому
        // ключа не дадут.
        var version = 0
        var key = ByteArray(0)
        if (secret) {
            // Инвариант ADR-0017 §2: устаревшая версия делает сообщение невосстановимым по
            // ордеру в день отправки. Ротируем до отправки, а не после.
            if (stale(entry.chatId) && !rotate(entry.chatId)) {
                return wait(entry, "не удалось обновить ключ группы — сообщение ждёт")
            }
            val latest = environment.groupKeyBook.latestVersion(entry.chatId)
            val material = latest?.let { environment.groupKeyBook.key(entry.chatId, it) }
            if (latest == null || material == null) {
                // Ключа нет вовсе: новое устройство, которому его ещё не дали. Сообщение
                // ОСТАЁТСЯ в очереди — ключ придёт с ближайшей ротацией, и оно уйдёт само.
                // Запустить ротацию может любой участник (ADR-0017 §5), поэтому ждать
                // здесь — не тупик.
                return wait(entry, "ключа группы ещё нет — сообщение ждёт")
            }
            version = latest
            key = material
        }

        val assembled = sealer.sealPrepared(
            groupId = entry.chatId,
            gkVersion = version,
            key = key,
            body = entry.body,
            createdAtUnixMs = entry.createdAtMs,
        ) ?: return wait(entry, "не удалось собрать сообщение группы")

        val outcome = transport.send(
            groupId = entry.chatId,
            clientMsgId = entry.dedupKey,
            kind = KIND_TEXT,
            gkVersion = version,
            payload = assembled.payload,
            signature = assembled.signature,
            createdAtUnixMs = entry.createdAtMs,
            level = entry.level,
        )

        return when (outcome) {
            // Повтор дошедшего — успех: сообщение у сервера есть, и слать его снова незачем.
            is GroupSendStep.Duplicate -> {
                environment.queue.onOutcome(entry.dedupKey, SendOutcome.Duplicate(outcome.messageId))
                true
            }

            is GroupSendStep.Sent -> {
                // Счётчик отправок под версией растёт только у шифрованного: открытое
                // ключом не пользовалось и ротацию не приближает.
                if (secret) environment.groupKeyBook.markSend(entry.chatId, version)
                environment.queue.onOutcome(entry.dedupKey, SendOutcome.Accepted(outcome.messageId))
                true
            }

            // Версия, которой сервер не знает: наш ключ новее серверного состояния.
            // Пересобирать бессмысленно, пока ключи не сойдутся.
            GroupSendStep.UnknownKeyVersion ->
                wait(entry, "сервер не знает нашей версии ключа — сообщение ждёт")

            GroupSendStep.Banned -> {
                lastTrouble = "вы заблокированы в этой группе"
                environment.queue.onOutcome(entry.dedupKey, SendOutcome.Permanent("banned"))
                true
            }

            is GroupSendStep.SlowMode ->
                wait(entry, "медленный режим: подождите ${outcome.retryAfterSec} с", outcome.retryAfterSec * 1000L)

            is GroupSendStep.Offline ->
                wait(entry, "нет связи с сервером", outcome.retryAfterMs)

            is GroupSendStep.Refused -> {
                lastTrouble = "сервер отказал: ${outcome.reason}"
                environment.queue.onOutcome(entry.dedupKey, SendOutcome.Permanent(outcome.reason))
                true
            }
        }
    }

    /**
     * Сообщение остаётся в очереди и ждёт.
     *
     * Записывается беда — и это важнее самого ожидания: отправка, которая молча не
     * происходит, худшее из состояний, потому что человек видит «ждёт» и не знает, чего.
     */
    private fun wait(entry: OutboxEntry, why: String, afterMs: Long = 0): Boolean {
        lastTrouble = why
        environment.queue.onOutcome(entry.dedupKey, SendOutcome.Retry(afterMs))
        return false
    }

    private companion object {
        /** `CK_TEXT` из `envelope.proto`. */
        const val KIND_TEXT = 1
    }
}
