package io.tima.feature.auth

import io.tima.domain.account.ConfirmDeviceLink
import io.tima.domain.account.DeviceLinkConfirm
import io.tima.domain.account.LinkCode
import io.tima.domain.account.LinkConfirmStep
import io.tima.domain.account.LinkSigner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Подтверждение привязки на телефоне.
 *
 * Проверяется главное правило этого экрана: **скан — не решение**. Между чтением кода и
 * доверием стоит вопрос человеку, и до ответа в сеть не уходит ничего.
 */
class LinkStoreTest {

    private val network = FakeConfirmation()

    private fun store(scope: kotlinx.coroutines.CoroutineScope, code: String = "tima://link/v1?…") =
        LinkStore(ConfirmDeviceLink(network, AlwaysSigner), scope, code)

    /**
     * Скан сам ничего не подтверждает.
     *
     * Так было в v1, и это была настоящая поломка: код можно прислать в переписке или
     * наклеить на стену, а устройство добавлялось от одного взгляда камеры.
     */
    @Test
    fun скан_ничего_не_подтверждает() = runTest {
        val store = store(backgroundScope)

        val state = store.state.value
        assertIs<LinkState.Ask>(state)
        assertEquals("Компьютер", state.name, "человеку показывают имя устройства")
        assertEquals(0, network.confirmations, "до нажатия в сеть не уходит ничего")
    }

    @Test
    fun доверить_подтверждает_и_говорит_об_успехе() = runTest {
        val store = store(backgroundScope)

        store.trust()

        val state = store.state.first { it is LinkState.Done }
        assertIs<LinkState.Done>(state)
        assertEquals("d-2", state.deviceId)
        assertEquals(1, network.confirmations)
    }

    /** Второе нажатие не посылает второе подтверждение. */
    @Test
    fun второе_нажатие_не_повторяет_вызов() = runTest {
        network.onConfirmation = { LinkConfirmStep.Offline(retryAfterMs = 1_000) }
        val store = store(backgroundScope)

        store.trust()
        store.trust()

        store.state.first { it is LinkState.Ask && !it.expect }
        assertEquals(1, network.confirmations)
    }

    /**
     * «Подтвердить может только телефон» — своими словами, а не «не получилось».
     *
     * Человек в этот момент стоит с ПК, где показан код, и телефоном в руках. Если ему
     * сказать «ошибка», он попробует ещё раз то же самое.
     */
    @Test
    fun отказ_не_телефону_называется_словами() = runTest {
        network.onConfirmation = { LinkConfirmStep.NotAPhone }
        val store = store(backgroundScope)

        store.trust()

        val state = store.state.first { it is LinkState.Ask && !it.expect }
        assertIs<LinkState.Ask>(state)
        assertTrue(state.trouble.orEmpty().contains("только телефон"), "беда: ${state.trouble}")
    }

    /** Просроченный код — своё сообщение: показать новый надо на том устройстве. */
    @Test
    fun просроченный_код_отправляет_за_новым() = runTest {
        network.onConfirmation = { LinkConfirmStep.SessionGone }
        val store = store(backgroundScope)

        store.trust()

        val state = store.state.first { it is LinkState.Ask && !it.expect }
        assertIs<LinkState.Ask>(state)
        assertTrue(state.trouble.orEmpty().contains("новый"), "беда: ${state.trouble}")
    }

    /** Чужой код виден сразу, до всякой сети. */
    @Test
    fun чужой_код_виден_сразу() = runTest {
        network.onParsing = { null }

        val store = store(backgroundScope, code = "https://example.com")

        assertEquals(LinkState.NotOurCode, store.state.value)
        assertEquals(0, network.confirmations)
    }

    /** Имени в коде не было — так и говорим. Подставленное имя человек примет за настоящее. */
    @Test
    fun безымянное_устройство_не_получает_придуманного_имени() = runTest {
        network.onParsing = { LinkCode("s-1", "sec", ByteArray(32), ByteArray(32), deviceName = null) }

        val state = store(backgroundScope).state.value

        assertIs<LinkState.Ask>(state)
        assertEquals(null, state.name)
    }

    private class FakeConfirmation : DeviceLinkConfirm {
        var confirmations = 0
        var onParsing: (String) -> LinkCode? = {
            LinkCode("s-1", "sec", ByteArray(32), ByteArray(32), deviceName = "Компьютер")
        }
        var onConfirmation: suspend () -> LinkConfirmStep = { LinkConfirmStep.Confirmed("d-2") }

        override fun parse(code: String): LinkCode? = onParsing(code)

        override suspend fun confirm(
            sessionId: String,
            secret: String,
            signature: ByteArray,
        ): LinkConfirmStep {
            confirmations++
            return onConfirmation()
        }
    }

    private object AlwaysSigner : LinkSigner {
        override fun sign(
            sessionId: String,
            secret: String,
            encryptionPub: ByteArray,
            signingPub: ByteArray,
        ): ByteArray = ByteArray(64) { 1 }
    }
}
