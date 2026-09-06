package io.tima.shared

import io.tima.core.network.ProblemPost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Очередь неотправленных отчётов (ПЛАН-ОТЛАДКИ.md, Б3 и Б7).
 *
 * Очередь существует ровно потому, что на обрыв связи жалуются при обрыве связи. Всё
 * ниже — про то, чтобы жалоба дожила до появления сети и не размножилась по дороге.
 */
class ReportQueueTest {

    private fun store(): ReportsStore {
        var held: String? = null
        return ReportsStore(load = { held }, save = { held = it })
    }

    private fun post(text: String) = ProblemPost(
        kind = "messages",
        text = text,
        origin = "Телефон · Чаты",
        platform = "android",
        model = "realme",
        os = "Android 11",
        build = "2.0.4-dev (4)",
        stream = "v2",
        nickname = "ivan",
        log = "11:00 [сеть] POST /api/v1/messages → 503",
    )

    @Test
    fun отчёт_переживает_перезапуск() {
        // Ради этого очередь и заведена: приложение закрыли, сеть появилась завтра.
        val store = store()
        ReportQueue(store).add(post("не уходят сообщения"))

        val afterRestart = ReportQueue(store).waiting()
        assertEquals(1, afterRestart.size)
        assertEquals("не уходят сообщения", afterRestart[0].text)
    }

    @Test
    fun ушедшее_убирается_и_не_шлётся_дважды() {
        val store = store()
        val queue = ReportQueue(store)
        val one = post("первый")
        queue.add(one)
        queue.add(post("второй"))

        queue.forget(one)

        val left = queue.waiting()
        assertEquals(1, left.size)
        assertEquals("второй", left[0].text)
    }

    @Test
    fun телефон_не_склад_отчётов() {
        // Больше десяти неотправленных означает, что дело не в сети, а в сервере, и
        // копить их дальше незачем.
        val store = store()
        val queue = ReportQueue(store)
        repeat(25) { queue.add(post("отчёт $it")) }

        val kept = queue.waiting()
        assertEquals(10, kept.size)
        assertEquals("отчёт 24", kept.last().text, "последний обязан остаться")
    }

    @Test
    fun испорченная_очередь_не_мешает_новым() {
        // Отчёты не ценность сами по себе. Испорченный список, за который держатся,
        // заблокировал бы отправку всего последующего.
        var held: String? = "это не json"
        val store = ReportsStore(load = { held }, save = { held = it })
        val queue = ReportQueue(store)

        assertTrue(queue.waiting().isEmpty())
        queue.add(post("новый"))
        assertEquals(1, queue.waiting().size)
    }

    @Test
    fun хранилище_без_памяти_ничего_не_ломает() {
        // Платформа может не дать места (проверки, iOS до реализации). Очередь при этом
        // обязана вести себя тихо, а не падать при первом отчёте.
        val queue = ReportQueue(ReportsStore.Forgetful)
        queue.add(post("в никуда"))

        assertTrue(queue.waiting().isEmpty())
    }
}
