package io.tima.domain.account

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Заведение виртуального аккаунта: что подписывается, чем кончается, чего не происходит.
 *
 * Без сети и без крипты: и то и другое — порты. Предмет проверки здесь один — порядок и
 * состав шагов, то есть правило продукта.
 */
class CreateVirtualTest {

    private val material = DeviceKeyMaterial(
        encryptionPub = ByteArray(32) { 1 },
        signingPub = ByteArray(32) { 2 },
        secret = ByteArray(32) { 3 },
    )

    private val identityPub = ByteArray(32) { 7 }

    private class Server(
        val answer: VirtualCreateStep = VirtualCreateStep.Created("u-2", "d-2", "jwt-2"),
    ) : VirtualsApi {
        var calls = 0
        var sentNickname: String? = null
        var sentIdentity: ByteArray? = null
        var sentSignature: ByteArray? = null
        var sentKeys: Pair<ByteArray, ByteArray>? = null

        override suspend fun create(
            nickname: String,
            identityPub: ByteArray,
            signature: ByteArray,
            encryptionPub: ByteArray,
            signingPub: ByteArray,
            platform: String,
        ): VirtualCreateStep {
            calls++
            sentNickname = nickname
            sentIdentity = identityPub
            sentSignature = signature
            sentKeys = encryptionPub to signingPub
            return answer
        }

        override suspend fun mine(): List<VirtualAccount>? = emptyList()
    }

    /** Подписывающий, который запоминает, что ему дали подписать. */
    private class Signer(val fails: Boolean = false) : IdentitySigner {
        var signedBytes: ByteArray? = null
        var signedWords: List<String>? = null
        override fun sign(words: List<String>, bytes: ByteArray): ByteArray? {
            signedWords = words
            signedBytes = bytes
            return if (fails) null else ByteArray(64) { 9 }
        }
    }

    private fun создай(
        server: VirtualsApi,
        signer: IdentitySigner = Signer(),
    ) = CreateVirtual(
        api = server,
        keys = { material },
        identities = object : AccountIdentities {
            override fun fresh() = NewAccountIdentity(words = List(12) { "слово$it" }, identityPub = identityPub)
            override fun fromWords(words: List<String>): ByteArray = identityPub
        },
        signer = signer,
        platform = "android",
    )

    @Test
    fun подписывается_ник_и_ключ_вместе_через_перевод_строки() = runTest {
        val signer = Signer()
        val server = Server()
        создай(server, signer).create("petr_smirnov", List(12) { "слово" })

        // Байт в байт как считает сервер (api/virtuals.go, virtualSigned). Разойдись
        // это на один байт — подпись не сойдётся, и выглядеть будет как «сервер нас не
        // пускает», а не как расхождение форматов.
        val ожидалось = "petr_smirnov".encodeToByteArray() + 10.toByte() + identityPub
        assertContentEquals(ожидалось, signer.signedBytes)
    }

    @Test
    fun готово_отдаёт_сессию_секрет_и_слова_нового_аккаунта() = runTest {
        val server = Server()
        val step = создай(server).create("petr_smirnov", List(12) { "слово" })

        val готово = assertIs<VirtualStep.Created>(step)
        assertEquals(Session("u-2", "d-2", "jwt-2"), готово.session)
        // Секрет тот же, чьи открытые части ушли на сервер: иначе аккаунт был бы заведён
        // под ключи, которых у нас нет.
        assertContentEquals(material.secret, готово.deviceSecret)
        assertContentEquals(material.encryptionPub, server.sentKeys?.first)
        assertContentEquals(material.signingPub, server.sentKeys?.second)
        assertEquals(12, готово.words.size)
        assertEquals("petr_smirnov", готово.nickname)
    }

    @Test
    fun негодный_ник_до_сети_не_доходит() = runTest {
        val server = Server()
        assertEquals(VirtualStep.BadNickname, создай(server).create("petr", List(12) { "слово" }))
        assertEquals(0, server.calls, "короткий ник ушёл на сервер — проверка границ не сработала")
    }

    @Test
    fun не_та_фраза_владельца_до_сети_не_доходит() = runTest {
        val server = Server()
        val step = создай(server, Signer(fails = true)).create("petr_smirnov", listOf("не", "та"))

        assertEquals(VirtualStep.BadPhrase, step)
        assertEquals(0, server.calls, "с неподписанным запросом пошли на сервер")
    }

    @Test
    fun ответ_сервера_переводится_словами_продукта() = runTest {
        val переводы = listOf(
            VirtualCreateStep.NicknameTaken to VirtualStep.NicknameTaken,
            VirtualCreateStep.TooMany to VirtualStep.TooMany,
            VirtualCreateStep.NotAllowed to VirtualStep.NotAllowed,
            VirtualCreateStep.Offline to VirtualStep.Offline,
            // Подпись не сошлась — для человека это та же «не та фраза»: другой фразы у
            // него нет, и различать ему нечего.
            VirtualCreateStep.BadSignature to VirtualStep.BadPhrase,
        )
        for ((ответ, ожидание) in переводы) {
            assertEquals(ожидание, создай(Server(ответ)).create("petr_smirnov", List(12) { "слово" }))
        }
    }

    @Test
    fun ник_обрезается_по_краям() = runTest {
        val server = Server()
        создай(server).create("  petr_smirnov  ", List(12) { "слово" })
        assertEquals("petr_smirnov", server.sentNickname)
    }

    @Test
    fun границы_ника_те_же_что_у_профиля() {
        assertTrue(nicknameFits("petr_smirnov"))
        assertTrue(!nicknameFits("petr"), "короче десяти знаков ник занимать нельзя")
        assertTrue(!nicknameFits("пётр_смирнов"), "кириллица в нике не годится")
    }
}
