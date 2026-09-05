package io.tima.domain.account

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Передача виртуального аккаунта: что подписывается, что доходит до сети, а что нет.
 *
 * Крипта и сеть — порты: предмет проверки здесь один, порядок и состав шагов.
 */
class TransferVirtualTest {

    private val material = DeviceKeyMaterial(
        encryptionPub = ByteArray(32) { 1 },
        signingPub = ByteArray(32) { 2 },
        secret = ByteArray(32) { 3 },
    )

    private class Server(
        val start: TransferStartStep = TransferStartStep.Code("code-1", 1800, 3),
        val accept: TransferAcceptStep = TransferAcceptStep.Taken(
            session = Session("u-9", "d-9", "jwt-9"),
            rotateNeeded = true,
        ),
    ) : TransfersApi {
        var accepts = 0
        var cancels = 0
        var sentKeys: Pair<ByteArray, ByteArray>? = null
        var sentProof: ByteArray? = null

        override suspend fun start(virtualUserId: String) = start
        override suspend fun cancel(virtualUserId: String): Boolean {
            cancels++
            return true
        }

        override suspend fun accept(
            code: String,
            proof: ByteArray,
            encryptionPub: ByteArray,
            signingPub: ByteArray,
            platform: String,
        ): TransferAcceptStep {
            accepts++
            sentProof = proof
            sentKeys = encryptionPub to signingPub
            return accept
        }
    }

    private fun передача(server: TransfersApi, prover: TransferProver = TransferProver { _, _ -> ByteArray(64) { 5 } }) =
        TransferVirtual(api = server, keys = { material }, prover = prover, platform = "android")

    @Test
    fun принятый_аккаунт_приносит_и_секрет_устройства() = runTest {
        val server = Server()
        val step = передача(server).accept("code-1", List(12) { "слово" })

        val взяли = assertIs<TransferAcceptStep.Taken>(step)
        assertEquals(Session("u-9", "d-9", "jwt-9"), взяли.session)
        // Без секрета устройства токен бесполезен: расшифровать адресованное ему нечем.
        assertContentEquals(material.secret, взяли.deviceSecret)
        assertContentEquals(material.encryptionPub, server.sentKeys?.first)
        assertContentEquals(material.signingPub, server.sentKeys?.second)
        // Ротация нужна: сервер её сделать не может, ключи выпускают участники.
        assertEquals(true, взяли.rotateNeeded)
    }

    @Test
    fun не_та_фраза_до_сети_не_доходит() = runTest {
        val server = Server()
        val step = передача(server, TransferProver { _, _ -> null }).accept("code-1", listOf("не", "та"))

        assertEquals(TransferAcceptStep.BadPhrase, step)
        // Попытка потрачена не была бы зря: их всего три, и тратить их на опечатку,
        // которую видно на месте, нельзя.
        assertEquals(0, server.accepts, "с неподписанным кодом пошли на сервер")
    }

    @Test
    fun сгоревший_код_отличается_от_неверной_фразы() = runTest {
        // Разница не косметическая: после «фраза не подходит» пробуют снова, после
        // «код сгорел» пробовать нечего — нужен новый код от прежнего владельца.
        val сгорел = передача(Server(accept = TransferAcceptStep.Burned)).accept("code-1", List(12) { "с" })
        assertEquals(TransferAcceptStep.Burned, сгорел)

        val нетКода = передача(Server(accept = TransferAcceptStep.CodeGone)).accept("code-1", List(12) { "с" })
        assertEquals(TransferAcceptStep.CodeGone, нетКода)
    }

    @Test
    fun отмена_доходит_до_сервера() = runTest {
        val server = Server()
        assertEquals(true, передача(server).cancel("v-1"))
        assertEquals(1, server.cancels)
    }
}
