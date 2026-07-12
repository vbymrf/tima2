package io.tima.crypto

import com.github.luben.zstd.Zstd
import io.kodium.Kodium
import io.tima.crypto.proto.Entity
import io.tima.crypto.proto.EntityType
import io.tima.crypto.proto.MediaRef
import io.tima.crypto.proto.MessageBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Wire-сериализация: KAT замороженных protobuf-байт MessageBody (Wire — референс для Go),
 * zstd-инварианты (нормативна распаковка, не байты сжатия) и roundtrip полного Envelope.
 */
class SerializerTest {

    private val vectors = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/vectors.json")?.readBytes()?.decodeToString()
            ?: fail("vectors.json не найден в тест-ресурсах"),
    ).jsonObject["vectors"]!!.jsonObject

    companion object {
        // Канонический body KAT-вектора `message_body`. Все offset/length — UTF-16 code units;
        // 👋 — суррогатная пара (2 юнита), поэтому «жирный» начинается с 17, а не 16.
        val FROZEN_BODY = MessageBody(
            text = "Привет, TIMA! 👋 жирный #тест",
            entities = listOf(
                Entity(type = EntityType.ET_LINK, offset = 8, length = 4, url = "https://tima.app"),
                Entity(type = EntityType.ET_BOLD, offset = 17, length = 6),
                Entity(type = EntityType.ET_HASHTAG, offset = 24, length = 5, attribute = "тест"),
            ),
            media = listOf(
                MediaRef(
                    media_id = "aaaabbbb-cccc-dddd-eeee-ffff00001111",
                    media_key = ByteArray(32) { 0xcc.toByte() }.toByteString(),
                    mime = "image/webp",
                    size_bytes = 34567,
                    width = 640,
                    height = 480,
                    blurhash = "LKO2?U%2Tw=w",
                    chunk_count = 1,
                ),
            ),
        )
    }

    @Test
    fun `message_body - protobuf байты совпадают с замороженным вектором`() {
        val v = vectors["message_body"]?.jsonObject ?: fail("Вектор 'message_body' отсутствует")
        val encoded = MessageBody.ADAPTER.encode(FROZEN_BODY)
        assertEquals(
            v["protobuf_hex"]!!.jsonPrimitive.content,
            encoded.toHex(),
            "Wire-сериализация MessageBody разошлась с замороженным вектором",
        )
        // Нормативный обратный ход: parse(bytes) == body
        assertEquals(FROZEN_BODY, MessageBody.ADAPTER.decode(v["protobuf_hex"]!!.jsonPrimitive.content.hexToBytes()))
    }

    @Test
    fun `offset и length сущностей действительно в UTF-16 code units`() {
        val text = FROZEN_BODY.text
        assertEquals("TIMA", text.substring(8, 8 + 4))
        assertEquals("жирный", text.substring(17, 17 + 6)) // после суррогатной пары 👋
        assertEquals("#тест", text.substring(24, 24 + 5))
    }

    @Test
    fun `body - zstd roundtrip (сжатый вид по байтам не нормативен)`() {
        val payload = MessageSerializer.encodeBody(FROZEN_BODY)
        assertTrue(payload.size < MessageBody.ADAPTER.encode(FROZEN_BODY).size + 100, "zstd-оверхед подозрительно велик")
        assertEquals(FROZEN_BODY, MessageSerializer.decodeBody(payload).getOrThrow())
    }

    @Test
    fun `envelope - полный цикл body → конверт → protobuf → парсинг → чтение`() {
        val escrowKeyPair = Mlkem768.keyPair()
        val sealer = PersonalMessageSealer(EscrowModule(escrowKeyPair.first, escrowKeyVersion = 1))
        val senderKey = Kodium.generateKeyPair()
        val recipientKey = Kodium.generateKeyPair()

        val sealed = sealer.seal(
            meta = EnvelopeMeta(
                messageId = 42u,
                chatId = "11111111-1111-1111-1111-111111111111",
                senderId = "22222222-2222-2222-2222-222222222222",
                senderDevice = "33333333-3333-3333-3333-333333333333",
                kind = 1,
                createdAtUnixMs = 1_750_000_000_000,
            ),
            payloadPlaintext = MessageSerializer.encodeBody(FROZEN_BODY),
            senderDeviceKey = senderKey,
            recipientDevices = listOf(DeviceAddress("dev-1", recipientKey.getPublicKey().encryptionKey)),
        ).getOrThrow()

        // На провод и обратно
        val wire = MessageSerializer.encodeEnvelope(sealed)
        val parsed = MessageSerializer.decodeEnvelope(wire).getOrThrow()

        // Подпись выживает сериализацию (canonical_bytes восстановимы из распарсенных полей)
        val payload = PersonalMessageSealer.openWithWrappedKey(
            parsed, "dev-1", recipientKey, senderKey.getPublicKey().signingKey,
        ).getOrThrow()
        assertEquals(FROZEN_BODY, MessageSerializer.decodeBody(payload).getOrThrow())
    }

    @Test
    fun `zstd-бомба - decodeBody отклоняет body больше лимита`() {
        val bomb = Zstd.compress(ByteArray(MessageSerializer.MAX_BODY_BYTES.toInt() + 1), 3)
        assertTrue(bomb.size < 1024 * 1024, "бомба должна быть маленькой на проводе")
        assertTrue(MessageSerializer.decodeBody(bomb).isFailure)
    }

    @Test
    fun `мусор на входе - decodeBody и decodeEnvelope падают, не бросая наружу`() {
        assertTrue(MessageSerializer.decodeBody(ByteArray(64) { 0x5a }).isFailure)
        assertTrue(MessageSerializer.decodeEnvelope(ByteArray(64) { 0x5a }).isFailure)
    }
}
