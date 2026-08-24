package io.tima.core.encryption

import io.kodium.Kodium
import io.tima.crypto.WrappedKeyService

/**
 * Поделиться версией группового ключа с устройством, которому её не выдавали.
 *
 * **Почему это отдельно от ротации.** Ротация выпускает НОВУЮ версию и раздаёт её всем;
 * здесь мы отдаём СТАРУЮ — ту, что у нас уже есть, — одному устройству, которое пришло в
 * группу позже и потому обёртки не получало. Новых версий не появляется, состав не
 * меняется, и версия у всех остаётся прежней.
 *
 * **Эфемерная пара своя на каждую обёртку.** Переиспользовать одну на все версии
 * заманчиво — меньше вычислений, — но тогда компрометация этой пары раскрывает сразу всю
 * отданную историю, а не одну её версию. Экономия здесь стоит дешевле, чем то, что она
 * покупает.
 */
object GroupKeyShares {

    fun wrap(recipientEncryptionPub: ByteArray, groupKey: ByteArray): Result<SharedGroupKey> =
        runCatching {
            require(recipientEncryptionPub.size == 32) { "открытый ключ устройства — 32 байта" }
            val эфемерная = Kodium.generateKeyPair()
            SharedGroupKey(
                senderEphemeralPub = эфемерная.getPublicKey().encryptionKey,
                wrapped = WrappedKeyService.wrap(эфемерная, recipientEncryptionPub, groupKey).getOrThrow(),
            )
        }
}

/** Обёртка одной версии GK под конкретное устройство. */
class SharedGroupKey(val senderEphemeralPub: ByteArray, val wrapped: ByteArray)
