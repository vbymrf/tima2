package io.tima.domain.account

/**
 * Передача виртуального аккаунта — ПЛАН-КОНТАКТОВ.md, Д12.
 *
 * **Передача уносит всё**: переписку, группы, каналы, роли и владение. Ничего не
 * переносится построчно — они ссылаются на тот же идентификатор, который передача не
 * меняет. И она **отрезает будущее, а не прошлое**: скачанное и расшифрованное остаётся у
 * прежнего владельца на его устройстве, отобрать это нечем. Сказать об этом обязан экран.
 *
 * ── ДВА УСЛОВИЯ, А НЕ ОДНО ──────────────────────────────────────────────────
 *
 * Знания фразы мало: нужно ещё действие прежнего владельца — он выдаёт код. Иначе
 * подслушанной фразы хватало бы, чтобы забрать аккаунт. И наоборот: код без фразы не
 * передаёт ничего, поэтому его не страшно показать на экране и сфотографировать.
 *
 * **Фразу приложение не передаёт никогда.** Её передают люди сами и тем способом, каким
 * считают нужным; здесь ходит только код. Пока код и фраза идут разными путями, перехват
 * одного пути ничего не даёт — и это единственное, на чём держится предъявительский код.
 */
class TransferVirtual(
    private val api: TransfersApi,
    private val keys: DeviceKeyFactory,
    private val prover: TransferProver,
    private val platform: String,
) {

    /** Сторона передающего: выдать код. Прежний код при этом гаснет — так решает сервер. */
    suspend fun start(virtualUserId: String): TransferStartStep = api.start(virtualUserId)

    /** Сторона передающего: передумал. До предъявления кода передача не состоялась. */
    suspend fun cancel(virtualUserId: String): Boolean = api.cancel(virtualUserId)

    /**
     * Сторона принимающего: код плюс фраза.
     *
     * Ключи устройства порождаются здесь и сохраняются вызывающим после успеха — так же и
     * по той же причине, что в [CreateVirtual]: до появления ответа записать их некуда,
     * имя ящика содержит `userId`.
     *
     * @param words фраза **передаваемого аккаунта**, а не своя: ею и доказывается право.
     */
    suspend fun accept(code: String, words: List<String>): TransferAcceptStep {
        // Фраза разбирается на месте: не сложились слова — сети не касаемся. Спешка на
        // этом шаге стоит попытки, а их всего три.
        val proof = prover.prove(words, code) ?: return TransferAcceptStep.BadPhrase

        val material = keys.newDeviceKeys()
        return when (
            val answer = api.accept(
                code = code,
                proof = proof,
                encryptionPub = material.encryptionPub,
                signingPub = material.signingPub,
                platform = platform,
            )
        ) {
            is TransferAcceptStep.Taken -> answer.copy(deviceSecret = material.secret)
            else -> answer
        }
    }
}

/** Чем кончилась выдача кода. */
sealed interface TransferStartStep {

    /**
     * Код выдан. Показывается **один раз**: в базе лежит его хэш, и показать второй раз
     * его нечем — можно только выдать новый, погасив этот.
     */
    data class Code(
        val code: String,
        /** Сколько ему жить. Считает сервер: на клиенте срок означал бы «сколько захочу». */
        val secondsLeft: Int,
        /** Сколько неверных фраз он переживёт. */
        val attempts: Int,
    ) : TransferStartStep

    /** Это не ваш виртуальный аккаунт либо его нет вовсе. */
    data object NotYours : TransferStartStep
    data object Offline : TransferStartStep
}

/** Чем кончилось предъявление кода. */
sealed interface TransferAcceptStep {

    /**
     * Аккаунт перешёл. Устройства прежнего владельца отозваны, токен выдан нам.
     *
     * @param rotateNeeded групповые ключи придётся ротировать: сервер этого сделать не
     *   может, их выпускают участники (ADR-0017). До ротации прежний владелец продолжает
     *   читать группы, где аккаунт состоит, — и человеку это надо сказать.
     */
    data class Taken(
        val session: Session,
        val rotateNeeded: Boolean,
        /** Заполняется после порождения ключей: сохранить его — дело вызывающего. */
        val deviceSecret: ByteArray = ByteArray(0),
    ) : TransferAcceptStep {
        fun copy(deviceSecret: ByteArray) = Taken(session, rotateNeeded, deviceSecret)
    }

    /**
     * Кода нет, он погашен или просрочен.
     *
     * Три случая слиты **сервером**, а не нами: различать их значило бы сообщать
     * предъявителю, существовала ли передача вообще.
     */
    data object CodeGone : TransferAcceptStep

    /** Фраза не подходит. Попытка потрачена. */
    data object BadPhrase : TransferAcceptStep

    /** Третья неверная фраза. Код мёртв — нужен новый, и выдать его может только владелец. */
    data object Burned : TransferAcceptStep

    data object Offline : TransferAcceptStep
}

/**
 * Подпись кода передачи ключом личности передаваемого аккаунта.
 *
 * Порт отдельно от [IdentitySigner], потому что подписывается **не то, что видно**: код
 * приходит строкой в её транспортном виде, и превратить её в байты — знание кодировки, а
 * не продукта. Для этого слоя код — непрозрачная строка от сервера, и это правильно:
 * ничего, кроме «показать и передать», он с ней не делает.
 *
 * @return `null`, если слова не складываются в личность либо код не разбирается.
 */
fun interface TransferProver {
    fun prove(words: List<String>, code: String): ByteArray?
}

/** Порт к серверу: три ручки Д12. Реализуется в `core-network`. */
interface TransfersApi {
    suspend fun start(virtualUserId: String): TransferStartStep
    suspend fun cancel(virtualUserId: String): Boolean
    suspend fun accept(
        code: String,
        proof: ByteArray,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        platform: String,
    ): TransferAcceptStep
}
