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
class ПривязкаStoreTest {

    private val сеть = ПоддельноеПодтверждение()

    private fun store(scope: kotlinx.coroutines.CoroutineScope, код: String = "tima://link/v1?…") =
        ПривязкаStore(ConfirmDeviceLink(сеть, ПодписантВсегда), scope, код)

    /**
     * Скан сам ничего не подтверждает.
     *
     * Так было в v1, и это была настоящая поломка: код можно прислать в переписке или
     * наклеить на стену, а устройство добавлялось от одного взгляда камеры.
     */
    @Test
    fun скан_ничего_не_подтверждает() = runTest {
        val store = store(backgroundScope)

        val состояние = store.state.value
        assertIs<ПривязкаState.Спрашиваем>(состояние)
        assertEquals("Компьютер", состояние.имя, "человеку показывают имя устройства")
        assertEquals(0, сеть.подтверждений, "до нажатия в сеть не уходит ничего")
    }

    @Test
    fun доверить_подтверждает_и_говорит_об_успехе() = runTest {
        val store = store(backgroundScope)

        store.доверить()

        val состояние = store.state.first { it is ПривязкаState.Готово }
        assertIs<ПривязкаState.Готово>(состояние)
        assertEquals("d-2", состояние.deviceId)
        assertEquals(1, сеть.подтверждений)
    }

    /** Второе нажатие не посылает второе подтверждение. */
    @Test
    fun второе_нажатие_не_повторяет_вызов() = runTest {
        сеть.наПодтверждение = { LinkConfirmStep.Offline(retryAfterMs = 1_000) }
        val store = store(backgroundScope)

        store.доверить()
        store.доверить()

        store.state.first { it is ПривязкаState.Спрашиваем && !it.ждём }
        assertEquals(1, сеть.подтверждений)
    }

    /**
     * «Подтвердить может только телефон» — своими словами, а не «не получилось».
     *
     * Человек в этот момент стоит с ПК, где показан код, и телефоном в руках. Если ему
     * сказать «ошибка», он попробует ещё раз то же самое.
     */
    @Test
    fun отказ_не_телефону_называется_словами() = runTest {
        сеть.наПодтверждение = { LinkConfirmStep.NotAPhone }
        val store = store(backgroundScope)

        store.доверить()

        val состояние = store.state.first { it is ПривязкаState.Спрашиваем && !it.ждём }
        assertIs<ПривязкаState.Спрашиваем>(состояние)
        assertTrue(состояние.беда.orEmpty().contains("только телефон"), "беда: ${состояние.беда}")
    }

    /** Просроченный код — своё сообщение: показать новый надо на том устройстве. */
    @Test
    fun просроченный_код_отправляет_за_новым() = runTest {
        сеть.наПодтверждение = { LinkConfirmStep.SessionGone }
        val store = store(backgroundScope)

        store.доверить()

        val состояние = store.state.first { it is ПривязкаState.Спрашиваем && !it.ждём }
        assertIs<ПривязкаState.Спрашиваем>(состояние)
        assertTrue(состояние.беда.orEmpty().contains("новый"), "беда: ${состояние.беда}")
    }

    /** Чужой код виден сразу, до всякой сети. */
    @Test
    fun чужой_код_виден_сразу() = runTest {
        сеть.наРазбор = { null }

        val store = store(backgroundScope, код = "https://example.com")

        assertEquals(ПривязкаState.НеНашКод, store.state.value)
        assertEquals(0, сеть.подтверждений)
    }

    /** Имени в коде не было — так и говорим. Подставленное имя человек примет за настоящее. */
    @Test
    fun безымянное_устройство_не_получает_придуманного_имени() = runTest {
        сеть.наРазбор = { LinkCode("s-1", "sec", ByteArray(32), ByteArray(32), deviceName = null) }

        val состояние = store(backgroundScope).state.value

        assertIs<ПривязкаState.Спрашиваем>(состояние)
        assertEquals(null, состояние.имя)
    }

    private class ПоддельноеПодтверждение : DeviceLinkConfirm {
        var подтверждений = 0
        var наРазбор: (String) -> LinkCode? = {
            LinkCode("s-1", "sec", ByteArray(32), ByteArray(32), deviceName = "Компьютер")
        }
        var наПодтверждение: suspend () -> LinkConfirmStep = { LinkConfirmStep.Confirmed("d-2") }

        override fun parse(code: String): LinkCode? = наРазбор(code)

        override suspend fun confirm(
            sessionId: String,
            secret: String,
            signature: ByteArray,
        ): LinkConfirmStep {
            подтверждений++
            return наПодтверждение()
        }
    }

    private object ПодписантВсегда : LinkSigner {
        override fun sign(
            sessionId: String,
            secret: String,
            encryptionPub: ByteArray,
            signingPub: ByteArray,
        ): ByteArray = ByteArray(64) { 1 }
    }
}
