package io.tima.feature.auth

import io.tima.domain.account.AccountDevice
import io.tima.domain.account.DeviceBook
import io.tima.domain.account.DeviceTrustActions
import io.tima.domain.account.DevicesStep
import io.tima.domain.account.MyDevices
import io.tima.domain.account.PhoneChange
import io.tima.domain.account.Rereg
import io.tima.domain.account.RevokeStep
import io.tima.domain.account.TrustStep
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Панель смены номера (ДУ9; порядок — ответ заказчика 2026-10-06): кому что показать и что можно
 * нажать. Здесь проверяется выбор, а не слова.
 */
class PhoneChangeViewTest {

    private class NoBook : DeviceBook {
        override suspend fun mine(): DevicesStep = DevicesStep.Devices(emptyList<AccountDevice>())
        override suspend fun revoke(deviceId: String): RevokeStep = RevokeStep.Gone
    }

    private class Trust(val p: PhoneChange, val r: Rereg = Rereg.NONE) : DeviceTrustActions {
        override fun holdsKey() = true
        override suspend fun confirmWithPhrase(words: List<String>) = TrustStep.Done
        override suspend fun certify(deviceId: String) = TrustStep.Done
        override suspend fun rereg(): Rereg = r
        override suspend fun phoneChange(): PhoneChange = p
    }

    private val hour = 3_600_000L
    private val now = 100 * hour

    private suspend fun view(p: PhoneChange, scope: kotlinx.coroutines.CoroutineScope, r: Rereg = Rereg.NONE): PhoneChangeView? {
        val store = DevicesStore(MyDevices(NoBook()), scope, trust = Trust(p, r), dateText = { "д$it" })
        store.state.first { it.phoneChange != null && it.rereg != null }
        return store.phoneChangeView(now)
    }

    @Test
    fun без_заявки_можно_подать() = runTest {
        val v = view(PhoneChange.NONE, backgroundScope)!!
        assertTrue(v.canStart)
        assertNull(v.text)
    }

    @Test
    fun во_время_перерегистрации_подать_нельзя() = runTest {
        val v = view(PhoneChange.NONE, backgroundScope, Rereg(active = true, windowFrom = now + hour, windowTo = now + 2 * hour))!!
        assertFalse(v.canStart, "заявка отменяется до конца спора — и подать её нельзя")
    }

    @Test
    fun подавшая_подтверждает_только_в_окне() = runTest {
        val filed = PhoneChange(active = true, newPhone = "+7 ••• •• 02", newPhoneFull = "+79990000002", mine = true)
        val before = view(filed.copy(windowFrom = now + hour, windowTo = now + 2 * hour), backgroundScope)!!
        assertFalse(before.canConfirm, "до окна не подтверждают")
        assertTrue(before.text!!.contains("д${now + hour}"), "в тексте — даты окна")
        val open = view(filed.copy(windowFrom = now - hour, windowTo = now + hour), backgroundScope)!!
        assertTrue(open.canConfirm)
        assertFalse(open.canStart)
    }

    @Test
    fun другая_личность_видит_предупреждение_и_не_подтверждает() = runTest {
        val v = view(PhoneChange(active = true, newPhone = "+7 ••• •• 02", mine = false, windowFrom = now - hour, windowTo = now + hour), backgroundScope)!!
        assertFalse(v.canConfirm)
        assertTrue(v.text!!.contains("+7 ••• •• 02"), "предупреждение называет новый номер")
    }
}
