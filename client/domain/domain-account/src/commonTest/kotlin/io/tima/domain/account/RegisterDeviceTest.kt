package io.tima.domain.account

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Порядок шагов заведения устройства. Проверяется без сети и без хранилища: правило
 * продукта здесь — именно порядок, и он обязан держаться на коде, а не на аккуратности
 * вызывающего.
 */
class RegisterDeviceTest {

    private val material = DeviceKeyMaterial(
        encryptionPub = ByteArray(32) { 1 },
        signingPub = ByteArray(32) { 2 },
        secret = ByteArray(32) { 3 },
    )

    /** Хранилище, помнящее порядок вызовов: он и есть предмет проверки. */
    private class Store : DeviceSecretStore {
        val order = mutableListOf<String>()
        // savedX, а не X: имена параметров уже заняли secret и session, и поле,
        // названное так же, закрывало бы их собой — присваивание уходило бы в
        // параметр, а проверка читала бы null.
        var savedSecret: ByteArray? = null
        var savedSession: Session? = null
        override fun hasDevice(): Boolean = savedSession != null
        override fun saveDeviceSecret(secret: ByteArray) {
            order += "секрет"
            savedSecret = secret
        }
        override fun saveSession(session: Session) {
            order += "сессия"
            savedSession = session
        }
        override fun session(): Session? = savedSession
    }

    private class Server(
        val check: CodeSubmitStep = CodeSubmitStep.Accepted("rt-1"),
        val creation: DeviceCreateStep = DeviceCreateStep.Created("u-1", "d-1", "jwt"),
        val request: CodeRequestStep = CodeRequestStep.CodeRequested("r-1", devCode = "123456"),
    ) : AccountApi {
        val calls = mutableListOf<String>()
        var sentKeys: Pair<ByteArray, ByteArray>? = null
        var sentIdentity: ByteArray? = null
        var sentPlatform: String? = null
        var sentFork: Boolean = false

        override suspend fun requestCode(phone: String): CodeRequestStep {
            calls += "запрос"
            return request
        }
        override suspend fun submitCode(requestId: String, code: String): CodeSubmitStep {
            calls += "проверка"
            return check
        }
        override suspend fun createDevice(
            registrationToken: String,
            encryptionPub: ByteArray,
            signingPub: ByteArray,
            identityPub: ByteArray?,
            platform: String,
            forceNewIdentity: Boolean,
        ): DeviceCreateStep {
            calls += "заведение"
            sentKeys = encryptionPub to signingPub
            sentIdentity = identityPub
            sentPlatform = platform
            sentFork = forceNewIdentity
            return creation
        }
    }

    private fun case(
        server: Server = Server(),
        store: Store = Store(),
    ) = Triple(
        RegisterDevice(server, DeviceKeyFactory { material }, store, platform = "desktop"),
        server,
        store,
    )

    // ── удачный путь ─────────────────────────────────────────────────────────

    @Test
    fun секрет_записывается_до_вызова_сервера_а_токен_после() = runTest {
        // Главное решение всего класса. Умри процесс между «сервер завёл устройство» и
        // «мы сохранили закрытый ключ» — устройство останется на сервере навсегда без
        // ключа: адресованное ему не расшифровать, а выглядит это как «сообщения не
        // приходят». Обратный порядок стоит ровно ничего.
        val (registration, _, store) = case()

        val outcome = registration.confirm("r-1", "123456")

        assertEquals(RegistrationStep.Registered("u-1", "d-1"), outcome)
        assertEquals(listOf("секрет", "сессия"), store.order)
    }

    @Test
    fun ключи_и_платформа_доезжают_до_сервера() = runTest {
        val (registration, server, _) = case()

        registration.confirm("r-1", "123456", identityPub = ByteArray(32) { 9 })

        assertContentEquals(material.encryptionPub, server.sentKeys?.first)
        assertContentEquals(material.signingPub, server.sentKeys?.second)
        assertContentEquals(ByteArray(32) { 9 }, server.sentIdentity)
        assertEquals("desktop", server.sentPlatform)
    }

    @Test
    fun сессия_запоминается_целиком() = runTest {
        val (registration, _, store) = case()
        registration.confirm("r-1", "123456")
        assertEquals(Session("u-1", "d-1", "jwt"), store.savedSession)
        assertContentEquals(material.secret, store.savedSecret)
    }

    @Test
    fun код_запрашивается_и_dev_код_доезжает() = runTest {
        // На стенде с TIMA_DEV_SMS код приходит в ответе, и харнесс К4 живёт этим.
        val (registration, _, _) = case()
        val outcome = registration.requestCode("+79001234567")
        assertEquals(CodeRequestStep.CodeRequested("r-1", "123456"), outcome)
    }

    // ── отказы ───────────────────────────────────────────────────────────────

    @Test
    fun неверный_код_не_порождает_ключей_и_не_пишет_секрет() = runTest {
        // Порождать ключи до проверки кода значило бы тратить работу на каждый промах
        // пальцем — и, хуже, перезаписывать секрет из-за опечатки.
        val (registration, server, store) = case(Server(check = CodeSubmitStep.WrongCode))

        assertEquals(RegistrationStep.WrongCode, registration.confirm("r-1", "000000"))

        assertEquals(listOf("проверка"), server.calls, "до заведения дело дойти не должно")
        assertTrue(store.order.isEmpty(), "хранилище не тронуто")
        assertNull(store.savedSecret)
    }

    @Test
    fun уже_заведённое_устройство_не_перезаводится_молча() = runTest {
        // Случайный повторный вызов оставил бы на сервере устройство, к которому у нас
        // больше нет закрытого ключа: тихий зомби в списке устройств человека.
        val store = Store().apply { savedSession = Session("u-0", "d-0", "старый") }
        val (registration, server, _) = case(store = store)

        assertEquals(RegistrationStep.AlreadyRegistered, registration.confirm("r-1", "123456"))

        assertTrue(server.calls.isEmpty(), "в сеть ходить незачем")
        assertEquals(Session("u-0", "d-0", "старый"), store.savedSession, "прежняя сессия цела")
    }

    @Test
    fun перезавести_можно_только_явно() = runTest {
        val store = Store().apply { savedSession = Session("u-0", "d-0", "старый") }
        val (registration, _, _) = case(store = store)

        val outcome = registration.confirm("r-1", "123456", replaceExisting = true)

        assertEquals(RegistrationStep.Registered("u-1", "d-1"), outcome)
        assertEquals("jwt", store.savedSession?.accessToken)
    }

    @Test
    fun чужая_личность_секрет_не_выбрасывает() = runTest {
        // Ключи ещё понадобятся тому пути, который человек выберет дальше — возврату по
        // секретной фразе. Стереть их значило бы заставить пройти регистрацию заново.
        val (registration, _, store) = case(
            Server(creation = DeviceCreateStep.IdentityMismatch),
        )

        // Исход несёт registration_token: дальше — вход по фразе или «начать заново», и
        // оба идут с ним, потому что код к этому моменту погашен проверкой.
        val step = assertIs<RegistrationStep.IdentityMismatch>(registration.confirm("r-1", "123456"))
        assertTrue(step.registrationToken.isNotBlank(), "без токена продолжить вход нечем")

        assertNotNull(store.savedSecret, "секрет остаётся: он ещё пригодится")
        assertNull(store.savedSession, "а сессии нет — устройство не заведено")
    }

    @Test
    fun просроченный_токен_отличается_от_неверного_кода() = runTest {
        // Человеку это разные сообщения: «код не тот» и «слишком долго вводил».
        val (registration, _, _) = case(Server(creation = DeviceCreateStep.TokenExpired))
        assertEquals(RegistrationStep.CodeExpired, registration.confirm("r-1", "123456"))
    }

    @Test
    fun обрыв_связи_несёт_паузу_насквозь() = runTest {
        // Пауза приходит из сетевого слоя, где её измерили в живой сети. Domain её не
        // придумывает и не теряет.
        val onCheck = case(Server(check = CodeSubmitStep.Offline(5_000))).first
        assertEquals(RegistrationStep.Offline(5_000), onCheck.confirm("r-1", "1"))

        val (onCreation, _, store) = case(Server(creation = DeviceCreateStep.Offline(120_000)))
        assertEquals(RegistrationStep.Offline(120_000), onCreation.confirm("r-1", "1"))
        assertNotNull(store.savedSecret, "секрет уже записан — повтор возьмёт его же")
        assertNull(store.savedSession)
    }

    @Test
    fun отказ_сервера_доносит_причину() = runTest {
        val (registration, _, _) = case(Server(creation = DeviceCreateStep.Refused("bad_keys")))
        assertEquals(RegistrationStep.Refused("bad_keys"), registration.confirm("r-1", "1"))
    }
}
