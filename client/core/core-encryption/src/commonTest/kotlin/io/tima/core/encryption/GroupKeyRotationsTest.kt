package io.tima.core.encryption

import io.tima.crypto.GroupKeyManager
import io.tima.crypto.Mlkem768
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ротация: кто получит ключ и кто не получит.
 *
 * Проверяется не «вызвалось», а свойство, ради которого ротация существует: устройство, не
 * попавшее в список получателей, новую версию развернуть не может. Это и есть отключение
 * исключённого участника — не запрет на сервере, а отсутствие обёртки.
 */
class GroupKeyRotationsTest {

    private val escrow = Mlkem768.keyPair()
    private val ротации = GroupKeyRotations(EscrowEpochKey(escrow.first, version = 3))

    private val остаётся = DeviceIdentity.generate()
    private val второе = DeviceIdentity.generate()
    private val исключённый = DeviceIdentity.generate()

    private fun адрес(id: String, кто: DeviceIdentity) = RecipientDevice(id, кто.encryptionPublic)

    @Test
    fun версия_на_единицу_больше_серверной() {
        // Не «наша + 1»: наша могла отстать, и тогда сервер ответит version_conflict.
        val выпуск = ротации.rotate(7, listOf(адрес("dev-1", остаётся))).getOrThrow()
        assertEquals(8, выпуск.gkVersion)
    }

    @Test
    fun каждому_получателю_своя_обёртка_и_все_дают_один_ключ() {
        val выпуск = ротации.rotate(
            currentVersion = 0,
            recipients = listOf(адрес("dev-1", остаётся), адрес("dev-2", второе)),
        ).getOrThrow()

        assertEquals(setOf("dev-1", "dev-2"), выпуск.wrappedKeys.keys)

        // Оба устройства обязаны прийти к ОДНОМУ ключу: иначе половина группы читала бы
        // одно, половина другое, и расхождение вылезло бы только на живой переписке.
        for ((устройство, кто) in listOf("dev-1" to остаётся, "dev-2" to второе)) {
            val развёрнутый = GroupKeyManager.unwrapGroupKey(
                deviceKey = кто.key,
                senderEphemeralPub = выпуск.senderEphemeralPub,
                wrappedGk = выпуск.wrappedKeys.getValue(устройство),
            ).getOrThrow()
            assertContentEquals(выпуск.groupKey, развёрнутый, "устройство $устройство получило другой ключ")
        }
    }

    @Test
    fun исключённый_новую_версию_не_разворачивает() {
        // Смысл ротации при выходе участника. Ему не запрещают — ему просто не создают
        // обёртку, и старые ключи новых сообщений не открывают.
        val выпуск = ротации.rotate(1, listOf(адрес("dev-1", остаётся))).getOrThrow()

        assertNull(выпуск.wrappedKeys["dev-исключённый"])
        val чужая = выпуск.wrappedKeys.getValue("dev-1")
        val попытка = GroupKeyManager.unwrapGroupKey(исключённый.key, выпуск.senderEphemeralPub, чужая)
        assertTrue(попытка.isFailure, "исключённый развернул чужую обёртку")
    }

    @Test
    fun ключ_на_сервер_не_уходит_а_escrow_уходит() {
        val выпуск = ротации.rotate(0, listOf(адрес("dev-1", остаётся))).getOrThrow()

        assertEquals(3, выпуск.escrowKeyVersion, "версия ключа эпохи обязана ехать с блобом")
        assertTrue(выпуск.escrowMlkemCt.isNotEmpty() && выпуск.escrowWrappedKey.isNotEmpty())

        // Ключ не должен встречаться ни в одной части того, что уедет на сервер.
        val наружу = выпуск.escrowMlkemCt + выпуск.escrowWrappedKey +
            выпуск.senderEphemeralPub + выпуск.wrappedKeys.values.fold(ByteArray(0)) { a, b -> a + b }
        assertTrue(!содержит(наружу, выпуск.groupKey), "групповой ключ уехал бы открытым")
    }

    @Test
    fun ротация_без_получателей_отвергается() {
        // Иначе группа осталась бы с версией, которой ни у кого нет ключа, — и замолчала
        // бы навсегда, без единой ошибки где-либо.
        assertFailsWith<IllegalArgumentException> {
            ротации.rotate(1, emptyList()).getOrThrow()
        }
    }

    private fun содержит(где: ByteArray, что: ByteArray): Boolean {
        if (что.isEmpty() || где.size < что.size) return false
        outer@ for (i in 0..(где.size - что.size)) {
            for (j in что.indices) if (где[i + j] != что[j]) continue@outer
            return true
        }
        return false
    }
}
