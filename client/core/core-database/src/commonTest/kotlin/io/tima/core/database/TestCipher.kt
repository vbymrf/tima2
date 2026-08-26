package io.tima.core.database

import io.tima.core.encryption.LocalStoreFieldCipher
import io.tima.core.outbox.FieldCipher

/**
 * Шифр покоя для проверок — **настоящий**, с постоянным секретом устройства.
 *
 * Подделки здесь нет намеренно. Сквозной «шифр», отдающий байты как есть, — это способ
 * получить зелёные тесты при открытой базе, то есть ровно то, от чего шифрование покоя и
 * защищает. Настоящий шифр стоит одного вывода HKDF на весь набор проверок.
 */
internal fun testCipher(): FieldCipher = LocalStoreFieldCipher(TEST_SECRET)

/** Секрет устройства для проверок. Постоянный: проверкам нужна повторяемость. */
internal val TEST_SECRET: ByteArray = ByteArray(32) { (it + 7).toByte() }
