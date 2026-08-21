package io.tima.domain.account

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Порядок шагов заведения устройства. Проверяется без сети и без хранилища: правило
 * продукта здесь — именно порядок, и он обязан держаться на коде, а не на аккуратности
 * вызывающего.
 */
class RegisterDeviceTest {

    private val материал = DeviceKeyMaterial(
        encryptionPub = ByteArray(32) { 1 },
        signingPub = ByteArray(32) { 2 },
        secret = ByteArray(32) { 3 },
    )

    /** Хранилище, помнящее порядок вызовов: он и есть предмет проверки. */
    private class Хранилище : DeviceSecretStore {
        val порядок = mutableListOf<String>()
        var секрет: ByteArray? = null
        var сессия: Session? = null
        override fun hasDevice(): Boolean = сессия != null
        override fun saveDeviceSecret(secret: ByteArray) {
            порядок += "секрет"
            секрет = secret
        }
        override fun saveSession(session: Session) {
            порядок += "сессия"
            сессия = session
        }
        override fun session(): Session? = сессия
    }

    private class Сервер(
        val проверка: CodeSubmitStep = CodeSubmitStep.Accepted("rt-1"),
        val заведение: DeviceCreateStep = DeviceCreateStep.Created("u-1", "d-1", "jwt"),
        val запрос: CodeRequestStep = CodeRequestStep.CodeRequested("r-1", devCode = "123456"),
    ) : AccountApi {
        val вызовы = mutableListOf<String>()
        var посланныеКлючи: Pair<ByteArray, ByteArray>? = null
        var посланныйIdentity: ByteArray? = null
        var посланнаяПлатформа: String? = null

        override suspend fun requestCode(phone: String): CodeRequestStep {
            вызовы += "запрос"
            return запрос
        }
        override suspend fun submitCode(requestId: String, code: String): CodeSubmitStep {
            вызовы += "проверка"
            return проверка
        }
        override suspend fun createDevice(
            registrationToken: String,
            encryptionPub: ByteArray,
            signingPub: ByteArray,
            identityPub: ByteArray?,
            platform: String,
        ): DeviceCreateStep {
            вызовы += "заведение"
            посланныеКлючи = encryptionPub to signingPub
            посланныйIdentity = identityPub
            посланнаяПлатформа = platform
            return заведение
        }
    }

    private fun случай(
        сервер: Сервер = Сервер(),
        хранилище: Хранилище = Хранилище(),
    ) = Triple(
        RegisterDevice(сервер, DeviceKeyFactory { материал }, хранилище, platform = "desktop"),
        сервер,
        хранилище,
    )

    // ── удачный путь ─────────────────────────────────────────────────────────

    @Test
    fun секрет_записывается_до_вызова_сервера_а_токен_после() = runTest {
        // Главное решение всего класса. Умри процесс между «сервер завёл устройство» и
        // «мы сохранили закрытый ключ» — устройство останется на сервере навсегда без
        // ключа: адресованное ему не расшифровать, а выглядит это как «сообщения не
        // приходят». Обратный порядок стоит ровно ничего.
        val (регистрация, _, хранилище) = случай()

        val исход = регистрация.confirm("r-1", "123456")

        assertEquals(RegistrationStep.Registered("u-1", "d-1"), исход)
        assertEquals(listOf("секрет", "сессия"), хранилище.порядок)
    }

    @Test
    fun ключи_и_платформа_доезжают_до_сервера() = runTest {
        val (регистрация, сервер, _) = случай()

        регистрация.confirm("r-1", "123456", identityPub = ByteArray(32) { 9 })

        assertContentEquals(материал.encryptionPub, сервер.посланныеКлючи?.first)
        assertContentEquals(материал.signingPub, сервер.посланныеКлючи?.second)
        assertContentEquals(ByteArray(32) { 9 }, сервер.посланныйIdentity)
        assertEquals("desktop", сервер.посланнаяПлатформа)
    }

    @Test
    fun сессия_запоминается_целиком() = runTest {
        val (регистрация, _, хранилище) = случай()
        регистрация.confirm("r-1", "123456")
        assertEquals(Session("u-1", "d-1", "jwt"), хранилище.сессия)
        assertContentEquals(материал.secret, хранилище.секрет)
    }

    @Test
    fun код_запрашивается_и_dev_код_доезжает() = runTest {
        // На стенде с TIMA_DEV_SMS код приходит в ответе, и харнесс К4 живёт этим.
        val (регистрация, _, _) = случай()
        val исход = регистрация.requestCode("+79001234567")
        assertEquals(CodeRequestStep.CodeRequested("r-1", "123456"), исход)
    }

    // ── отказы ───────────────────────────────────────────────────────────────

    @Test
    fun неверный_код_не_порождает_ключей_и_не_пишет_секрет() = runTest {
        // Порождать ключи до проверки кода значило бы тратить работу на каждый промах
        // пальцем — и, хуже, перезаписывать секрет из-за опечатки.
        val (регистрация, сервер, хранилище) = случай(Сервер(проверка = CodeSubmitStep.WrongCode))

        assertEquals(RegistrationStep.WrongCode, регистрация.confirm("r-1", "000000"))

        assertEquals(listOf("проверка"), сервер.вызовы, "до заведения дело дойти не должно")
        assertTrue(хранилище.порядок.isEmpty(), "хранилище не тронуто")
        assertNull(хранилище.секрет)
    }

    @Test
    fun уже_заведённое_устройство_не_перезаводится_молча() = runTest {
        // Случайный повторный вызов оставил бы на сервере устройство, к которому у нас
        // больше нет закрытого ключа: тихий зомби в списке устройств человека.
        val хранилище = Хранилище().apply { сессия = Session("u-0", "d-0", "старый") }
        val (регистрация, сервер, _) = случай(хранилище = хранилище)

        assertEquals(RegistrationStep.AlreadyRegistered, регистрация.confirm("r-1", "123456"))

        assertTrue(сервер.вызовы.isEmpty(), "в сеть ходить незачем")
        assertEquals(Session("u-0", "d-0", "старый"), хранилище.сессия, "прежняя сессия цела")
    }

    @Test
    fun перезавести_можно_только_явно() = runTest {
        val хранилище = Хранилище().apply { сессия = Session("u-0", "d-0", "старый") }
        val (регистрация, _, _) = случай(хранилище = хранилище)

        val исход = регистрация.confirm("r-1", "123456", replaceExisting = true)

        assertEquals(RegistrationStep.Registered("u-1", "d-1"), исход)
        assertEquals("jwt", хранилище.сессия?.accessToken)
    }

    @Test
    fun чужая_личность_секрет_не_выбрасывает() = runTest {
        // Ключи ещё понадобятся тому пути, который человек выберет дальше — возврату по
        // секретной фразе. Стереть их значило бы заставить пройти регистрацию заново.
        val (регистрация, _, хранилище) = случай(
            Сервер(заведение = DeviceCreateStep.IdentityMismatch),
        )

        assertEquals(RegistrationStep.IdentityMismatch, регистрация.confirm("r-1", "123456"))

        assertNotNull(хранилище.секрет, "секрет остаётся: он ещё пригодится")
        assertNull(хранилище.сессия, "а сессии нет — устройство не заведено")
    }

    @Test
    fun просроченный_токен_отличается_от_неверного_кода() = runTest {
        // Человеку это разные сообщения: «код не тот» и «слишком долго вводил».
        val (регистрация, _, _) = случай(Сервер(заведение = DeviceCreateStep.TokenExpired))
        assertEquals(RegistrationStep.CodeExpired, регистрация.confirm("r-1", "123456"))
    }

    @Test
    fun обрыв_связи_несёт_паузу_насквозь() = runTest {
        // Пауза приходит из сетевого слоя, где её измерили в живой сети. Domain её не
        // придумывает и не теряет.
        val наПроверке = случай(Сервер(проверка = CodeSubmitStep.Offline(5_000))).first
        assertEquals(RegistrationStep.Offline(5_000), наПроверке.confirm("r-1", "1"))

        val (наЗаведении, _, хранилище) = случай(Сервер(заведение = DeviceCreateStep.Offline(120_000)))
        assertEquals(RegistrationStep.Offline(120_000), наЗаведении.confirm("r-1", "1"))
        assertNotNull(хранилище.секрет, "секрет уже записан — повтор возьмёт его же")
        assertNull(хранилище.сессия)
    }

    @Test
    fun отказ_сервера_доносит_причину() = runTest {
        val (регистрация, _, _) = случай(Сервер(заведение = DeviceCreateStep.Refused("bad_keys")))
        assertEquals(RegistrationStep.Refused("bad_keys"), регистрация.confirm("r-1", "1"))
    }
}
