package io.tima.core.encryption

import io.kodium.Kodium
import io.tima.crypto.EscrowConfigSignature
import io.tima.crypto.EscrowKeyMeta
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Проверка ключа эпохи escrow. Подпись настоящая: смысл всей проверки в том, что
 * подмену ловит криптография, и заглушкой это проверять бессмысленно.
 */
class EscrowKeyVerifierTest {

    private val анклав = Kodium.generateKeyPair()
    private val ключАнклава = анклав.getPublicKey().signingKey

    private val сейчас = 1_771_200_000_000L
    private val ключЭпохи = ByteArray(EscrowKeyVerifier.PUBLIC_KEY_BYTES) { (it % 251).toByte() }

    private fun мета(
        id: Long = 7,
        region: String = "ru",
        chatId: String = "bbbbbbbb-0000-0000-0000-0000000000e1",
        epoch: String = "2026-08",
        publicKey: ByteArray = ключЭпохи,
        validFrom: Long = сейчас - 1_000,
        validTo: Long = сейчас + 1_000,
        destroyAt: Long = сейчас + 100_000,
    ) = EscrowKeyMeta(id, region, epoch, chatId, publicKey, validFrom, validTo, destroyAt)

    private fun подпись(meta: EscrowKeyMeta, ключ: io.kodium.KodiumPrivateKey = анклав): ByteArray =
        Kodium.signDetached(ключ, EscrowConfigSignature.keyMetaSigningBytes(meta)).getOrThrow()

    private fun проверить(
        meta: EscrowKeyMeta = мета(),
        signature: ByteArray = подпись(meta),
        ключАнклава: ByteArray = this.ключАнклава,
        nowMs: Long = сейчас,
    ) = EscrowKeyVerifier.verify(
        enclaveSigningPub = ключАнклава,
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
        val принят = проверить().getOrThrow()

        assertEquals(7, принят.version, "версия ключа едет в конверт как escrow_key_version")
        assertContentEquals(ключЭпохи, принят.publicKey)
    }

    @Test
    fun подменённый_ключ_эпохи_отвергается() {
        // Главная проверка: именно так выглядит компрометация бэкенда — подписан один
        // ключ, отдан другой. Пройди это, и сообщение зашифровалось бы в обход анклава.
        val честная = мета()
        val подпись = подпись(честная)
        val чужой = ByteArray(EscrowKeyVerifier.PUBLIC_KEY_BYTES) { 0x42 }

        val исход = проверить(meta = честная.copy(publicKey = чужой), signature = подпись)

        assertIs<EscrowKeyRejected>(исход.exceptionOrNull())
        assertTrue(исход.exceptionOrNull()!!.message!!.contains("подпись"))
    }

    @Test
    fun подпись_чужого_анклава_отвергается() {
        val чужойАнклав = Kodium.generateKeyPair()
        val meta = мета()
        assertTrue(проверить(meta, подпись(meta, чужойАнклав)).isFailure)
    }

    @Test
    fun подмена_любого_подписанного_поля_ловится() {
        // Подпись покрывает восемь полей, и все восемь важны: подменённый chat_id даёт
        // ключ другой переписки, подменённый destroy_at — обещание хранения, которого
        // анклав не давал.
        val честная = мета()
        val подпись = подпись(честная)

        val подмены = listOf(
            "id" to честная.copy(id = 8),
            "region" to честная.copy(region = "eu"),
            "chatId" to честная.copy(chatId = "cccccccc-0000-0000-0000-0000000000e1"),
            "epoch" to честная.copy(epoch = "2026-09"),
            "validFrom" to честная.copy(validFromUnixMs = честная.validFromUnixMs - 1),
            "validTo" to честная.copy(validToUnixMs = честная.validToUnixMs + 1),
            "destroyAt" to честная.copy(destroyAtUnixMs = честная.destroyAtUnixMs + 1),
        )
        for ((поле, подменённая) in подмены) {
            assertTrue(
                проверить(подменённая, подпись).isFailure,
                "подмена поля $поле обязана быть замечена",
            )
        }
    }

    @Test
    fun истёкший_и_ещё_не_начавшийся_ключ_не_годятся() {
        // Запечатывать на истёкшую эпоху — значит отдать сообщение ключу, который анклав
        // уничтожит раньше, чем истечёт срок хранения переписки.
        val meta = мета()
        val истёк = проверить(meta, nowMs = meta.validToUnixMs)
        assertTrue(истёк.isFailure)
        assertTrue(истёк.exceptionOrNull()!!.message!!.contains("истёк"))

        val рано = проверить(meta, nowMs = meta.validFromUnixMs - 1)
        assertTrue(рано.isFailure)
        assertTrue(рано.exceptionOrNull()!!.message!!.contains("не начал"))
    }

    @Test
    fun граница_окна_включает_начало_и_исключает_конец() {
        val meta = мета()
        assertTrue(проверить(meta, nowMs = meta.validFromUnixMs).isSuccess, "начало включительно")
        assertTrue(проверить(meta, nowMs = meta.validToUnixMs - 1).isSuccess)
        assertTrue(проверить(meta, nowMs = meta.validToUnixMs).isFailure, "конец исключительно")
    }

    @Test
    fun размеры_проверяются_до_криптографии() {
        // Ключ не того размера — испорченный ответ, а не подмена. Сообщение об этом
        // должно называть размер, иначе искать причину придётся в криптографии.
        val meta = мета(publicKey = ByteArray(100))
        val исход = проверить(meta)
        assertTrue(исход.exceptionOrNull()!!.message!!.contains("1184"))

        assertTrue(проверить(signature = ByteArray(10)).isFailure)
        assertTrue(проверить(ключАнклава = ByteArray(10)).isFailure)
    }

    @Test
    fun ключ_анклава_в_сборке_пока_не_зашит() {
        // Состояние, а не заглушка: пока ключа нет, ни один ключ эпохи не принимается, и
        // отправка невозможна. Порядок работ отсюда и следует — выкатка стенда, ключ из
        // журнала анклава, потом сборка, умеющая отправлять.
        assertTrue(EscrowTrust.enclaveSigningPub == null)
    }
}
