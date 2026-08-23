package io.tima.feature.auth

import io.tima.domain.account.AccountIdentities
import io.tima.domain.account.AccountApi
import io.tima.domain.account.CodeRequestStep
import io.tima.domain.account.CodeSubmitStep
import io.tima.domain.account.DeviceCreateStep
import io.tima.domain.account.DeviceKeyFactory
import io.tima.domain.account.DeviceKeyMaterial
import io.tima.domain.account.DeviceSecretStore
import io.tima.domain.account.NewAccountIdentity
import io.tima.domain.account.RegisterDevice
import io.tima.domain.account.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    private val личности = ПоддельныеЛичности()

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = AuthStore(
        register = RegisterDevice(api, ключи, хранилище, platform = "проба"),
        identities = личности,
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

    /**
     * Успех ведёт к **показу фразы**, а не сразу в приложение.
     *
     * Это единственный момент, когда человек может её записать: слова не хранятся ни у
     * нас, ни на сервере. Проскочить его нельзя — дальше только по кнопке «Записал».
     */
    @Test
    fun успех_показывает_фразу_и_только_потом_готово() = runTest {
        val store = дошлиДоКода(backgroundScope)
        store.кодИзменён("111111")

        store.подтвердить()

        val фраза = store.state.first { it is AuthState.Фраза }
        assertIs<AuthState.Фраза>(фраза)
        assertEquals(СЛОВА, фраза.слова, "показать надо ровно ту фразу, что ушла на сервер")

        store.фразаСохранена()

        val готово = store.state.value
        assertIs<AuthState.Готово>(готово)
        assertEquals("u-1", готово.userId)
        assertEquals("d-1", готово.deviceId)
    }

    /**
     * **Личность аккаунта уходит на сервер при первой же регистрации.**
     *
     * Без неё аккаунт остаётся без фразы навсегда, и вернуться в него после потери
     * телефона нечем: номер подтверждает только номер, а номера перевыпускают.
     */
    @Test
    fun при_регистрации_серверу_уходит_личность_аккаунта() = runTest {
        val store = дошлиДоКода(backgroundScope)
        store.кодИзменён("111111")

        store.подтвердить()
        store.state.first { it is AuthState.Фраза }

        assertContentEquals(ПУБЛИЧНЫЙ, api.посланныйIdentity, "identity_pub обязан быть послан")
        assertEquals(false, api.посланныйФорк, "форк цепочки без просьбы человека недопустим")
    }

    // ── возврат по фразе ────────────────────────────────────────────────────

    /**
     * Занятый номер ведёт к вводу фразы, а не к отказу.
     *
     * Аккаунт существует, и владение им доказывает фраза. Код при этом уже подтверждён и
     * не спрашивается заново: человек не виноват в том, что у него есть аккаунт.
     */
    @Test
    fun занятый_номер_ведёт_к_вводу_фразы() = runTest {
        api.наПроверкуКода = { CodeSubmitStep.Accepted("t-1") }
        api.наЗаведение = { DeviceCreateStep.IdentityMismatch }
        val store = дошлиДоКода(backgroundScope)
        store.кодИзменён("111111")

        store.подтвердить()

        val состояние = store.state.first { it is AuthState.ВводФразы }
        assertIs<AuthState.ВводФразы>(состояние)
        assertEquals(ТЕЛЕФОН, состояние.телефон)
        assertEquals("111111", состояние.код, "код помнится: второй раз SMS просить не за что")
    }

    /** Неверная фраза до сети не доходит: проверить её можно на месте. */
    @Test
    fun неверная_фраза_до_сети_не_доходит() = runTest {
        val store = дошлиДоВводаФразы(backgroundScope)
        личности.признаёт = false
        val былоЗаведений = api.заведений

        store.фразаИзменена("не та фраза совсем")
        store.войтиПоФразе()

        val состояние = store.state.value
        assertIs<AuthState.ВводФразы>(состояние)
        assertEquals("Фраза не та — проверьте запись", состояние.беда)
        assertEquals("не та фраза совсем", состояние.фраза, "набранное не отбираем")
        assertEquals(былоЗаведений, api.заведений, "сеть на неверной фразе не тревожим")
    }

    /** Верная фраза заводит устройство — и фразу больше не показывает: она у человека есть. */
    @Test
    fun верная_фраза_заводит_устройство_без_показа_фразы() = runTest {
        val store = дошлиДоВводаФразы(backgroundScope)
        api.наЗаведение = { DeviceCreateStep.Created("u-1", "d-2", "a-2") }

        store.фразаИзменена(СЛОВА.joinToString(" "))
        store.войтиПоФразе()

        val состояние = store.state.first { it is AuthState.Готово }
        assertIs<AuthState.Готово>(состояние)
        assertEquals("d-2", состояние.deviceId)
        assertContentEquals(ПУБЛИЧНЫЙ, api.посланныйIdentity)
    }

    /**
     * «Начать заново» форкает личность **только по прямому нажатию**.
     *
     * Собеседники увидят предупреждение о смене личности, а прежняя переписка не вернётся.
     * Поэтому флаг не ставится «чтобы прошло»: он ставится, когда человек сказал, что
     * фразы у него нет.
     */
    @Test
    fun начать_заново_форкает_личность_и_показывает_новую_фразу() = runTest {
        val store = дошлиДоВводаФразы(backgroundScope)
        api.наЗаведение = { DeviceCreateStep.Created("u-2", "d-3", "a-3") }

        store.начатьЗаново()

        val фраза = store.state.first { it is AuthState.Фраза }
        assertIs<AuthState.Фраза>(фраза)
        assertEquals(СЛОВА, фраза.слова, "новая личность — новая фраза, и показать её обязательно")
        assertEquals(true, api.посланныйФорк)
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

    /**
     * Уже заведённое устройство — не отказ.
     *
     * «Заведено» значит **есть сессия**: сервер выдал устройству токен. Одного секрета
     * мало — секрет пишется до вызова сервера, и без сессии он остался от прерванной
     * попытки, которую следующая перезапишет.
     */
    @Test
    fun уже_заведённое_устройство_это_не_отказ() = runTest {
        хранилище.сессия = Session(userId = "u-1", deviceId = "d-1", accessToken = "a-1")
        val store = дошлиДоКода(backgroundScope)
        store.кодИзменён("111111")

        store.подтвердить()

        assertEquals(AuthState.УжеЗаведено, store.state.first { it is AuthState.УжеЗаведено })
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

    private suspend fun дошлиДоВводаФразы(scope: kotlinx.coroutines.CoroutineScope): AuthStore {
        api.наПроверкуКода = { CodeSubmitStep.Accepted("t-1") }
        api.наЗаведение = { DeviceCreateStep.IdentityMismatch }
        val store = дошлиДоКода(scope)
        store.кодИзменён("111111")
        store.подтвердить()
        store.state.first { it is AuthState.ВводФразы }
        return store
    }

    private suspend fun дошлиДоКода(scope: kotlinx.coroutines.CoroutineScope): AuthStore {
        val store = store(scope)
        store.номерИзменён(ТЕЛЕФОН)
        store.запроситьКод()
        store.state.first { it is AuthState.Код }
        return store
    }

    /**
     * Поддельные личности: фраза постоянная, признание — переключателем.
     *
     * Настоящий вывод (HKDF из двенадцати слов) проверен векторами в `messenger-crypto`.
     * Здесь проверяется не он, а поведение экрана: что фраза показывается, что неверная не
     * доходит до сети, что форк требует нажатия.
     */
    private class ПоддельныеЛичности : AccountIdentities {
        var признаёт = true
        override fun fresh() = NewAccountIdentity(words = СЛОВА, identityPub = ПУБЛИЧНЫЙ)
        override fun fromWords(words: List<String>): ByteArray? = if (признаёт) ПУБЛИЧНЫЙ else null
    }

    private class ПоддельныйApi : AccountApi {
        var запросовКода = 0
        var заведений = 0
        var наЗапросКода: suspend () -> CodeRequestStep = { CodeRequestStep.CodeRequested("r-1") }
        var наПроверкуКода: suspend () -> CodeSubmitStep = { CodeSubmitStep.Accepted("t-1") }
        var посланныйIdentity: ByteArray? = null
        var посланныйФорк: Boolean = false
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
            forceNewIdentity: Boolean,
        ): DeviceCreateStep {
            заведений++
            посланныйIdentity = identityPub
            посланныйФорк = forceNewIdentity
            return наЗаведение()
        }
    }

    private class ПамятныеСекреты : DeviceSecretStore {
        var секрет: ByteArray? = null
        var сессия: Session? = null
        // Секрет без сессии — прерванная попытка, а не заведённое устройство. Настоящее
        // хранилище отвечает именно так; подделка, отвечавшая «секрет есть — значит
        // заведено», обрывала вход по фразе на «Устройство уже заведено».
        override fun hasDevice(): Boolean = сессия != null
        override fun saveDeviceSecret(secret: ByteArray) { секрет = secret }
        override fun saveSession(session: Session) { сессия = session }
        override fun session(): Session? = сессия
    }

    private companion object {
        const val ТЕЛЕФОН = "+79990000001"

        /** Двенадцать слов: их показывают человеку и по ним он возвращается. */
        val СЛОВА = listOf(
            "абажур", "берег", "ветер", "город", "дерево", "ель",
            "жизнь", "заря", "игла", "камень", "лето", "море",
        )
        val ПУБЛИЧНЫЙ = ByteArray(32) { 7 }

        val ключи = DeviceKeyFactory {
            DeviceKeyMaterial(
                encryptionPub = ByteArray(32) { 1 },
                signingPub = ByteArray(32) { 2 },
                secret = ByteArray(32) { 3 },
            )
        }
    }
}
