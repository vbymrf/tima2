package io.tima.core.encryption

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** План КК, КК8: ключ копии из своего источника — запись книги под ключом служебной группы. */
class CopyAnchorTest {

    private val account = AccountIdentitiesOverKodium.fresh()
    private val copy = KeyCopy.fromWords(account.words, 2)!!
    private val anchor = CopyAnchor(account.identityPub, 2, copy.encryptionPublic, KeyCopy.sign(account.words, 2, copy.encryptionPublic)!!)
    private val groupKey = ByteArray(32) { 3 }

    @Test
    fun запись_книги_открывается_ключом_служебной_группы() {
        val got = CopyAnchorCodec.open(groupKey, CopyAnchorCodec.seal(groupKey, anchor)!!)!!
        assertEquals(2, got.epoch)
        assertContentEquals(copy.encryptionPublic, got.pub)
        assertNull(CopyAnchorCodec.open(ByteArray(32) { 4 }, CopyAnchorCodec.seal(groupKey, anchor)!!), "чужой ключ группы открыл")
    }

    @Test
    fun подпись_чужим_ключом_личности_не_принимается() {
        val other = AccountIdentitiesOverKodium.fresh()
        val forged = CopyAnchor(other.identityPub, 2, copy.encryptionPublic, anchor.sig)
        assertNull(CopyAnchorCodec.open(groupKey, CopyAnchorCodec.seal(groupKey, forged)!!))
        assertNull(CopyAnchor.decode(forged.encode()))
        assertContentEquals(anchor.pub, CopyAnchor.decode(anchor.encode())!!.pub)
    }
}
