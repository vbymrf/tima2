package io.tima.harness

import io.tima.core.encryption.LocalStoreFieldCipher
import io.tima.core.outbox.FieldCipher

/**
 * Шифр покоя харнесса — **настоящий**, с постоянным секретом устройства.
 *
 * Подделки нет намеренно: сквозной «шифр» дал бы зелёные сценарии при открытой базе, то
 * есть ровно то состояние, из которого шифрование покоя и вытаскивали. Настоящий стоит
 * одного вывода HKDF на сценарий.
 */
fun cipherHarness(): FieldCipher = LocalStoreFieldCipher(SECRET_HARNESS)

/** Секрет устройства для сценариев. Постоянный: сценариям нужна повторяемость. */
val SECRET_HARNESS: ByteArray = ByteArray(32) { (it + 11).toByte() }
