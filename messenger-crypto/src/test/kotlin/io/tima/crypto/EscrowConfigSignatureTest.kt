package io.tima.crypto

import io.kodium.Kodium
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Подпись конфига анклава (Р2). Раскладка байт здесь обязана совпадать с
 * server/internal/escrow/signing.go (KeyMetaSigningBytes/PubkeySigningBytes)
 * байт-в-байт — расхождение ловится тем, что подпись сервера не пройдёт
 * проверку у клиента. Здесь проверяется то, что доступно без запуска Go:
 * подпись реально покрывает поля (тамперинг ловится), а не какую-то
 * фиксированную заглушку.
 */
class EscrowConfigSignatureTest {

    private val enclaveKey = Kodium.generateKeyPair()
    private val enclavePub = enclaveKey.getPublicKey().signingKey

    private fun meta(publicKey: ByteArray = ByteArray(1184) { it.toByte() }) = EscrowKeyMeta(
        id = 42L,
        region = "ru",
        epoch = "2026-07",
        chatId = "bbbbbbbb-0000-0000-0000-0000000000e1",
        publicKey = publicKey,
        validFromUnixMs = 1_753_920_000_000L,
        validToUnixMs = 1_756_598_400_000L,
        destroyAtUnixMs = 1_772_150_400_000L,
    )

    private fun sign(m: EscrowKeyMeta): ByteArray =
        Kodium.signDetached(enclaveKey, EscrowConfigSignature.keyMetaSigningBytes(m)).getOrThrow()

    @Test
    fun `подпись KeyMeta проходит проверку зашитым ключом анклава`() {
        val m = meta()
        val sig = sign(m)
        assertTrue(EscrowConfigSignature.verify(enclavePub, EscrowConfigSignature.keyMetaSigningBytes(m), sig))
    }

    @Test
    fun `подмена public_key ломает проверку - ровно то, что защита должна ловить`() {
        val original = meta()
        val sig = sign(original)

        val swapped = original.publicKey.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tampered = original.copy(publicKey = swapped)

        assertFalse(EscrowConfigSignature.verify(enclavePub, EscrowConfigSignature.keyMetaSigningBytes(tampered), sig))
    }

    @Test
    fun `подмена destroy_at ломает проверку`() {
        val original = meta()
        val sig = sign(original)
        val tampered = original.copy(destroyAtUnixMs = original.destroyAtUnixMs + 1)
        assertFalse(EscrowConfigSignature.verify(enclavePub, EscrowConfigSignature.keyMetaSigningBytes(tampered), sig))
    }

    @Test
    fun `подпись легаси pubkey проходит проверку и ловит подмену версии`() {
        val pub = ByteArray(1184) { (it * 3).toByte() }
        val msg = EscrowConfigSignature.pubkeySigningBytes(version = 1, publicKey = pub)
        val sig = Kodium.signDetached(enclaveKey, msg).getOrThrow()
        assertTrue(EscrowConfigSignature.verify(enclavePub, msg, sig))

        val tamperedMsg = EscrowConfigSignature.pubkeySigningBytes(version = 2, publicKey = pub)
        assertFalse(EscrowConfigSignature.verify(enclavePub, tamperedMsg, sig))
    }

    @Test
    fun `чужой ключ анклава подпись не проходит`() {
        val impostor = Kodium.generateKeyPair()
        val m = meta()
        val sig = sign(m)
        assertFalse(
            EscrowConfigSignature.verify(
                impostor.getPublicKey().signingKey,
                EscrowConfigSignature.keyMetaSigningBytes(m),
                sig,
            ),
        )
    }
}
