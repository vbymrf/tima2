package io.tima.feature.auth

import io.tima.domain.account.AccountIdentities
import io.tima.domain.account.AccountApi
import io.tima.domain.account.CodeRequestStep
import io.tima.domain.account.CodeSubmitStep
import io.tima.domain.account.DeviceCreateStep
import io.tima.domain.account.DeviceKeyFactory
import io.tima.domain.account.DeviceKeyMaterial
import io.tima.domain.account.DeviceSecretStore
import io.tima.domain.account.DeviceLinkStart
import io.tima.domain.account.LinkClaimStep
import io.tima.domain.account.LinkNewDevice
import io.tima.domain.account.LinkStartStep
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

    private val api = FakeApi()
    private val store = SecretMemorable()

    private val identity = FakeIdentity()

    private val link = FakeStart()

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = AuthStore(
        register = RegisterDevice(api, keys, store, platform = "проба"),
        identities = identity,
        scope = scope,
        link = LinkNewDevice(link, keys, store),
        deviceName = "Компьютер",
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
        api.onRequestCode = { CodeRequestStep.Offline(retryAfterMs = 5_000) }
        val store = store(backgroundScope)
        store.changedNumber("+79990000001")

        store.requestCode()

        val state = store.state.first { it is AuthState.Phone && !it.expect }
        assertIs<AuthState.Phone>(state)
        assertEquals("+79990000001", state.number, "номер отобрали при отказе сети")
        assertTrue(state.trouble.orEmpty().contains("5 с"), "срок повтора обязан быть назван: ${state.trouble}")
    }

    /**
     * Неверный код остаётся в поле.
     *
     * Человек чаще опечатался в одной цифре, чем набрал наугад, и стирать за него шесть
     * цифр — это заставить набирать их заново.
     */
    @Test
    fun неверный_код_остаётся_в_поле() = runTest {
        api.onCheckCode = { CodeSubmitStep.WrongCode }
        val store = deliveredUntilCode(backgroundScope)
        store.changedCode("123456")

        store.confirm()

        val state = store.state.first { it is AuthState.Code && !it.expect }
        assertIs<AuthState.Code>(state)
        assertEquals("123456", state.code)
        assertEquals("Код неверен или просрочен", state.trouble)
    }

    /** «Изменить номер» возвращает к телефону с уже набранным номером. */
    @Test
    fun назад_помнит_номер() = runTest {
        val store = deliveredUntilCode(backgroundScope)

        store.back()

        val state = store.state.value
        assertIs<AuthState.Phone>(state)
        assertEquals(PHONE, state.number)
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
        val hold = CompletableDeferred<CodeRequestStep>()
        api.onRequestCode = { hold.await() }
        val store = store(backgroundScope)
        store.changedNumber(PHONE)

        store.requestCode()
        store.state.first { it is AuthState.Phone && it.expect }
        store.requestCode()
        store.requestCode()

        hold.complete(CodeRequestStep.CodeRequested(requestId = "r-1"))
        store.state.first { it is AuthState.Code }

        assertEquals(1, api.codeRequests, "вызовов запроса кода обязано быть ровно одно")
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
        val store = deliveredUntilCode(backgroundScope)
        store.changedCode("111111")

        store.confirm()

        val phrase = store.state.first { it is AuthState.Phrase }
        assertIs<AuthState.Phrase>(phrase)
        assertEquals(WORDS, phrase.words, "показать надо ровно ту фразу, что ушла на сервер")

        store.savedPhrase()

        val done = store.state.value
        assertIs<AuthState.Done>(done)
        assertEquals("u-1", done.userId)
        assertEquals("d-1", done.deviceId)
    }

    /**
     * **Личность аккаунта уходит на сервер при первой же регистрации.**
     *
     * Без неё аккаунт остаётся без фразы навсегда, и вернуться в него после потери
     * телефона нечем: номер подтверждает только номер, а номера перевыпускают.
     */
    @Test
    fun при_регистрации_серверу_уходит_личность_аккаунта() = runTest {
        val store = deliveredUntilCode(backgroundScope)
        store.changedCode("111111")

        store.confirm()
        store.state.first { it is AuthState.Phrase }

        assertContentEquals(PUBLIC, api.sentIdentity, "identity_pub обязан быть послан")
        assertEquals(false, api.sentFork, "форк цепочки без просьбы человека недопустим")
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
        api.onCheckCode = { CodeSubmitStep.Accepted("t-1") }
        api.onCreation = { DeviceCreateStep.IdentityMismatch }
        val store = deliveredUntilCode(backgroundScope)
        store.changedCode("111111")

        store.confirm()

        val state = store.state.first { it is AuthState.PhraseInput }
        assertIs<AuthState.PhraseInput>(state)
        assertEquals(PHONE, state.phone)
        // Дальше идём с токеном, а не с кодом: код одноразовый и погашен проверкой.
        // Второй confirm с ним отвечал «неверен или просрочен», и вход по фразе не мог
        // сработать никогда — найдено живым прогоном 2026-08-25.
        assertTrue(state.registrationToken.isNotBlank(), "токен обязан сохраниться")
    }

    /**
     * Вход по фразе не гасит код повторно.
     *
     * Найдено живым прогоном 2026-08-25: вход по фразе отвечал «код неверен или
     * просрочен» и не мог сработать НИКОГДА. Код одноразовый — он гасится в
     * `/auth/verify` первым обращением, — а второй шаг того же входа приходил с ним же.
     *
     * Симптом указывал на срок («просрочен»), причина была в повторном использовании.
     * Поэтому проверка считает обращения к проверке кода, а не смотрит на исход: исход
     * можно позеленить подделкой, а лишнее обращение — нет.
     */
    @Test
    fun вход_по_фразе_идёт_с_токеном_а_не_с_кодом() = runTest {
        val store = deliveredUntilInputPhrase(backgroundScope)
        val checkWas = api.codeChecks

        store.changedPhrase(WORDS.joinToString(" "))
        store.enterByPhrase()
        store.state.first { it !is AuthState.PhraseInput || it.expect.not() }

        assertEquals(
            checkWas,
            api.codeChecks,
            "код проверен второй раз — сервер погасил его при первой проверке",
        )
    }

    /**
     * Из ввода фразы есть выход к номеру.
     *
     * Найдено заказчиком на живом телефоне: человек, чей номер уже занят, оказывался
     * заперт между «вспомните фразу» и «начните заново». Второе стирает прежнюю личность,
     * то есть опечатка в номере стоила бы аккаунта — цена, несоразмерная ошибке.
     */
    @Test
    fun из_ввода_фразы_можно_вернуться_к_номеру() = runTest {
        val store = deliveredUntilInputPhrase(backgroundScope)

        store.back()

        val state = store.state.first { it is AuthState.Phone }
        assertIs<AuthState.Phone>(state)
        assertEquals(PHONE, state.number, "набранный номер обязан сохраниться")
        // Возвращённый номер уже с плюсом, и склейка обязана взять его как есть: иначе
        // к нему приписался бы код страны и получился бы номер, которого нет.
        assertEquals(PHONE, state.fullNumber)
    }

    /** Неверная фраза до сети не доходит: проверить её можно на месте. */
    @Test
    fun неверная_фраза_до_сети_не_доходит() = runTest {
        val store = deliveredUntilInputPhrase(backgroundScope)
        identity.accepts = false
        val creationWas = api.creations

        store.changedPhrase("не та фраза совсем")
        store.enterByPhrase()

        val state = store.state.value
        assertIs<AuthState.PhraseInput>(state)
        assertEquals("Фраза не та — проверьте запись", state.trouble)
        assertEquals("не та фраза совсем", state.phrase, "набранное не отбираем")
        assertEquals(creationWas, api.creations, "сеть на неверной фразе не тревожим")
    }

    /** Верная фраза заводит устройство — и фразу больше не показывает: она у человека есть. */
    @Test
    fun верная_фраза_заводит_устройство_без_показа_фразы() = runTest {
        val store = deliveredUntilInputPhrase(backgroundScope)
        api.onCreation = { DeviceCreateStep.Created("u-1", "d-2", "a-2") }

        store.changedPhrase(WORDS.joinToString(" "))
        store.enterByPhrase()

        val state = store.state.first { it is AuthState.Done }
        assertIs<AuthState.Done>(state)
        assertEquals("d-2", state.deviceId)
        assertContentEquals(PUBLIC, api.sentIdentity)
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
        val store = deliveredUntilInputPhrase(backgroundScope)
        api.onCreation = { DeviceCreateStep.Created("u-2", "d-3", "a-3") }

        store.startAnew()

        val phrase = store.state.first { it is AuthState.Phrase }
        assertIs<AuthState.Phrase>(phrase)
        assertEquals(WORDS, phrase.words, "новая личность — новая фраза, и показать её обязательно")
        assertEquals(true, api.sentFork)
    }

    /**
     * Код стенда виден на экране — и только когда его прислал сервер.
     *
     * Поле заполняется лишь при `TIMA_DEV_SMS`; решает это сервер, а не мы. Без этого
     * сквозной путь по стенду требовал бы настоящей SMS на каждый прогон.
     */
    @Test
    fun код_стенда_доезжает_до_экрана() = runTest {
        api.onRequestCode = { CodeRequestStep.CodeRequested(requestId = "r-1", devCode = "424242") }
        val store = store(backgroundScope)
        store.changedNumber(PHONE)

        store.requestCode()

        val state = store.state.first { it is AuthState.Code }
        assertEquals("424242", (state as AuthState.Code).standHint)
    }

    @Test
    fun без_кода_стенда_подсказки_нет() = runTest {
        val store = deliveredUntilCode(backgroundScope)
        assertNull((store.state.value as AuthState.Code).standHint)
    }

    /**
     * Просроченный токен возвращает к номеру, а не оставляет на коде.
     *
     * Иначе человек повторяет код, который уже не примут, и видит одну и ту же жалобу.
     */
    @Test
    fun просроченный_токен_возвращает_к_номеру() = runTest {
        api.onCheckCode = { CodeSubmitStep.Accepted("t-1") }
        api.onCreation = { DeviceCreateStep.TokenExpired }
        val store = deliveredUntilCode(backgroundScope)
        store.changedCode("111111")

        store.confirm()

        val state = store.state.first { it is AuthState.Phone }
        assertIs<AuthState.Phone>(state)
        assertEquals(PHONE, state.number, "номер сохраняется: его уже набрали")
        assertEquals("Код просрочен — запросите новый", state.trouble)
    }

    /**
     * Уже заведённое устройство — не отказ.
     *
     * «Заведено» значит **есть сессия**: сервер выдал устройству токен. Одного секрета
     * мало — секрет пишется до вызова сервера, и без сессии он остался от прерванной
     * попытки, которую следующая перезапишет.
     */
    @Test
    fun already_created_device_this_not_refusal() = runTest {
        store.savedSession = Session(userId = "u-1", deviceId = "d-1", accessToken = "a-1")
        val store = deliveredUntilCode(backgroundScope)
        store.changedCode("111111")

        store.confirm()

        assertEquals(AuthState.CreatedAlready, store.state.first { it is AuthState.CreatedAlready })
    }

    // ── привязка к существующему аккаунту ───────────────────────────────────

    /**
     * Код показывается, и **имя устройства уходит серверу**.
     *
     * Имя увидит человек на телефоне перед «Доверить» — по нему он и отличит свой ПК от
     * чужого кода, подсунутого в переписке.
     */
    @Test
    fun подключение_показывает_код() = runTest {
        val store = store(backgroundScope)

        store.connect()

        val state = store.state.first { it is AuthState.DisplayCode && it.code != null }
        assertIs<AuthState.DisplayCode>(state)
        assertEquals("tima://link/v1?session_id=s-1", state.code)
        assertEquals("Компьютер", link.sentName)
    }

    /** Подтвердили на телефоне — устройство заведено, и без всякой SMS. */
    @Test
    fun подтверждение_на_телефоне_доводит_до_готово() = runTest {
        link.onPoll = { LinkClaimStep.Claimed("u-1", "d-9", "a-9") }
        val store = store(backgroundScope)

        store.connect()

        val state = store.state.first { it is AuthState.Done }
        assertIs<AuthState.Done>(state)
        assertEquals("d-9", state.deviceId)
        assertEquals(0, api.codeRequests, "привязка идёт без SMS — в этом весь её смысл")
    }

    /**
     * Срок кода вышел — «попросите новый», а не «ошибка».
     *
     * Человек мог просто не успеть дойти до телефона: сессия живёт пять минут.
     */
    @Test
    fun вышедший_срок_просит_новый_код() = runTest {
        link.onPoll = { LinkClaimStep.NotReady }
        val store = store(backgroundScope)

        store.connect()

        val state = store.state.first { it is AuthState.DisplayCode && it.trouble != null }
        assertTrue(state.trouble.orEmpty().contains("новый"), "беда: ${state.trouble}")
    }

    /** «Назад» с показа кода возвращает к номеру: путь по SMS никуда не делся. */
    @Test
    fun назад_с_кода_возвращает_к_номеру() = runTest {
        val store = store(backgroundScope)
        store.connect()
        store.state.first { it is AuthState.DisplayCode }

        store.back()

        assertIs<AuthState.Phone>(store.state.value)
    }

    /**
     * После просроченного кода можно попросить новый — и только тогда.
     *
     * Живой код рядом с кнопкой «новый» означал бы, что промах по экрану обнуляет
     * работающий код, а телефон на том конце покажет отказ.
     */
    @Test
    fun новый_код_просится_только_вместо_мёртвого() = runTest {
        link.onPoll = { LinkClaimStep.NotReady }
        val store = store(backgroundScope)

        store.connect()
        store.state.first { it is AuthState.DisplayCode && it.code != null }
        val displayedSessions = link.starts
        store.connect()
        assertEquals(displayedSessions, link.starts, "живой код не сбрасывается вторым нажатием")

        store.state.first { it is AuthState.DisplayCode && it.trouble != null }
        store.connect()

        store.state.first { it is AuthState.DisplayCode && it.code != null }
        assertEquals(displayedSessions + 1, link.starts, "после просрочки новый код обязан прийти")
    }

    @Test
    fun плохой_номер_отсекается_до_сети() = runTest {
        api.onRequestCode = { CodeRequestStep.BadPhone("не E.164") }
        val store = store(backgroundScope)
        store.changedNumber("восемь-девять-ноль")

        store.requestCode()

        val state = store.state.first { it is AuthState.Phone && !it.expect }
        assertTrue(state.trouble.orEmpty().contains("не E.164"))
    }

    // ── помощники ───────────────────────────────────────────────────────────

    private suspend fun deliveredUntilInputPhrase(scope: kotlinx.coroutines.CoroutineScope): AuthStore {
        api.onCheckCode = { CodeSubmitStep.Accepted("t-1") }
        api.onCreation = { DeviceCreateStep.IdentityMismatch }
        val store = deliveredUntilCode(scope)
        store.changedCode("111111")
        store.confirm()
        store.state.first { it is AuthState.PhraseInput }
        return store
    }

    private suspend fun deliveredUntilCode(scope: kotlinx.coroutines.CoroutineScope): AuthStore {
        val store = store(scope)
        store.changedNumber(PHONE)
        store.requestCode()
        store.state.first { it is AuthState.Code }
        return store
    }

    /**
     * Поддельные личности: фраза постоянная, признание — переключателем.
     *
     * Настоящий вывод (HKDF из двенадцати слов) проверен векторами в `messenger-crypto`.
     * Здесь проверяется не он, а поведение экрана: что фраза показывается, что неверная не
     * доходит до сети, что форк требует нажатия.
     */
    private class FakeIdentity : AccountIdentities {
        var accepts = true
        override fun fresh() = NewAccountIdentity(words = WORDS, identityPub = PUBLIC)
        override fun fromWords(words: List<String>): ByteArray? = if (accepts) PUBLIC else null
    }

    private class FakeStart : DeviceLinkStart {
        var starts = 0
        var sentName: String? = null
        var onStart: suspend () -> LinkStartStep = {
            LinkStartStep.Started("s-1", "tima://link/v1?session_id=s-1", "c-1")
        }
        var onPoll: suspend () -> LinkClaimStep = { LinkClaimStep.NotReady }

        override suspend fun start(
            encryptionPub: ByteArray,
            signingPub: ByteArray,
            deviceName: String,
        ): LinkStartStep {
            starts++
            sentName = deviceName
            return onStart()
        }

        override suspend fun claim(sessionId: String, claimToken: String): LinkClaimStep = onPoll()
    }

    private class FakeApi : AccountApi {
        var codeRequests = 0
        var creations = 0
        var onRequestCode: suspend () -> CodeRequestStep = { CodeRequestStep.CodeRequested("r-1") }
        var onCheckCode: suspend () -> CodeSubmitStep = { CodeSubmitStep.Accepted("t-1") }
        var sentIdentity: ByteArray? = null
        var sentFork: Boolean = false
        var onCreation: suspend () -> DeviceCreateStep = {
            DeviceCreateStep.Created(userId = "u-1", deviceId = "d-1", accessToken = "a-1")
        }

        override suspend fun requestCode(phone: String): CodeRequestStep {
            codeRequests++
            return onRequestCode()
        }

        var codeChecks = 0

        override suspend fun submitCode(requestId: String, code: String): CodeSubmitStep {
            codeChecks++
            return onCheckCode()
        }

        override suspend fun createDevice(
            registrationToken: String,
            encryptionPub: ByteArray,
            signingPub: ByteArray,
            identityPub: ByteArray?,
            platform: String,
            forceNewIdentity: Boolean,
        ): DeviceCreateStep {
            creations++
            sentIdentity = identityPub
            sentFork = forceNewIdentity
            return onCreation()
        }
    }

    private class SecretMemorable : DeviceSecretStore {
        // savedX, а не X: имена параметров уже заняли secret и session, и поле,
        // названное так же, закрывало бы их собой — присваивание уходило бы в
        // параметр, а проверка читала бы null.
        var savedSecret: ByteArray? = null
        var savedSession: Session? = null
        // Секрет без сессии — прерванная попытка, а не заведённое устройство. Настоящее
        // хранилище отвечает именно так; подделка, отвечавшая «секрет есть — значит
        // заведено», обрывала вход по фразе на «Устройство уже заведено».
        override fun hasDevice(): Boolean = savedSession != null
        override fun saveDeviceSecret(secret: ByteArray) { savedSecret = secret }
        override fun saveSession(session: Session) { savedSession = session }
        override fun session(): Session? = savedSession
    }

    private companion object {
        const val PHONE = "+79990000001"

        /** Двенадцать слов: их показывают человеку и по ним он возвращается. */
        val WORDS = listOf(
            "абажур", "берег", "ветер", "город", "дерево", "ель",
            "жизнь", "заря", "игла", "камень", "лето", "море",
        )
        val PUBLIC = ByteArray(32) { 7 }

        val keys = DeviceKeyFactory {
            DeviceKeyMaterial(
                encryptionPub = ByteArray(32) { 1 },
                signingPub = ByteArray(32) { 2 },
                secret = ByteArray(32) { 3 },
            )
        }
    }
}
