package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Полосы доставки и подсказки — П3…П5.
 *
 * Ошибка здесь не видна ни в сборке, ни на экране: телефон просто перестаёт что-то
 * получать. Ровно так двое суток молчал realme.
 */
class EventStreamProtocolLanesTest {

    private val protocol = EventStreamProtocol()

    @Test
    fun приветствие_приносит_вершины_полос() {
        val decision = protocol.decide(
            """{"event":"ok","device_id":"d1","pts":7,"qts":2,"seq":0}""",
        )
        val ready = assertIs<EventStreamProtocol.Decision.Ready>(decision)
        assertEquals(LaneTops(pts = 7, qts = 2, seq = 0), ready.lanes)
    }

    @Test
    fun подсказка_не_подтверждается_и_не_отбирается_по_курсору() {
        // `event_id` в подсказке — номер события НА СЕРВЕРЕ, а не наш. Пройди она отбор
        // «уже видели», клиент перестал бы догонять ровно тогда, когда отстал.
        val decision = protocol.decide(
            """{"event":"sync.poke","event_id":5,"pts":3,"qts":0,"seq":0}""",
            last = 100,
        )
        val poke = assertIs<EventStreamProtocol.Decision.Poke>(decision)
        assertEquals(5, poke.eventId)
        assertEquals(3, poke.lanes.pts)
    }

    @Test
    fun подсказка_про_звонок_несёт_только_идентификатор() {
        val decision = protocol.decide("""{"event":"call.poke","call_id":"c-1"}""")
        assertEquals(EventStreamProtocol.Decision.CallPoke("c-1"), decision)
    }

    @Test
    fun разрыв_называется_полосой_а_не_числом() {
        assertEquals("ключи", LaneTops.name(LaneTops.LANE_QTS))
        assertEquals("переписка", LaneTops.name(LaneTops.LANE_PTS))
        assertEquals("фон", LaneTops.name(LaneTops.LANE_SEQ))
        // Незнакомая полоса не должна называться чужим именем: «вне полос» означает
        // ровно то, что мы про неё ничего не обещаем.
        assertEquals("вне полос", LaneTops.name(99))
    }

    @Test
    fun кадр_без_номера_не_проверяется() {
        // Два случая на одно правило: кадр звонка номера не имеет по устройству, а
        // запись, сделанная до перехода, — по возрасту. Клиент такой просто применяет.
        assertEquals(null, EventStreamProtocol.laneMark("""{"event":"call.state","event_id":3}"""))
        assertEquals(null, EventStreamProtocol.laneMark("""{"event":"message.new","lane":0,"lane_seq":0}"""))
        assertEquals(
            LaneMark(1, 4),
            EventStreamProtocol.laneMark("""{"event":"message.new","lane":1,"lane_seq":4}"""),
        )
    }

    @Test
    fun номер_полосы_не_едет_назад() {
        // Кадры приходят и догоном, и живым каналом; повтор старого не должен опускать
        // отметку — иначе клиент объявил бы разрыв на ровном месте.
        val marks = LaneTops().with(LaneTops.LANE_PTS, 5).with(LaneTops.LANE_PTS, 2)
        assertEquals(5, marks.pts)
        assertEquals(5, marks.of(LaneTops.LANE_PTS))
        assertEquals(0, marks.of(LaneTops.LANE_QTS))
    }

    @Test
    fun сброс_полос_приходит_вместе_с_разрывом_истории() {
        // Номера остались от журнала, которого больше нет. Не сбросить — и клиент
        // навсегда считает, что у него разрыв, то есть зовёт догон на каждую подсказку.
        val decision = protocol.decide("""{"event":"sync.gap","next_cursor":900,"pts":11,"qts":4,"seq":1}""")
        val gap = assertIs<EventStreamProtocol.Decision.NeedHistory>(decision)
        assertEquals(900, gap.fromCursor)
        assertEquals(LaneTops(11, 4, 1), gap.lanes)
    }

    @Test
    fun сборка_называет_себя_в_первом_кадре() {
        val frame = protocol.authFrame("ключ", appCode = 42, stream = "v2")
        assertTrue(frame.contains("\"app\":42"), frame)
        assertTrue(frame.contains("\"stream\":\"v2\""), frame)
        // Сборка, которая себя не называет, шлёт кадр как раньше: сервер постарше про
        // эти поля не знает, а сервер поновее обязан такую пускать.
        assertEquals("""{"token":"ключ"}""", protocol.authFrame("ключ"))
    }

    @Test
    fun сервер_говорит_что_приложение_устарело() {
        val decision = protocol.decide(
            """{"event":"app.outdated","min_client":40,"version_name":"2.0.1"}""",
        )
        val outdated = assertIs<EventStreamProtocol.Decision.AppOutdated>(decision)
        assertEquals(40, outdated.minClient)
        assertEquals("2.0.1", outdated.versionName)
    }
}
