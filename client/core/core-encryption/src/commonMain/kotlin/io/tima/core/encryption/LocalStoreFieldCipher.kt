package io.tima.core.encryption

import io.tima.core.outbox.FieldCipher
import io.tima.crypto.LocalStoreCipher

/**
 * Шифр покоя локальной базы — переходник к порту `core-outbox`.
 *
 * Ключ выводится из **секрета устройства** (`core-secrets`), а не хранится рядом с базой:
 * в v1 на ПК секрет лежал открытым файлом рядом с файлом базы, и шифрование покоя было
 * декоративным — кто дошёл до базы, доходил и до ключа.
 *
 * Ключ считается один раз, при создании: вывод HKDF на каждое поле стоил бы заметно, а
 * ключ один на всю базу.
 */
class LocalStoreFieldCipher(deviceSecret: ByteArray) : FieldCipher {

    private val key: ByteArray = LocalStoreCipher.keyFromDeviceSecret(deviceSecret)

    /** Отказ шифрования — ошибка в коде, а не состояние данных: поэтому исключение. */
    override fun seal(plaintext: ByteArray): ByteArray =
        LocalStoreCipher.seal(key, plaintext).getOrThrow()

    /** Не открылось — обычное состояние: чужая установка, испорченный файл. */
    override fun open(sealed: ByteArray): ByteArray? = LocalStoreCipher.open(key, sealed).getOrNull()
}
