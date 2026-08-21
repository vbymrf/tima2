package io.tima.core.encryption

import io.tima.domain.account.DeviceKeyFactory
import io.tima.domain.account.DeviceKeyMaterial

/**
 * Порождение ключей устройства — переходник к порту `domain-account`.
 *
 * Оба открытых ключа выводятся из **одного** 32-байтного секрета: так устроен Kodium,
 * и так же устроен сервер, который принимает их по отдельности. Значит хранить надо
 * ровно этот секрет, а не два ключа: из него восстанавливаются оба, плюс ключ покоя
 * локальной базы.
 *
 * Смена способа вывода сломала бы ключи у всех устройств — это миграция аккаунтов, а
 * не рефакторинг (`crypto-invariants`: `KodiumPrivateKey.fromRaw` менять нельзя).
 */
object DeviceKeyFactoryOverKodium : DeviceKeyFactory {

    override fun newDeviceKeys(): DeviceKeyMaterial {
        val identity = DeviceIdentity.generate()
        return DeviceKeyMaterial(
            encryptionPub = identity.encryptionPublic,
            signingPub = identity.signingPublic,
            secret = identity.exportRaw(),
        )
    }
}

/**
 * Восстановление личности устройства из сохранённого секрета.
 *
 * Отдельная функция, потому что путь другой: при запуске приложения ключи не
 * порождаются, а достаются из [io.tima.core.secrets.SecretVault]. Спутать эти два пути
 * — значит на каждом запуске заводить новое устройство.
 */
fun deviceIdentityFrom(secret: ByteArray): DeviceIdentity {
    require(secret.size == DEVICE_SECRET_BYTES) {
        "секрет устройства обязан быть $DEVICE_SECRET_BYTES байт, а не ${secret.size}"
    }
    return DeviceIdentity.fromRaw(secret)
}

/** Размер секрета устройства. Задан Kodium, а не выбран нами. */
const val DEVICE_SECRET_BYTES: Int = 32
