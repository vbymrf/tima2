package io.tima.shared

import io.tima.core.database.desktopDatabase
import io.tima.core.encryption.deviceIdentityFrom
import io.tima.domain.account.Session
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Приёмник: **своё эхо не показывается вторым сообщением**.
 *
 * Сервер рассылает конверт по всем обёрткам ключа, включая нашу собственную — так второе
 * устройство человека и получает отправленное с первого. Копия, вернувшаяся на ТО ЖЕ
 * устройство, входящим сообщением не является: она уже лежит в исходящих.
 *
 * Найдено живым прогоном: своё сообщение показывалось дважды — один раз своим, второй
 * чужим, — и на телефоне, и на ПК одновременно. Заодно эхо ломало серию: следующее чужое
 * сообщение считалось продолжением и теряло имя автора.
 */
class ReceiverTest {

    private val catalog: File = File.createTempFile("tima-приёмник", "").let {
        it.delete(); it.mkdirs(); it
    }

    @AfterTest
    fun remove() {
        catalog.deleteRecursively()
    }

    private fun receiver(myDeviceId: String): Receiver {
        val session = Session(userId = "u-я", deviceId = myDeviceId, accessToken = "t")
        val environment = Environment.open(desktopDatabase(File(catalog, "tima.db")), SECRET, session.userId)
        val network = Network.create(session)
        val identity = deviceIdentityFrom(SECRET)
        return Receiver(
            environment = environment,
            network = network,
            session = session,
            identity = identity,
            keyOrchestrator = GroupKeyOrchestrator(environment, network, identity, msNow = { 0L }),
        )
    }

    @Test
    fun конверт_с_этого_же_устройства_считается_эхом() {
        assertTrue(receiver("d-моё").ownCopy("d-моё"))
    }

    /**
     * Копия с **другого своего** устройства — не эхо.
     *
     * Это настоящее сообщение: человек написал его с телефона и хочет видеть на ПК.
     * Показывать его надо своим, а не чужим, и это отдельная работа — привязка второго
     * устройства.
     */
    @Test
    fun конверт_с_другого_устройства_не_эхо() {
        assertFalse(receiver("d-моё").ownCopy("d-телефон"))
    }

    /** Неразобранный конверт отправителя не назвал — и эхом считаться не может. */
    @Test
    fun конверт_без_отправителя_не_эхо() {
        assertFalse(receiver("d-моё").ownCopy(null))
    }

    private companion object {
        val SECRET: ByteArray = ByteArray(32) { (it + 5).toByte() }
    }

    @Test
    fun причина_недоступного_пишется_в_журнал_один_раз() {
        // 2026-10-06: «Сообщение недоступно» на ПК заказчика разобрать было нечем — причина
        // лежала только в базе устройства.
        val diary = io.tima.core.diag.Diary(now = { 0L })
        io.tima.core.diag.Journal.replace(diary)
        val r = receiver("d-я")
        val entry = io.tima.core.outbox.IncomingEntry("c-1", 7, ByteArray(1))
        val noKey = io.tima.core.outbox.OpenOutcome.NoKey("ключ подписи отправителя не получен")
        r.noted(entry, noKey)
        r.noted(entry, noKey)
        r.noted(io.tima.core.outbox.IncomingEntry("c-1", 8, ByteArray(1)), io.tima.core.outbox.OpenOutcome.Rejected("подпись не сошлась"))

        val lines = diary.tail().filter { it.code == io.tima.core.diag.LogCode.MSG_UNREADABLE }
        kotlin.test.assertEquals(2, lines.size, "повтор той же причины записан снова")
        kotlin.test.assertEquals(io.tima.core.diag.Level.Info, lines[0].level)
        assertTrue(lines[0].details.contains("причина" to "ключ подписи отправителя не получен"))
        kotlin.test.assertEquals(io.tima.core.diag.Level.Trouble, lines[1].level, "непрошедшая проверка — беда")
    }
}
