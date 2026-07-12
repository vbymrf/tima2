package io.tima.crypto

import io.kodium.Kodium
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Семантика ротации GK (crypto-protocol.md §4) и шифрования медиа (§5), включая KAT ключей чанков. */
class GroupAndMediaTest {

    private val escrowKeyPair = Mlkem768.keyPair()
    private val manager = GroupKeyManager(EscrowModule(escrowKeyPair.first, escrowKeyVersion = 1))

    private val alice = Kodium.generateKeyPair()
    private val bob = Kodium.generateKeyPair()
    private val eve = Kodium.generateKeyPair() // исключаемый участник

    private fun devices(vararg pairs: Pair<String, io.kodium.KodiumPrivateKey>) =
        pairs.map { (id, key) -> DeviceAddress(id, key.getPublicKey().encryptionKey) }

    @Test
    fun `ротация - все устройства разворачивают GK, версия растёт`() {
        val rotation = manager.rotate(currentVersion = 0, devices = devices("alice-1" to alice, "bob-1" to bob)).getOrThrow()
        assertEquals(1, rotation.gkVersion)
        assertEquals(setOf("alice-1", "bob-1"), rotation.wrappedKeys.keys)

        for ((deviceId, key) in listOf("alice-1" to alice, "bob-1" to bob)) {
            val gk = GroupKeyManager.unwrapGroupKey(key, rotation.senderEphemeralPub, rotation.wrappedKeys[deviceId]!!).getOrThrow()
            assertEquals(rotation.groupKey.toHex(), gk.toHex())
        }
    }

    @Test
    fun `сообщение группы шифруется GK и читается участником`() {
        val rotation = manager.rotate(0, devices("alice-1" to alice, "bob-1" to bob)).getOrThrow()
        val body = "Групповое сообщение · 群聊 · 👥".encodeToByteArray()

        val payload = EnvelopeCipher.seal(rotation.groupKey, body).getOrThrow()
        val gkAtBob = GroupKeyManager.unwrapGroupKey(bob, rotation.senderEphemeralPub, rotation.wrappedKeys["bob-1"]!!).getOrThrow()
        assertEquals(body.toHex(), EnvelopeCipher.open(gkAtBob, payload).getOrThrow().toHex())
    }

    @Test
    fun `исключение участника - новый GK ему не выдаётся и старым ключом не читается`() {
        val v1 = manager.rotate(0, devices("alice-1" to alice, "bob-1" to bob, "eve-1" to eve)).getOrThrow()
        // Ева исключена → ротация по списку без неё
        val v2 = manager.rotate(v1.gkVersion, devices("alice-1" to alice, "bob-1" to bob)).getOrThrow()

        assertEquals(2, v2.gkVersion)
        assertFalse("eve-1" in v2.wrappedKeys, "Исключённому устройству wrapped_GK не создаётся")
        assertFalse(v1.groupKey.contentEquals(v2.groupKey), "GK обязан смениться")

        // Сообщение новым GK не открывается старым ключом Евы
        val payload = EnvelopeCipher.seal(v2.groupKey, "после исключения".encodeToByteArray()).getOrThrow()
        assertTrue(EnvelopeCipher.open(v1.groupKey, payload).isFailure)
    }

    @Test
    fun `escrow - анклав восстанавливает GK одной операцией на версию`() {
        val rotation = manager.rotate(0, devices("alice-1" to alice)).getOrThrow()
        val gk = EscrowModule.unwrap(rotation.escrow, escrowKeyPair.second).getOrThrow()
        assertEquals(rotation.groupKey.toHex(), gk.toHex())
        assertEquals(1, rotation.escrow.escrowKeyVersion)
    }

    // ── Медиа ──

    @Test
    fun `media_chunk_keys - деривация ключей чанков совпадает с вектором байт-в-байт`() {
        val vectors = Json.parseToJsonElement(
            javaClass.getResourceAsStream("/vectors.json")!!.readBytes().decodeToString(),
        ).jsonObject["vectors"]!!.jsonObject
        val v = vectors["media_chunk_keys"]?.jsonObject ?: fail("Вектор 'media_chunk_keys' отсутствует")
        val mediaKey = v["media_key"]!!.jsonPrimitive.content.hexToBytes()

        for ((field, index) in listOf("chunk_0" to 0, "chunk_1" to 1, "chunk_10" to 10)) {
            assertEquals(
                v[field]!!.jsonPrimitive.content,
                MediaCipher.chunkKey(mediaKey, index).toHex(),
                "chunk_key[$index] разошёлся с эталоном",
            )
        }
    }

    @Test
    fun `медиа целиком - roundtrip`() {
        val mediaKey = Kodium.generateHighEntropyKey()
        val voice = ByteArray(48 * 1024) { (it % 251).toByte() } // «голосовое» 48 KB
        val sealed = MediaCipher.seal(mediaKey, voice).getOrThrow()
        assertEquals(voice.toHex(), MediaCipher.open(mediaKey, sealed).getOrThrow().toHex())
    }

    @Test
    fun `чанки - roundtrip и защита от перестановки`() {
        val mediaKey = Kodium.generateHighEntropyKey()
        val chunk0 = ByteArray(1024) { 0x0a }
        val chunk1 = ByteArray(1024) { 0x0b }

        val sealed0 = MediaCipher.sealChunk(mediaKey, 0, chunk0).getOrThrow()
        val sealed1 = MediaCipher.sealChunk(mediaKey, 1, chunk1).getOrThrow()

        assertEquals(chunk0.toHex(), MediaCipher.openChunk(mediaKey, 0, sealed0).getOrThrow().toHex())
        assertEquals(chunk1.toHex(), MediaCipher.openChunk(mediaKey, 1, sealed1).getOrThrow().toHex())

        // Чанк, подсунутый под чужим индексом, не расшифруется (у каждого индекса свой ключ)
        assertTrue(MediaCipher.openChunk(mediaKey, 0, sealed1).isFailure)
        assertTrue(MediaCipher.openChunk(mediaKey, 1, sealed0).isFailure)
    }
}
