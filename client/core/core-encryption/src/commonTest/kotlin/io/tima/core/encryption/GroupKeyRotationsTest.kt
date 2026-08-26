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
    private val rotation = GroupKeyRotations(EscrowEpochKey(escrow.first, version = 3))

    private val stays = DeviceIdentity.generate()
    private val second = DeviceIdentity.generate()
    private val removed = DeviceIdentity.generate()

    private fun address(id: String, who: DeviceIdentity) = RecipientDevice(id, who.encryptionPublic)

    @Test
    fun версия_на_единицу_больше_серверной() {
        // Не «наша + 1»: наша могла отстать, и тогда сервер ответит version_conflict.
        val issue = rotation.rotate(7, listOf(address("dev-1", stays))).getOrThrow()
        assertEquals(8, issue.gkVersion)
    }

    @Test
    fun каждому_получателю_своя_обёртка_и_все_дают_один_ключ() {
        val issue = rotation.rotate(
            currentVersion = 0,
            recipients = listOf(address("dev-1", stays), address("dev-2", second)),
        ).getOrThrow()

        assertEquals(setOf("dev-1", "dev-2"), issue.wrappedKeys.keys)

        // Оба устройства обязаны прийти к ОДНОМУ ключу: иначе половина группы читала бы
        // одно, половина другое, и расхождение вылезло бы только на живой переписке.
        for ((device, who) in listOf("dev-1" to stays, "dev-2" to second)) {
            val unwrapped = GroupKeyManager.unwrapGroupKey(
                deviceKey = who.key,
                senderEphemeralPub = issue.senderEphemeralPub,
                wrappedGk = issue.wrappedKeys.getValue(device),
            ).getOrThrow()
            assertContentEquals(issue.groupKey, unwrapped, "устройство $device получило другой ключ")
        }
    }

    @Test
    fun исключённый_новую_версию_не_разворачивает() {
        // Смысл ротации при выходе участника. Ему не запрещают — ему просто не создают
        // обёртку, и старые ключи новых сообщений не открывают.
        val issue = rotation.rotate(1, listOf(address("dev-1", stays))).getOrThrow()

        assertNull(issue.wrappedKeys["dev-исключённый"])
        val foreign = issue.wrappedKeys.getValue("dev-1")
        val attempt = GroupKeyManager.unwrapGroupKey(removed.key, issue.senderEphemeralPub, foreign)
        assertTrue(attempt.isFailure, "исключённый развернул чужую обёртку")
    }

    @Test
    fun ключ_на_сервер_не_уходит_а_escrow_уходит() {
        val issue = rotation.rotate(0, listOf(address("dev-1", stays))).getOrThrow()

        assertEquals(3, issue.escrowKeyVersion, "версия ключа эпохи обязана ехать с блобом")
        assertTrue(issue.escrowMlkemCt.isNotEmpty() && issue.escrowWrappedKey.isNotEmpty())

        // Ключ не должен встречаться ни в одной части того, что уедет на сервер.
        val outward = issue.escrowMlkemCt + issue.escrowWrappedKey +
            issue.senderEphemeralPub + issue.wrappedKeys.values.fold(ByteArray(0)) { a, b -> a + b }
        assertTrue(!contains(outward, issue.groupKey), "групповой ключ уехал бы открытым")
    }

    @Test
    fun ротация_без_получателей_отвергается() {
        // Иначе группа осталась бы с версией, которой ни у кого нет ключа, — и замолчала
        // бы навсегда, без единой ошибки где-либо.
        assertFailsWith<IllegalArgumentException> {
            rotation.rotate(1, emptyList()).getOrThrow()
        }
    }

    private fun contains(where: ByteArray, what: ByteArray): Boolean {
        if (what.isEmpty() || where.size < what.size) return false
        outer@ for (i in 0..(where.size - what.size)) {
            for (j in what.indices) if (where[i + j] != what[j]) continue@outer
            return true
        }
        return false
    }
}
