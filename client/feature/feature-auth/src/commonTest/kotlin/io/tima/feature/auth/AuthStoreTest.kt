package io.tima.feature.auth

import io.tima.domain.account.AccountApi
import io.tima.domain.account.CodeRequestStep
import io.tima.domain.account.CodeSubmitStep
import io.tima.domain.account.DeviceCreateStep
import io.tima.domain.account.DeviceKeyFactory
import io.tima.domain.account.DeviceKeyMaterial
import io.tima.domain.account.DeviceSecretStore
import io.tima.domain.account.RegisterDevice
import io.tima.domain.account.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Вход: три правила поведения экрана и все исходы, которые бывают.
 */
class AuthStoreTest {

    private val api = ПоддельныйApi()
    private val хранилище = ПамятныеСекреты()

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = AuthStore(
        register = RegisterDevice(api, ключи, хранилище, platform = "проба"),
        scope = scope,
    )

    // ── набранное не теряется ────────────────────────────────────────────────

    /**
     * Номер остаётся в поле при любом отказе.
     *
     * То же правило, что в переписке: набранное написано человеком, и восстановить его нам
     * нечем. На этом экране цена выше — без входа приложения нет вовсе.
     */
    @Test
    fun номер_остаётся_в_поле_при_отказе() = runTest {
        api.наЗапросКода = { CodeRequestStep.Offline(retryAfterMs = 5_000) }
        val store = store(backgroundScope)
        store.номерИзменён("+79990000001")

        store.запроситьКод()

        val состояние = store.state.first { it is AuthState.Телефон && !it.ждём }
        assertIs<AuthState.Телефон>(состояние)
        assertEquals("+79990000001", состояние.номер, "номер отобрали при отказе сети")
        assertTrue(состояние.беда.orEmpty().contains("5 с"), "срок повтора обязан быть назван: ${состояние.беда}")
    }

    /**
     * Неверный код остаётся в поле.
     *
     * Человек чаще опечатался в одной цифре, чем набрал наугад, и стирать за него шесть
     * цифр — это заставить набирать их заново.
     */
    @Test
    fun неверный_код_остаётся_в_поле() = runTest {
        api.наПроверкуКода = { CodeSubmitStep.WrongCode }
        val store = дошлиДоКода(backgroundScope)
        store.кодИзменён("123456")

        store.подтвердить()

        val состояние = store.state.first { it is AuthState.Код && !it.ждём }
        assertIs<AuthState.Код>(состояние)
        assertEquals("123456", состояние.код)
        assertEquals("Код неверен или просрочен", состояние.беда)
    }

    /** «Изменить номер» возвращает к телефону с уже набранным номером. */
    @Test
    fun назад_помнит_номер() = runTest {
        val store = дошлиДоКода(backgroundScope)

        store.назад()

        val состояние = store.state.value
        assertIs<AuthState.Телефон>(состояние)
        assertEquals(ТЕЛЕФОН, состояние.номер)
    }

    // ── второе нажатие ──────────────────────────────────────────────────────

    /**
     * Второе нажатие не посылает вторую SMS.
     *
     * Не «удобство»: две SMS человек читает как «оно шлёт мне код дважды», и это дороже
     * любой задержки. Проверяется тем, что вызов держится незавершённым.
     */
    @Test
    fun второе_нажатие_не_посылает_вторую_смс() = runTest {
        val держим = CompletableDeferred<CodeRequestStep>()
        api.наЗапросКода = { держим.await() }
        val store = store(backgroundScope)
        store.номерИзменён(ТЕЛЕФОН)

        store.запроситьКод()
        store.state.first { it is AuthState.Телефон && it.ждём }
        store.запроситьКод()
        store.запроситьКод()

        держим.complete(CodeRequestStep.CodeRequested(requestId = "r-1"))
        store.state.first { it is AuthState.Код }

        assertEquals(1, api.запросовКода, "вызовов запроса кода обязано быть ровно одно")
    }

    // ── исходы ──────────────────────────────────────────────────────────────

    @Test
    fun успех_доводит_до_готово() = runTest {
        val store = дошлиДоКода(backgroundScope)
        store.кодИзменён("111111")

        store.подтвердить()

        val состояние = store.state.first { it is AuthState.Готово }
        assertIs<AuthState.Готово>(состояние)
        assertEquals("u-1", состояние.userId)
        assertEquals("d-1", состояние.deviceId)
    }

    /**
     * Код стенда виден на экране — и только когда его прислал сервер.
     *
     * Поле заполняется лишь при `TIMA_DEV_SMS`; решает это сервер, а не мы. Без этого
     * сквозной путь по стенду требовал бы настоящей SMS на каждый прогон.
     */
    @Test
    fun код_стенда_доезжает_до_экрана() = runTest {
        api.наЗапросКода = { CodeRequestStep.CodeRequested(requestId = "r-1", devCode = "424242") }
        val store = store(backgroundScope)
        store.номерИзменён(ТЕЛЕФОН)

        store.запроситьКод()

        val состояние = store.state.first { it is AuthState.Код }
        assertEquals("424242", (состояние as AuthState.Код).подсказкаСтенда)
    }

    @Test
    fun без_кода_стенда_подсказки_нет() = runTest {
        val store = дошлиДоКода(backgroundScope)
        assertNull((store.state.value as AuthState.Код).подсказкаСтенда)
    }

    /**
     * Просроченный токен возвращает к номеру, а не оставляет на коде.
     *
     * Иначе человек повторяет код, который уже не примут, и видит одну и ту же жалобу.
     */
    @Test
    fun просроченный_токен_возвращает_к_номеру() = runTest {
        api.наПроверкуКода = { CodeSubmitStep.Accepted("t-1") }
        api.наЗаведение = { DeviceCreateStep.TokenExpired }
        val store = дошлиДоКода(backgroundScope)
        store.кодИзменён("111111")

        store.подтвердить()

        val состояние = store.state.first { it is AuthState.Телефон }
        assertIs<AuthState.Телефон>(состояние)
        assertEquals(ТЕЛЕФОН, состояние.номер, "номер сохраняется: его уже набрали")
        assertEquals("Код просрочен — запросите новый", состояние.беда)
    }

    @Test
    fun уже_заведённое_устройство_это_не_отказ() = runTest {
        хранилище.секрет = ByteArray(32)
        val store = дошлиДоКода(backgroundScope)
        store.кодИзменён("111111")

        store.подтвердить()

        assertEquals(AuthState.УжеЗаведено, store.state.first { it is AuthState.УжеЗаведено })
    }

    @Test
    fun чужая_личность_объясняется_словами_про_фразу() = runTest {
        api.наПроверкуКода = { CodeSubmitStep.Accepted("t-1") }
        api.наЗаведение = { DeviceCreateStep.IdentityMismatch }
        val store = дошлиДоКода(backgroundScope)
        store.кодИзменён("111111")

        store.подтвердить()

        val состояние = store.state.first { it is AuthState.Код && !it.ждём }
        assertTrue(
            состояние.беда.orEmpty().contains("секретной фразе"),
            "человеку надо сказать, куда идти: ${состояние.беда}",
        )
    }

    @Test
    fun плохой_номер_отсекается_до_сети() = runTest {
        api.наЗапросКода = { CodeRequestStep.BadPhone("не E.164") }
        val store = store(backgroundScope)
        store.номерИзменён("восемь-девять-ноль")

        store.запроситьКод()

        val состояние = store.state.first { it is AuthState.Телефон && !it.ждём }
        assertTrue(состояние.беда.orEmpty().contains("не E.164"))
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private suspend fun дошлиДоКода(scope: kotlinx.coroutines.CoroutineScope): AuthStore {
        val store = store(scope)
        store.номерИзменён(ТЕЛЕФОН)
        store.запроситьКод()
        store.state.first { it is AuthState.Код }
        return store
    }

    private class ПоддельныйApi : AccountApi {
        var запросовКода = 0
        var наЗапросКода: suspend () -> CodeRequestStep = { CodeRequestStep.CodeRequested("r-1") }
        var наПроверкуКода: suspend () -> CodeSubmitStep = { CodeSubmitStep.Accepted("t-1") }
        var наЗаведение: suspend () -> DeviceCreateStep = {
            DeviceCreateStep.Created(userId = "u-1", deviceId = "d-1", accessToken = "a-1")
        }

        override suspend fun requestCode(phone: String): CodeRequestStep {
            запросовКода++
            return наЗапросКода()
        }

        override suspend fun submitCode(requestId: String, code: String): CodeSubmitStep = наПроверкуКода()

        override suspend fun createDevice(
            registrationToken: String,
            encryptionPub: ByteArray,
            signingPub: ByteArray,
            identityPub: ByteArray?,
            platform: String,
        ): DeviceCreateStep = наЗаведение()
    }

    private class ПамятныеСекреты : DeviceSecretStore {
        var секрет: ByteArray? = null
        var сессия: Session? = null
        override fun hasDevice(): Boolean = секрет != null
        override fun saveDeviceSecret(secret: ByteArray) { секрет = secret }
        override fun saveSession(session: Session) { сессия = session }
        override fun session(): Session? = сессия
    }

    private companion object {
        const val ТЕЛЕФОН = "+79990000001"

        val ключи = DeviceKeyFactory {
            DeviceKeyMaterial(
                encryptionPub = ByteArray(32) { 1 },
                signingPub = ByteArray(32) { 2 },
                secret = ByteArray(32) { 3 },
            )
        }
    }
}
