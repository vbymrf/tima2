package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Передача владения глазами домена (ПЛАН-(СЦ) §6, СЦ11): какой акт подписывается и что уходит серверу. */
class TransferOwnershipTest {

    private class FakeOwnership : EntityOwnership {
        val offers = mutableListOf<Triple<String, Long, ByteArray>>()
        val accepts = mutableListOf<ByteArray>()
        override suspend fun chain(kind: EntityKind, id: String): OwnershipView? = null
        override suspend fun offer(kind: EntityKind, id: String, toUserId: String, expiresUnix: Long, signature: ByteArray): OwnershipStep {
            offers += Triple(toUserId, expiresUnix, signature)
            return OwnershipStep.Done
        }
        override suspend fun accept(kind: EntityKind, id: String, signature: ByteArray): OwnershipStep {
            accepts += signature
            return OwnershipStep.Done
        }
        override suspend fun drop(kind: EntityKind, id: String) = OwnershipStep.Done
        override suspend fun offersToMe(): List<OwnershipOffer>? = emptyList()
    }

    private val acts = mutableListOf<String>()
    private fun case(net: FakeOwnership, phraseOk: Boolean = true) = TransferOwnership(
        ownership = net,
        sign = { words, bytes -> if (phraseOk && words.size == 12) bytes.copyOf(64) else null },
        act = { k, id, link, from, to, exp -> "$k|$id|$link|$from|$to|$exp".also { acts += it }.encodeToByteArray() },
        nowUnix = { 1_000L },
    )

    private val twelve = List(12) { "word" }

    @Test
    fun владелец_подписывает_акт_следующего_звена_на_срок_меньше_получаса() = runTest {
        val net = FakeOwnership()
        assertEquals(OwnershipStep.Done, case(net).offer(EntityKind.Group, "g-1", "me", "mod", nextLink = 3, words = twelve))
        val expires = 1_000L + TransferOwnership.TTL_SECONDS
        assertEquals("group|g-1|3|me|mod|$expires", acts.single())
        assertEquals("mod", net.offers.single().first)
        assertEquals(expires, net.offers.single().second)
        assertTrue(TransferOwnership.TTL_SECONDS < 30 * 60, "сервер принимает акт не дольше 30 минут")
    }

    @Test
    fun принимающий_подписывает_тот_же_акт() = runTest {
        val net = FakeOwnership()
        val offer = OwnershipOffer(EntityKind.Community, "c-1", 2, "owner", "admin", 5_000L, "Клуб")
        assertEquals(OwnershipStep.Done, case(net).accept(offer, twelve))
        assertEquals("community|c-1|2|owner|admin|5000", acts.single())
        assertEquals(1, net.accepts.size)
    }

    @Test
    fun фраза_не_складывается_ничего_не_уходит() = runTest {
        val net = FakeOwnership()
        assertEquals(OwnershipStep.BadPhrase(false), case(net, phraseOk = false).offer(EntityKind.Channel, "ch", "me", "m", 2, twelve))
        assertTrue(net.offers.isEmpty(), "без подписи серверу не идёт ничего")
    }
}
