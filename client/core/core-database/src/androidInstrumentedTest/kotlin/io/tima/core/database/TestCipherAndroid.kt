package io.tima.core.database

import io.tima.core.encryption.LocalStoreFieldCipher
import io.tima.core.outbox.FieldCipher

/**
 * Шифр покоя для инструментальных проверок — **настоящий**, как и в общих тестах.
 *
 * **Почему не переиспользован тот же helper.** `androidInstrumentedTest` — отдельный
 * source set, и `commonTest` он не видит: тесты на устройстве собираются своим
 * компилятором и своим набором исходников. Связывать их через `dependsOn` дороже, чем
 * повторить четыре строки: связка тянет в инструментальный набор весь общий тестовый код,
 * включая тот, что рассчитан на драйвер в памяти.
 *
 * Подделки здесь нет намеренно, по той же причине, что и в общих тестах: сквозной
 * «шифр», отдающий байты как есть, — способ получить зелёные проверки при открытой базе.
 */
internal fun testCipher(): FieldCipher = LocalStoreFieldCipher(TEST_SECRET_ANDROID)

/** Тот же постоянный секрет, что и в общих проверках: сверки должны совпадать. */
internal val TEST_SECRET_ANDROID: ByteArray = ByteArray(32) { (it + 7).toByte() }
