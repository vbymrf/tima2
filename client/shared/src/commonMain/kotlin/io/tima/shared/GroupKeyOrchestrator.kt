package io.tima.shared

import io.tima.core.encryption.GroupKeyUnwrapOverKodium
import io.tima.core.encryption.GroupKeyWrapOverKodium
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.database.SqlGroupKeys
import io.tima.core.network.EventStreamProtocol
import io.tima.core.network.GroupKeyRecoveryOverHttp
import io.tima.core.network.GroupKeyWrapsOverHttp
import io.tima.domain.chat.ShareGroupKeys
import io.tima.domain.chat.SyncGroupKeys

/**
 * Кадры живого канала про групповые ключи: сходить за своим, отдать чужому, ротировать.
 *
 * ── ПОЧЕМУ ОТДЕЛЬНО ОТ ПРИЁМНИКА ────────────────────────────────────────────
 *
 * Приёмник держит канал и разбирает сообщения. Ключи ехали тем же каналом, и он же
 * их и собирал: три подсистемы прямо в его конструкторе — сверка, раздача, ротация.
 * Смысла в этом не было: канал только приносит кадры, а выполняет их другая работа,
 * которой нужны escrow, крипта, сеть и хранилище разом.
 *
 * Теперь приёмник получает готовый оркестр и знает про него одно: «вот кадр».
 *
 * **Ни один из трёх исходов не роняет канал.** Ключи — работа рядом с доставкой, а не
 * вместо неё: не удалось сходить за обёртками — сообщения всё равно должны идти.
 * Поэтому провал остаётся строкой диагностики, а не летит наверх.
 */
class ОркестрГрупповыхКлючей(
    окружение: Окружение,
    сеть: ПортыГрупп,
    личность: DeviceIdentity,
    private val сейчасМс: () -> Long,
) {
    private val ключиГруппы = SqlGroupKeys(окружение.db, окружение.шифр)

    private val сверка = SyncGroupKeys(
        wraps = GroupKeyWrapsOverHttp(сеть.ключиГрупп),
        unwrap = GroupKeyUnwrapOverKodium(личность),
        keys = ключиГруппы,
    )

    private val раздача = ShareGroupKeys(
        keys = ключиГруппы,
        wrap = GroupKeyWrapOverKodium,
        upload = GroupKeyRecoveryOverHttp(сеть.восстановлениеКлючейГрупп),
    )

    private val ротация = РотацияГрупповогоКлюча(
        группы = сеть.группы,
        ключиУстройств = сеть.ключи,
        escrow = сеть.escrow,
        ключиГрупп = сеть.ключиГрупп,
        книга = ключиГруппы,
        сейчасМс = сейчасМс,
    )

    /** Ключи этого устройства: их читает разбор сообщений группы. */
    val ключи: SqlGroupKeys get() = ключиГруппы

    /**
     * Обработать кадр. Возвращает строку для диагностики — ту же, что раньше писал
     * приёмник: по ней на живом прогоне видно, что происходило с ключами.
     */
    suspend fun обработать(решение: EventStreamProtocol.Decision): String? = runCatching {
        when (решение) {
            // Ротация и приезд обёрток означают одно: сходить за тем, чего у нас нет.
            is EventStreamProtocol.Decision.KeysArrived ->
                "ключи группы: ${сверка.обновить(решение.groupId)}"

            // Просят у нас — значит, у нас эти версии есть. Молчание оставит человека
            // ждать вечно: другого способа получить историю до своего прихода у него нет.
            is EventStreamProtocol.Decision.ShareKeys ->
                "отдали ключи: " + раздача.поделиться(
                    groupId = решение.groupId,
                    requesterDevice = решение.requesterDevice,
                    requesterEncryptionPub = решение.requesterEncryptionPub,
                    versions = решение.versions,
                )

            // Сервер сам ротировать не может — ключа он не видит (ADR-0017 §3).
            is EventStreamProtocol.Decision.RotationNeeded ->
                "ротация по просьбе сервера (${решение.reason}): " +
                    ротация.ротировать(решение.groupId)

            else -> null
        }
    }.getOrElse { "кадр про ключи не обработан: ${it.message}" }
}
