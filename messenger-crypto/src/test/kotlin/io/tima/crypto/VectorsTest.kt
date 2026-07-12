package io.tima.crypto

import io.kodium.KodiumPrivateKey
import io.kodium.core.nacl
import io.kodium.ratchet.HKDF
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * KAT против канонических векторов schema/test-vectors/vectors.json.
 * Эталон — tweetnacl-js + noble; Kodium обязан совпасть байт-в-байт.
 * Расхождение = красный билд, а не «подстроить тест».
 */
class VectorsTest {

    private val vectors = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/vectors.json")?.readBytes()?.decodeToString()
            ?: fail("vectors.json не найден в тест-ресурсах (../schema/test-vectors)"),
    ).jsonObject["vectors"]!!.jsonObject

    private fun vector(name: String) = vectors[name]?.jsonObject ?: fail("Вектор '$name' отсутствует")
    private fun kotlinx.serialization.json.JsonObject.hex(field: String) =
        this[field]!!.jsonPrimitive.content.hexToBytes()

    @Test
    fun `secretbox - конверт с фиксированным nonce совпадает байт-в-байт`() {
        val v = vector("secretbox")
        val out = EnvelopeCipher.sealWithNonce(v.hex("key"), v.hex("plaintext_hex"), v.hex("nonce"))
        assertEquals(v["kodium_output_hex"]!!.jsonPrimitive.content, out.toHex())

        // Обратный ход боевым API
        val opened = EnvelopeCipher.open(v.hex("key"), out).getOrThrow()
        assertEquals(v["plaintext_hex"]!!.jsonPrimitive.content, opened.toHex())
    }

    @Test
    fun `box_wrap - wrapped_key с фиксированным nonce совпадает байт-в-байт`() {
        val v = vector("box_wrap")

        // Публичный ключ выводится из секрета так же, как в эталоне
        val (ephPub, _) = nacl.Box.keyPairFromSecretKey(v.hex("eph_secret"))
        assertEquals(v["eph_public"]!!.jsonPrimitive.content, ephPub.toHex())

        val out = WrappedKeyService.wrapWithNonce(
            ephemeralSecret = v.hex("eph_secret"),
            recipientPub = v.hex("recipient_public"),
            messageKey = v.hex("message_key"),
            nonce = v.hex("nonce"),
        )
        assertEquals(v["kodium_output_hex"]!!.jsonPrimitive.content, out.toHex())

        // Разворачивание боевым API со стороны получателя
        val recipientKey = KodiumPrivateKey.fromRaw(v.hex("recipient_secret"))
        val unwrapped = WrappedKeyService.unwrap(recipientKey, v.hex("eph_public"), out).getOrThrow()
        assertEquals(v["message_key"]!!.jsonPrimitive.content, unwrapped.toHex())
    }

    @Test
    fun `ed25519 - подпись из seed устройства совпадает байт-в-байт`() {
        val v = vector("ed25519")
        val deviceKey = KodiumPrivateKey.fromRaw(v.hex("seed"))
        assertEquals(v["public_key"]!!.jsonPrimitive.content, deviceKey.getPublicKey().signingKey.toHex())

        val message = v.hex("message_hex")
        val signature = MessageSigner.sign(deviceKey, message).getOrThrow()
        assertEquals(v["signature_hex"]!!.jsonPrimitive.content, signature.toHex())

        assertTrue(MessageSigner.verify(v.hex("public_key"), message, signature))
        // Повреждённое сообщение — подпись невалидна
        val tampered = message.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertTrue(!MessageSigner.verify(v.hex("public_key"), tampered, signature))
    }

    @Test
    fun `hkdf_sha256 - деривация escrow-ключа совпадает байт-в-байт`() {
        val v = vector("hkdf_sha256")
        val out = HKDF.deriveSecrets(salt = v.hex("salt"), ikm = v.hex("ikm"), info = v.hex("info_hex"), length = 32)
        assertEquals(v["output_hex"]!!.jsonPrimitive.content, out.toHex())
    }

    @Test
    fun `canonical_bytes - preimage подписи совпадает байт-в-байт`() {
        val v = vector("canonical_bytes")
        val inputs = v["inputs"]!!.jsonObject

        val meta = EnvelopeMeta(
            messageId = inputs["message_id"]!!.jsonPrimitive.long.toULong(),
            chatId = inputs["chat_id"]!!.jsonPrimitive.content,
            senderId = inputs["sender_id"]!!.jsonPrimitive.content,
            senderDevice = inputs["sender_device"]!!.jsonPrimitive.content,
            kind = inputs["kind"]!!.jsonPrimitive.int,
            createdAtUnixMs = inputs["created_at_unix_ms"]!!.jsonPrimitive.long,
            replyTo = inputs["reply_to"]!!.jsonPrimitive.long.toULong(),
        )
        // Блобы — как в генераторе: payload из secretbox-вектора, escrow — 0xa1/0xa2, eph — из box_wrap
        val encryptedPayload = vector("secretbox").hex("kodium_output_hex")
        val escrowBytes = ByteArray(1088) { 0xa1.toByte() } + ByteArray(48) { 0xa2.toByte() }
        val senderEphemeralPub = vector("box_wrap").hex("eph_public")

        val cb = CanonicalBytes.build(
            meta = meta,
            encryptedPayload = encryptedPayload,
            escrowBytes = escrowBytes,
            senderEphemeralPub = senderEphemeralPub,
            ratchetEnvelope = CanonicalBytes.EMPTY,
            formatVersion = inputs["format_version"]!!.jsonPrimitive.int,
        )
        assertEquals(v["canonical_bytes_hex"]!!.jsonPrimitive.content, cb.toHex())
        assertEquals(v["sha256_hex"]!!.jsonPrimitive.content, sha256(cb).toHex())
    }

    @Test
    fun `mlkem768 - keygen из seed детерминирован, escrow round-trip`() {
        val v = vector("mlkem768_escrow")
        val seed = v.hex("keygen_seed") // 64 байта, layout noble/FIPS 203: d(32) ‖ z(32)

        // Провайдер escrow — Mlkem768 (BouncyCastle). Kodium ML-KEM не интероперабелен
        // с FIPS 203 и этот вектор НЕ проходит — см. Mlkem768.kt и поправку к ADR-0005.
        val (publicKey, secretKey) = Mlkem768.keyPairFromSeed(seed)

        assertEquals(v["public_key_len"]!!.jsonPrimitive.int, publicKey.size)
        assertEquals(v["secret_key_len"]!!.jsonPrimitive.int, secretKey.size)
        assertEquals(
            v["public_key_sha256"]!!.jsonPrimitive.content,
            sha256(publicKey).toHex(),
            "keygen(seed) разошёлся с noble — реализация ML-KEM некорректна",
        )

        // Инвариант (ct рандомизирован по FIPS 203): decapsulate(ct, sk) == shared
        val (shared, ct) = Mlkem768.encapsulate(publicKey)
        assertEquals(v["ciphertext_len"]!!.jsonPrimitive.int, ct.size)
        assertEquals(v["shared_len"]!!.jsonPrimitive.int, shared.size)
        val shared2 = Mlkem768.decapsulate(ct, secretKey) ?: fail("decapsulate вернул null")
        assertEquals(shared.toHex(), shared2.toHex())
    }

    @Test
    fun `все восемь векторов присутствуют`() {
        val expected = setOf(
            "secretbox", "box_wrap", "ed25519", "hkdf_sha256", "media_chunk_keys",
            "canonical_bytes", "message_body", "mlkem768_escrow",
        )
        assertEquals(expected, vectors.keys, "Состав vectors.json изменился — обнови KAT")
    }
}
