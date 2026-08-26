package io.tima.domain.chat

/**
 * Отправка сообщения в группу.
 *
 * **Чем отличается от личной отправки.** Личное кладётся в очередь и уходит проходом
 * насоса: у него на каждое сообщение свои обёртки ключа и свой escrow, которые надо
 * собрать в момент отправки. У группового всё это уже есть — ключ у участников, escrow
 * один на версию, — поэтому сообщение уходит сразу, а не ждёт сборки конверта.
 *
 * **Версия берётся последняя известная НАМ.** Не серверная: серверная могла уйти вперёд,
 * а ключа от неё у нас ещё нет, и зашифровать ею нечем. Если сервер такой версии не
 * знает — он ответит `unknown_gk_version`, и это честный признак, что надо сходить за
 * ключами, а не повторять отправку.
 *
 * **Счётчик растёт только после успеха.** Считать попытки значило бы ротировать ключ из-за
 * обрывов связи — то есть наказывать за плохую сеть выдачей обёрток всем устройствам.
 */
class SendGroupMessage(
    private val keys: GroupKeyBook,
    private val sealer: GroupMessageSealer,
    private val transport: GroupTransport,
    private val dedup: DedupKeys,
    /** Ротация при переполнении счётчика. Оптимизация: гарантию даёт смена эпохи. */
    private val rotator: GroupKeyRotator,
    private val nowMs: () -> Long,
) {

    suspend fun send(groupId: String, text: String): SendGroupStep {
        if (text.isBlank()) return SendGroupStep.Empty

        val version = keys.latestVersion(groupId) ?: return SendGroupStep.NoKey
        val key = keys.key(groupId, version) ?: return SendGroupStep.NoKey

        val idempotencyKey = dedup.newKey()
        val moment = nowMs()
        val assembled = sealer.seal(
            groupId = groupId,
            gkVersion = version,
            key = key,
            text = text,
            createdAtUnixMs = moment,
        ) ?: return SendGroupStep.CannotSeal

        val outcome = transport.send(
            groupId = groupId,
            clientMsgId = idempotencyKey,
            kind = KIND_TEXT,
            gkVersion = version,
            payload = assembled.payload,
            signature = assembled.signature,
            createdAtUnixMs = moment,
        )

        return when (outcome) {
            // Повтор той же отправки — тоже успех: сообщение у сервера есть. Но счётчик
            // на нём не растёт: под этой версией было отправлено одно сообщение, а не два.
            is GroupSendStep.Duplicate -> SendGroupStep.Sent(outcome.messageId, launchedRotation = false)

            is GroupSendStep.Sent -> {
                val howMany = keys.markSend(groupId, version)
                val due = howMany >= ROTATION_THRESHOLD
                if (due) {
                    // Провал ротации не отменяет отправку: сообщение доставлено, а ключ
                    // сменится при следующей попытке. Сказать об этом наружу стоит, но
                    // ронять из-за этого отправку — нет.
                    rotator.rotate(groupId)
                }
                SendGroupStep.Sent(outcome.messageId, launchedRotation = due)
            }

            GroupSendStep.UnknownKeyVersion -> SendGroupStep.NeedKeys
            GroupSendStep.Banned -> SendGroupStep.Banned
            is GroupSendStep.SlowMode -> SendGroupStep.SlowMode(outcome.retryAfterSec)
            is GroupSendStep.Offline -> SendGroupStep.Offline(outcome.retryAfterMs)
            is GroupSendStep.Refused -> SendGroupStep.Refused(outcome.reason)
        }
    }

    companion object {
        /** `CK_TEXT` из `envelope.proto`. */
        const val KIND_TEXT: Int = 1

        /**
         * Порог счётчика (ADR-0017 §1).
         *
         * Не 100, как было в первой редакции протокола: сто сообщений — это несколько
         * ротаций в сутки у рабочей группы, а каждая есть фан-аут обёрток на все
         * устройства всех участников. Смысл счётчика не в свежести ключа, а в
         * ограничении объёма, который раскроется при его утечке.
         */
        const val ROTATION_THRESHOLD: Int = 10_000
    }
}

// ── порты ───────────────────────────────────────────────────────────────────

/**
 * Порт сборки сообщения группы. Реализуется `core-encryption`.
 *
 * `null` — собрать не удалось: домен не должен знать, чем именно, потому что делать в
 * любом случае одно и то же.
 */
fun interface GroupMessageSealer {
    fun seal(
        groupId: String,
        gkVersion: Int,
        key: ByteArray,
        text: String,
        createdAtUnixMs: Long,
    ): SealedGroupBytes?
}

/** Порт отправки. Реализуется `core-network`. */
interface GroupTransport {
    suspend fun send(
        groupId: String,
        clientMsgId: String,
        kind: Int,
        gkVersion: Int,
        payload: ByteArray,
        signature: ByteArray,
        createdAtUnixMs: Long,
    ): GroupSendStep
}

class SealedGroupBytes(val payload: ByteArray, val signature: ByteArray)

sealed interface GroupSendStep {
    data class Sent(val messageId: Long) : GroupSendStep
    data class Duplicate(val messageId: Long) : GroupSendStep
    data object UnknownKeyVersion : GroupSendStep
    data object Banned : GroupSendStep
    data class SlowMode(val retryAfterSec: Int) : GroupSendStep
    data class Offline(val retryAfterMs: Long) : GroupSendStep
    data class Refused(val reason: String) : GroupSendStep
}

// ── исходы ──────────────────────────────────────────────────────────────────

sealed interface SendGroupStep {
    /** @param ротацияЗапущена счётчик дошёл до порога, и ключ меняется. */
    data class Sent(val messageId: Long, val launchedRotation: Boolean) : SendGroupStep

    /** Пустое сообщение до сети не доходит. */
    data object Empty : SendGroupStep

    /**
     * Ключа группы у нас нет вовсе — писать нечем.
     *
     * Бывает у только что созданной группы до первой ротации и у устройства, которое ещё
     * не забрало обёртки. Это не ошибка сети и лечится не повтором.
     */
    data object NoKey : SendGroupStep

    /** Сервер не знает нашей версии: сначала за ключами, потом слать. */
    data object NeedKeys : SendGroupStep

    /** Собрать сообщение не удалось: это не «сеть», а невозможность зашифровать. */
    data object CannotSeal : SendGroupStep

    data object Banned : SendGroupStep
    data class SlowMode(val retryAfterSec: Int) : SendGroupStep
    data class Offline(val retryAfterMs: Long) : SendGroupStep
    data class Refused(val reason: String) : SendGroupStep
}
