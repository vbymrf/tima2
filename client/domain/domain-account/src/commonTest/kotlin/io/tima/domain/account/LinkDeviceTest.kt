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
 * Привязка устройства: порядок записи и различение исходов.
 *
 * Роли проверяются по отдельности, потому что по отдельности и работают: новое устройство
 * только показывает код и ждёт, доверенное только подтверждает.
 */
class LinkDeviceTest {

    private val сеть = ПоддельнаяПривязка()
    private val секреты = ПамятныеСекреты()

    private fun новое() = LinkNewDevice(сеть, ключи, секреты)

    // ── новое устройство ────────────────────────────────────────────────────

    /**
     * **Секрет пишется до вызова сервера.**
     *
     * Та же причина, что в регистрации: умри процесс между «сервер завёл устройство» и
     * «мы сохранили закрытый ключ» — и устройство останется на сервере навсегда без ключа.
     * Расшифровать адресованное ему нельзя, снять его человек может только руками, а
     * выглядит это как «сообщения не приходят».
     */
    @Test
    fun секрет_пишется_до_вызова_сервера() = runTest {
        сеть.наСтарт = {
            assertNotNull(секреты.секрет, "секрет обязан быть записан ДО вызова сервера")
            LinkStartStep.Started("s-1", "tima://link/v1?…", "c-1")
        }

        val шаг = новое().begin("Компьютер")

        assertIs<LinkBeginStep.ShowCode>(шаг)
        assertEquals("s-1", шаг.sessionId)
        assertContentEquals(МАТЕРИАЛ.secret, секреты.секрет)
    }

    @Test
    fun имя_устройства_уходит_серверу() = runTest {
        новое().begin("Компьютер Евгения")

        assertEquals(
            "Компьютер Евгения",
            сеть.посланноеИмя,
            "человек на том конце увидит это имя и по нему решит, подтверждать ли",
        )
    }

    /** Уже заведённое устройство привязывать нечего: у него есть свой аккаунт. */
    @Test
    fun заведённое_устройство_не_привязывается() = runTest {
        секреты.сессия = Session("u-1", "d-1", "a-1")

        assertEquals(LinkBeginStep.AlreadyRegistered, новое().begin("Компьютер"))
        assertEquals(0, сеть.стартов, "сеть не тревожим: ответ известен на месте")
    }

    /**
     * Ожидание не прерывается ни «ещё не подтвердили», ни упавшей сетью.
     *
     * Код на экране, человек с телефоном рядом. Прерваться на первом `not_ready` значило бы
     * не дождаться никогда, а на первом отказе связи — заставить начинать заново из-за
     * мигнувшего Wi-Fi.
     */
    @Test
    fun ожидание_переживает_и_ожидание_и_отказ_связи() = runTest {
        val ответы = mutableListOf(
            LinkClaimStep.NotReady,
            LinkClaimStep.Offline(retryAfterMs = 5_000),
            LinkClaimStep.NotReady,
            LinkClaimStep.Claimed("u-1", "d-2", "a-2"),
        )
        сеть.наОпрос = { ответы.removeFirst() }

        val шаг = новое().await("s-1", "c-1")

        assertIs<LinkAwaitStep.Linked>(шаг)
        assertEquals("d-2", шаг.deviceId)
        assertTrue(ответы.isEmpty(), "опрос обязан дойти до подтверждения")
    }

    /** Сессия записывается только после успеха: её наличие и есть «устройство заведено». */
    @Test
    fun сессия_пишется_только_после_подтверждения() = runTest {
        сеть.наОпрос = { LinkClaimStep.Claimed("u-1", "d-2", "a-2") }

        новое().await("s-1", "c-1")

        assertEquals("d-2", секреты.сессия?.deviceId)
        assertEquals("a-2", секреты.сессия?.accessToken)
    }

    /**
     * Срок кода вышел — отдельный исход.
     *
     * Человеку надо показать новый код, а не повторять попытку с прежним: сервер держит
     * сессию пять минут и старую больше не примет.
     */
    @Test
    fun вышедший_срок_называется_сроком() = runTest {
        сеть.наОпрос = { LinkClaimStep.NotReady }

        assertEquals(LinkAwaitStep.Expired, новое().await("s-1", "c-1"))
        assertNull(секреты.сессия, "без подтверждения сессии нет")
    }

    // ── доверенное устройство ───────────────────────────────────────────────

    /** Подписывается то, что прочитано из кода, а не то, что мы думаем о сессии. */
    @Test
    fun подписывается_прочитанное_из_кода() = runTest {
        val подписант = ПоддельныйПодписант()
        сеть.наРазбор = { LinkCode("s-9", "секрет-из-кода", ByteArray(32) { 1 }, ByteArray(32) { 2 }, "Телефон") }

        val шаг = ConfirmDeviceLink(сеть, подписант).confirm("tima://link/v1?…")

        assertIs<LinkConfirmStep.Confirmed>(шаг)
        assertEquals("s-9", подписант.последний?.first)
        assertEquals("секрет-из-кода", подписант.последний?.second)
        assertEquals("s-9", сеть.подтверждённая)
    }

    /** Не наш код — до сети не доходит: разобрать его можно на месте. */
    @Test
    fun чужой_код_до_сети_не_доходит() = runTest {
        сеть.наРазбор = { null }

        assertEquals(
            LinkConfirmStep.NotOurCode,
            ConfirmDeviceLink(сеть, ПоддельныйПодписант()).confirm("что-то не то"),
        )
        assertEquals(0, сеть.подтверждений)
    }

    /** Подписать нечем — тоже до сети: без ключа устройства подтверждать нельзя. */
    @Test
    fun без_ключа_устройства_подтверждать_нечем() = runTest {
        сеть.наРазбор = { LinkCode("s-9", "s", ByteArray(32), ByteArray(32), null) }

        assertEquals(
            LinkConfirmStep.CannotSign,
            ConfirmDeviceLink(сеть, ПоддельныйПодписант(умеет = false)).confirm("tima://link/v1?…"),
        )
        assertEquals(0, сеть.подтверждений)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private class ПоддельнаяПривязка : DeviceLinkStart, DeviceLinkConfirm {
        var стартов = 0
        var подтверждений = 0
        var посланноеИмя: String? = null
        var подтверждённая: String? = null

        var наСтарт: suspend () -> LinkStartStep = { LinkStartStep.Started("s-1", "код", "c-1") }
        var наОпрос: suspend () -> LinkClaimStep = { LinkClaimStep.NotReady }
        var наРазбор: (String) -> LinkCode? = { null }
        var наПодтверждение: suspend () -> LinkConfirmStep = { LinkConfirmStep.Confirmed("d-2") }

        override suspend fun start(
            encryptionPub: ByteArray,
            signingPub: ByteArray,
            deviceName: String,
        ): LinkStartStep {
            стартов++
            посланноеИмя = deviceName
            return наСтарт()
        }

        override suspend fun claim(sessionId: String, claimToken: String): LinkClaimStep = наОпрос()

        override fun parse(code: String): LinkCode? = наРазбор(code)

        override suspend fun confirm(
            sessionId: String,
            secret: String,
            signature: ByteArray,
        ): LinkConfirmStep {
            подтверждений++
            подтверждённая = sessionId
            return наПодтверждение()
        }
    }

    private class ПоддельныйПодписант(private val умеет: Boolean = true) : LinkSigner {
        var последний: Pair<String, String>? = null
        override fun sign(
            sessionId: String,
            secret: String,
            encryptionPub: ByteArray,
            signingPub: ByteArray,
        ): ByteArray? {
            последний = sessionId to secret
            return if (умеет) ByteArray(64) { 9 } else null
        }
    }

    private class ПамятныеСекреты : DeviceSecretStore {
        var секрет: ByteArray? = null
        var сессия: Session? = null

        // Как в настоящем хранилище: заведено — значит есть сессия. Секрет без сессии это
        // прерванная попытка, которую следующая перезапишет.
        override fun hasDevice(): Boolean = сессия != null
        override fun saveDeviceSecret(secret: ByteArray) { секрет = secret }
        override fun saveSession(session: Session) { сессия = session }
        override fun session(): Session? = сессия
    }

    private companion object {
        val МАТЕРИАЛ = DeviceKeyMaterial(
            encryptionPub = ByteArray(32) { 1 },
            signingPub = ByteArray(32) { 2 },
            secret = ByteArray(32) { 3 },
        )
        val ключи = DeviceKeyFactory { МАТЕРИАЛ }
    }
}
