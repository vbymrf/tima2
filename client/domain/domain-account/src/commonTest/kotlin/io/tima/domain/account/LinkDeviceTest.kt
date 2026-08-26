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

    private val network = FakeLink()
    private val secrets = SecretMemorable()

    private fun new() = LinkNewDevice(network, keys, secrets)

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
        network.onStart = {
            assertNotNull(secrets.savedSecret, "секрет обязан быть записан ДО вызова сервера")
            LinkStartStep.Started("s-1", "tima://link/v1?…", "c-1")
        }

        val step = new().begin("Компьютер")

        assertIs<LinkBeginStep.ShowCode>(step)
        assertEquals("s-1", step.sessionId)
        assertContentEquals(MATERIAL.secret, secrets.savedSecret)
    }

    @Test
    fun имя_устройства_уходит_серверу() = runTest {
        new().begin("Компьютер Евгения")

        assertEquals(
            "Компьютер Евгения",
            network.sentName,
            "человек на том конце увидит это имя и по нему решит, подтверждать ли",
        )
    }

    /** Уже заведённое устройство привязывать нечего: у него есть свой аккаунт. */
    @Test
    fun заведённое_устройство_не_привязывается() = runTest {
        secrets.savedSession = Session("u-1", "d-1", "a-1")

        assertEquals(LinkBeginStep.AlreadyRegistered, new().begin("Компьютер"))
        assertEquals(0, network.starts, "сеть не тревожим: ответ известен на месте")
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
        val answers = mutableListOf(
            LinkClaimStep.NotReady,
            LinkClaimStep.Offline(retryAfterMs = 5_000),
            LinkClaimStep.NotReady,
            LinkClaimStep.Claimed("u-1", "d-2", "a-2"),
        )
        network.onPoll = { answers.removeFirst() }

        val step = new().await("s-1", "c-1")

        assertIs<LinkAwaitStep.Linked>(step)
        assertEquals("d-2", step.deviceId)
        assertTrue(answers.isEmpty(), "опрос обязан дойти до подтверждения")
    }

    /** Сессия записывается только после успеха: её наличие и есть «устройство заведено». */
    @Test
    fun сессия_пишется_только_после_подтверждения() = runTest {
        network.onPoll = { LinkClaimStep.Claimed("u-1", "d-2", "a-2") }

        new().await("s-1", "c-1")

        assertEquals("d-2", secrets.savedSession?.deviceId)
        assertEquals("a-2", secrets.savedSession?.accessToken)
    }

    /**
     * Срок кода вышел — отдельный исход.
     *
     * Человеку надо показать новый код, а не повторять попытку с прежним: сервер держит
     * сессию пять минут и старую больше не примет.
     */
    @Test
    fun вышедший_срок_называется_сроком() = runTest {
        network.onPoll = { LinkClaimStep.NotReady }

        assertEquals(LinkAwaitStep.Expired, new().await("s-1", "c-1"))
        assertNull(secrets.savedSession, "без подтверждения сессии нет")
    }

    // ── доверенное устройство ───────────────────────────────────────────────

    /** Подписывается то, что прочитано из кода, а не то, что мы думаем о сессии. */
    @Test
    fun подписывается_прочитанное_из_кода() = runTest {
        val signer = FakeSigner()
        network.onParsing = { LinkCode("s-9", "секрет-из-кода", ByteArray(32) { 1 }, ByteArray(32) { 2 }, "Телефон") }

        val step = ConfirmDeviceLink(network, signer).confirm("tima://link/v1?…")

        assertIs<LinkConfirmStep.Confirmed>(step)
        assertEquals("s-9", signer.last?.first)
        assertEquals("секрет-из-кода", signer.last?.second)
        assertEquals("s-9", network.confirmed)
    }

    /** Не наш код — до сети не доходит: разобрать его можно на месте. */
    @Test
    fun чужой_код_до_сети_не_доходит() = runTest {
        network.onParsing = { null }

        assertEquals(
            LinkConfirmStep.NotOurCode,
            ConfirmDeviceLink(network, FakeSigner()).confirm("что-то не то"),
        )
        assertEquals(0, network.confirmations)
    }

    /** Подписать нечем — тоже до сети: без ключа устройства подтверждать нельзя. */
    @Test
    fun без_ключа_устройства_подтверждать_нечем() = runTest {
        network.onParsing = { LinkCode("s-9", "s", ByteArray(32), ByteArray(32), null) }

        assertEquals(
            LinkConfirmStep.CannotSign,
            ConfirmDeviceLink(network, FakeSigner(can = false)).confirm("tima://link/v1?…"),
        )
        assertEquals(0, network.confirmations)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private class FakeLink : DeviceLinkStart, DeviceLinkConfirm {
        var starts = 0
        var confirmations = 0
        var sentName: String? = null
        var confirmed: String? = null

        var onStart: suspend () -> LinkStartStep = { LinkStartStep.Started("s-1", "код", "c-1") }
        var onPoll: suspend () -> LinkClaimStep = { LinkClaimStep.NotReady }
        var onParsing: (String) -> LinkCode? = { null }
        var onConfirmation: suspend () -> LinkConfirmStep = { LinkConfirmStep.Confirmed("d-2") }

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

        override fun parse(code: String): LinkCode? = onParsing(code)

        override suspend fun confirm(
            sessionId: String,
            secret: String,
            signature: ByteArray,
        ): LinkConfirmStep {
            confirmations++
            confirmed = sessionId
            return onConfirmation()
        }
    }

    private class FakeSigner(private val can: Boolean = true) : LinkSigner {
        var last: Pair<String, String>? = null
        override fun sign(
            sessionId: String,
            secret: String,
            encryptionPub: ByteArray,
            signingPub: ByteArray,
        ): ByteArray? {
            last = sessionId to secret
            return if (can) ByteArray(64) { 9 } else null
        }
    }

    private class SecretMemorable : DeviceSecretStore {
        // savedX: имена параметров ниже уже занимают secret и session.
        var savedSecret: ByteArray? = null
        var savedSession: Session? = null

        // Как в настоящем хранилище: заведено — значит есть сессия. Секрет без сессии это
        // прерванная попытка, которую следующая перезапишет.
        override fun hasDevice(): Boolean = savedSession != null
        override fun saveDeviceSecret(secret: ByteArray) { savedSecret = secret }
        override fun saveSession(session: Session) { savedSession = session }
        override fun session(): Session? = savedSession
    }

    private companion object {
        val MATERIAL = DeviceKeyMaterial(
            encryptionPub = ByteArray(32) { 1 },
            signingPub = ByteArray(32) { 2 },
            secret = ByteArray(32) { 3 },
        )
        val keys = DeviceKeyFactory { MATERIAL }
    }
}
