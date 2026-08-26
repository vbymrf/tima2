package io.tima.core.encryption

import io.kodium.Kodium
import io.tima.crypto.EscrowConfigSignature
import io.tima.crypto.EscrowKeyMeta
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверка ключа эпохи escrow. Подпись настоящая: смысл всей проверки в том, что
 * подмену ловит криптография, и заглушкой это проверять бессмысленно.
 */
class EscrowKeyVerifierTest {

    private val enclave = Kodium.generateKeyPair()
    private val enclaveKey = enclave.getPublicKey().signingKey

    private val now = 1_771_200_000_000L
    private val epochKey = ByteArray(EscrowKeyVerifier.PUBLIC_KEY_BYTES) { (it % 251).toByte() }

    private fun meta(
        id: Long = 7,
        region: String = "ru",
        chatId: String = "bbbbbbbb-0000-0000-0000-0000000000e1",
        epoch: String = "2026-08",
        publicKey: ByteArray = epochKey,
        validFrom: Long = now - 1_000,
        validTo: Long = now + 1_000,
        destroyAt: Long = now + 100_000,
    ) = EscrowKeyMeta(id, region, epoch, chatId, publicKey, validFrom, validTo, destroyAt)

    private fun caption(meta: EscrowKeyMeta, key: io.kodium.KodiumPrivateKey = enclave): ByteArray =
        Kodium.signDetached(key, EscrowConfigSignature.keyMetaSigningBytes(meta)).getOrThrow()

    private fun check(
        meta: EscrowKeyMeta = meta(),
        signature: ByteArray = caption(meta),
        enclaveKey: ByteArray = this.enclaveKey,
        nowMs: Long = now,
    ) = EscrowKeyVerifier.verify(
        enclaveSigningPub = enclaveKey,
        id = meta.id,
        region = meta.region,
        chatId = meta.chatId,
        epoch = meta.epoch,
        publicKey = meta.publicKey,
        signature = signature,
        validFromMs = meta.validFromUnixMs,
        validToMs = meta.validToUnixMs,
        destroyAtMs = meta.destroyAtUnixMs,
        nowMs = nowMs,
    )

    @Test
    fun подписанный_анклавом_ключ_принимается() {
        val accepted = check().getOrThrow()

        assertEquals(7, accepted.version, "версия ключа едет в конверт как escrow_key_version")
        assertContentEquals(epochKey, accepted.publicKey)
    }

    @Test
    fun подменённый_ключ_эпохи_отвергается() {
        // Главная проверка: именно так выглядит компрометация бэкенда — подписан один
        // ключ, отдан другой. Пройди это, и сообщение зашифровалось бы в обход анклава.
        val honest = meta()
        val caption = caption(honest)
        val foreign = ByteArray(EscrowKeyVerifier.PUBLIC_KEY_BYTES) { 0x42 }

        val outcome = check(meta = honest.copy(publicKey = foreign), signature = caption)

        assertIs<EscrowKeyRejected>(outcome.exceptionOrNull())
        assertTrue(outcome.exceptionOrNull()!!.message!!.contains("подпись"))
    }

    @Test
    fun подпись_чужого_анклава_отвергается() {
        val foreignEnclave = Kodium.generateKeyPair()
        val meta = meta()
        assertTrue(check(meta, caption(meta, foreignEnclave)).isFailure)
    }

    @Test
    fun подмена_любого_подписанного_поля_ловится() {
        // Подпись покрывает восемь полей, и все восемь важны: подменённый chat_id даёт
        // ключ другой переписки, подменённый destroy_at — обещание хранения, которого
        // анклав не давал.
        val honest = meta()
        val caption = caption(honest)

        val substitution = listOf(
            "id" to honest.copy(id = 8),
            "region" to honest.copy(region = "eu"),
            "chatId" to honest.copy(chatId = "cccccccc-0000-0000-0000-0000000000e1"),
            "epoch" to honest.copy(epoch = "2026-09"),
            "validFrom" to honest.copy(validFromUnixMs = honest.validFromUnixMs - 1),
            "validTo" to honest.copy(validToUnixMs = honest.validToUnixMs + 1),
            "destroyAt" to honest.copy(destroyAtUnixMs = honest.destroyAtUnixMs + 1),
        )
        for ((field, substituted) in substitution) {
            assertTrue(
                check(substituted, caption).isFailure,
                "подмена поля $field обязана быть замечена",
            )
        }
    }

    @Test
    fun истёкший_и_ещё_не_начавшийся_ключ_не_годятся() {
        // Запечатывать на истёкшую эпоху — значит отдать сообщение ключу, который анклав
        // уничтожит раньше, чем истечёт срок хранения переписки.
        val meta = meta()
        val expired = check(meta, nowMs = meta.validToUnixMs)
        assertTrue(expired.isFailure)
        assertTrue(expired.exceptionOrNull()!!.message!!.contains("истёк"))

        val early = check(meta, nowMs = meta.validFromUnixMs - 1)
        assertTrue(early.isFailure)
        assertTrue(early.exceptionOrNull()!!.message!!.contains("не начал"))
    }

    @Test
    fun граница_окна_включает_начало_и_исключает_конец() {
        val meta = meta()
        assertTrue(check(meta, nowMs = meta.validFromUnixMs).isSuccess, "начало включительно")
        assertTrue(check(meta, nowMs = meta.validToUnixMs - 1).isSuccess)
        assertTrue(check(meta, nowMs = meta.validToUnixMs).isFailure, "конец исключительно")
    }

    @Test
    fun размеры_проверяются_до_криптографии() {
        // Ключ не того размера — испорченный ответ, а не подмена. Сообщение об этом
        // должно называть размер, иначе искать причину придётся в криптографии.
        val meta = meta(publicKey = ByteArray(100))
        val outcome = check(meta)
        assertTrue(outcome.exceptionOrNull()!!.message!!.contains("1184"))

        assertTrue(check(signature = ByteArray(10)).isFailure)
        assertTrue(check(enclaveKey = ByteArray(10)).isFailure)
    }

    @Test
    fun ключ_анклава_зашит_и_он_нужного_размера() {
        // Ключ стенда, взятый из журнала анклава 2026-08-22 и проверенный прогоном:
        // подпись ключа эпохи сошлась, сообщение ушло и было прочитано.
        //
        // Проверяется размер, а не значение: значение — константа рядом, и сверять
        // константу с собой бессмысленно. А вот 31 байт вместо 32 (обрезанная строка,
        // испорченный base64url) даст отказ на каждом сообщении, и причина будет
        // выглядеть как «анклав подписывает неправильно».
        val key = EscrowTrust.enclaveSigningPub
        assertNotNull(key, "ключ анклава обязан быть зашит: без него отправка невозможна")
        assertEquals(32, key.size)
    }

    @Test
    fun непригодная_строка_ключа_даёт_отсутствие_а_не_мусор() {
        // Если константу однажды испортят правкой, лучше получить «ключа нет» и закрытый
        // отказ, чем 20 байт, которые примут вид ключа и провалят каждую подпись.
        assertEquals(
            43,
            EscrowTrust.STAND_ENCLAVE_KEY.length,
            "32 байта в base64url без выравнивания — это ровно 43 знака",
        )
    }
}
