package io.tima.feature.auth

import io.tima.domain.account.AccountDevice
import io.tima.domain.account.DeviceBook
import io.tima.domain.account.DeviceTrustActions
import io.tima.domain.account.DevicesStep
import io.tima.domain.account.MyDevices
import io.tima.domain.account.Rereg
import io.tima.domain.account.RevokeStep
import io.tima.domain.account.TrustStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Панель перерегистрации (ДУ9, Р34): кому что показать и что можно нажать. Тексты — §2б;
 * здесь проверяется выбор, а не слова.
 */
class ReregViewTest {

    private class NoBook : DeviceBook {
        override suspend fun mine(): DevicesStep = DevicesStep.Devices(emptyList<AccountDevice>())
        override suspend fun revoke(deviceId: String): RevokeStep = RevokeStep.Gone
    }

    private class Trust(val r: Rereg) : DeviceTrustActions {
        override fun holdsKey() = true
        override suspend fun confirmWithPhrase(words: List<String>) = TrustStep.Done
        override suspend fun certify(deviceId: String) = TrustStep.Done
        override suspend fun rereg(): Rereg = r
    }

    private val hour = 3_600_000L
    private val now = 100 * hour

    private suspend fun view(r: Rereg, scope: kotlinx.coroutines.CoroutineScope): ReregView? {
        val store = DevicesStore(MyDevices(NoBook()), scope, trust = Trust(r), dateText = { "д$it" })
        store.state.first { it.rereg != null }
        return store.reregView(now)
    }

    @Test
    fun без_процесса_можно_запустить() = runTest {
        val v = view(Rereg.NONE, backgroundScope)!!
        assertTrue(v.canStart)
        assertNull(v.text)
    }

    @Test
    fun прежняя_до_спора_может_заявить_о_краже() = runTest {
        val v = view(Rereg(active = true, isNew = false, windowFrom = now + hour, windowTo = now + 2 * hour), backgroundScope)!!
        assertTrue(v.canClaim)
        assertFalse(v.canConfirm)
        assertFalse(v.canStart)
    }

    @Test
    fun новая_подтверждает_только_в_окне_и_двумя_фразами() = runTest {
        val before = view(Rereg(active = true, isNew = true, windowFrom = now + hour, windowTo = now + 2 * hour), backgroundScope)!!
        assertFalse(before.canConfirm, "до окна не подтверждают")
        assertTrue(before.text!!.contains("д${now + hour}"), "в тексте — даты окна")
        val open = view(Rereg(active = true, isNew = true, windowFrom = now - hour, windowTo = now + hour), backgroundScope)!!
        assertTrue(open.canConfirm)
        assertTrue(open.twoPhrases)
        val done = view(Rereg(active = true, isNew = true, windowFrom = now - hour, windowTo = now + hour, confirmed = true), backgroundScope)!!
        assertFalse(done.canConfirm, "подтвердившая второй раз не подтверждает")
    }

    @Test
    fun прежняя_подтверждает_только_после_своей_заявки() = runTest {
        val noClaim = view(Rereg(active = true, isNew = false, windowFrom = now - hour, windowTo = now + hour), backgroundScope)!!
        assertFalse(noClaim.canConfirm)
        val claimed = view(Rereg(active = true, isNew = false, windowFrom = now - hour, windowTo = now + hour, disputed = true), backgroundScope)!!
        assertTrue(claimed.canConfirm)
        assertFalse(claimed.twoPhrases)
        assertFalse(claimed.canClaim, "заявка подаётся один раз")
        assertEquals(true, claimed.text?.isNotBlank())
    }
}
