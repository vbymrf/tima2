package io.tima.core.encryption

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ответ в ветке группы (Н24, ADR-0024): корень ветки — под подписью. Сервер собирает подписываемые
 * байты с `thread_root` из запроса; подпись «без ветки» он отвергал `403 bad_signature`.
 */
class GroupThreadSignatureTest {

    private val author = DeviceIdentity.generate()
    private val sealer = GroupMessageSealerOverKodium("bbbbbbbb-0000-0000-0000-000000000002", "cccccccc-0000-0000-0000-000000000003", author)
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }

    private fun opens(sealed: io.tima.domain.chat.SealedGroupBytes, gkVersion: Int, threadRoot: Long) = GroupMessages.open(
        groupId = "gggggggg-0000-0000-0000-000000000001",
        senderId = "bbbbbbbb-0000-0000-0000-000000000002",
        senderDevice = "cccccccc-0000-0000-0000-000000000003",
        kind = 1,
        createdAtUnixMs = 1_750_000_000_000,
        threadRoot = threadRoot,
        replyTo = 0,
        gkVersion = gkVersion,
        payload = sealed.payload,
        signature = sealed.signature,
        senderSigningPublic = author.signingPublic,
        groupKey = if (gkVersion == 0) ByteArray(0) else key,
        keyCommitment = sealed.keyCommitment,
    ).isSuccess

    @Test
    fun корень_ветки_входит_в_подпись_у_шифра_и_открытого() {
        for (version in listOf(0, 3)) {
            val sealed = sealer.sealPrepared(
                groupId = "gggggggg-0000-0000-0000-000000000001",
                gkVersion = version,
                key = if (version == 0) ByteArray(0) else key,
                body = TextBodyCodec.encodeText("ответ в ветке"),
                createdAtUnixMs = 1_750_000_000_000,
                threadRoot = 42,
            )!!
            assertTrue(opens(sealed, version, threadRoot = 42), "с тем же корнем подпись сходится (версия $version)")
            assertTrue(!opens(sealed, version, threadRoot = 0), "без корня — не сходится: корень под подписью (версия $version)")
        }
    }
}
