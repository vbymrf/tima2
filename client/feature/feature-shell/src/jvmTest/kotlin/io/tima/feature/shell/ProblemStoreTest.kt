package io.tima.feature.shell

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Экран «Сообщить о проблеме» (ПЛАН-ОТЛАДКИ.md, Б2 и Б3).
 *
 * Проверяется не рисование, а обещания, которые экран даёт человеку: показываем то же, что
 * отправляем; журнал снимаем в момент прихода; пустой отчёт не уходит.
 */
class ProblemStoreTest {

    private class Log(var text: String) : ProblemLog {
        override fun dump(): String = text
    }

    private class Sender(val outcome: SendOutcome = SendOutcome.Sent("A7K3")) : ProblemSender {
        var seen: ProblemReport? = null

        override suspend fun send(report: ProblemReport): SendOutcome {
            seen = report
            return outcome
        }
    }

    private val facts = ProblemFacts(
        platform = "android",
        model = "realme RMX3269",
        os = "Android 11",
        build = "2.0.4-dev (4)",
        stream = "v2",
        nickname = "ivan",
    )

    private fun store(
        scope: kotlinx.coroutines.CoroutineScope,
        log: ProblemLog = Log("11:00 [сеть] GET /api/v1/chats → 200"),
        sender: ProblemSender = Sender(),
        origin: Origin? = Origin(Window.Phone, "Чаты"),
    ) = ProblemStore(log = log, sender = sender, scope = scope, origin = origin, facts = facts)

    @Test
    fun откуда_пришли_сказано_человеку() {
        val state = ProblemState(origin = Origin(Window.Phone, "Чаты"))

        assertEquals("Вы пришли из окна «Телефон», вкладка «Чаты»", state.origin?.words())
        assertEquals("Телефон · Чаты", state.origin?.short())
    }

    @Test
    fun окно_без_вкладки_не_выдумывает_её() {
        // Вкладки есть не у всех окон. Пустая строка в отчёте лучше выдуманной «Главная».
        val origin = Origin(Window.Media)

        assertEquals("Вы пришли из окна «${Window.Media.short}»", origin.words())
        assertFalse(origin.words().contains("вкладка"))
    }

    @Test
    fun журнал_снимается_при_открытии_экрана() = runTest {
        // Пока человек пишет текст, приложение живёт и дописывает строки. Снимок в момент
        // отправки показал бы не то, на что человек жаловался.
        val log = Log("то, что было при открытии")
        val store = store(backgroundScope, log = log)
        log.text = "то, что случилось потом"

        assertEquals("то, что было при открытии", store.state.value.log)
    }

    @Test
    fun отправляется_ровно_то_что_показано() = runTest {
        val sender = Sender()
        val store = store(backgroundScope, sender = sender)
        store.changedText("не уходят сообщения")
        store.chose(ProblemKind.Messages)
        store.send()
        store.state.first { it.outcome != null }

        val sent = requireNotNull(sender.seen)
        assertEquals("не уходят сообщения", sent.text)
        assertEquals(ProblemKind.Messages, sent.kind)
        assertEquals("Телефон · Чаты", sent.origin)
        assertEquals(store.state.value.log, sent.log, "показали одно, отправили другое")
        assertEquals("realme RMX3269", sent.facts.model)
    }

    @Test
    fun пустой_отчёт_не_уходит() = runTest {
        // Журнал покажет, что происходило, но не то, чего человек ждал. Отчёт без слов
        // человека — это загадка, а не жалоба.
        val sender = Sender()
        val store = store(backgroundScope, sender = sender)
        store.send()

        assertEquals(null, sender.seen)
        assertFalse(store.state.value.canSend)
    }

    @Test
    fun номер_обращения_показывается() = runTest {
        val store = store(backgroundScope, sender = Sender(SendOutcome.Sent("A7K3")))
        store.changedText("что-то не так")
        store.send()
        val state = store.state.first { it.outcome != null }

        assertEquals(SendOutcome.Sent("A7K3"), state.outcome)
    }

    @Test
    fun без_связи_отчёт_не_теряется() = runTest {
        // Жалуются на обрыв связи при обрыве связи. «Попробуйте позже» здесь означает
        // «мы вас не услышим».
        val store = store(backgroundScope, sender = Sender(SendOutcome.Queued))
        store.changedText("нет сети")
        store.send()
        val state = store.state.first { it.outcome != null }

        assertEquals(SendOutcome.Queued, state.outcome)
    }

    @Test
    fun что_приложится_видно_целиком() = runTest {
        val store = store(backgroundScope)
        assertFalse(store.state.value.showing)

        store.toggleShowing()

        assertTrue(store.state.value.showing)
        val lines = store.state.value.facts.lines()
        assertContains(lines.joinToString("\n"), "2.0.4-dev (4)")
        assertContains(lines.joinToString("\n"), "realme RMX3269")
        // Номера телефона в отчёте нет: он есть у сервера по userId (решение заказчика).
        assertFalse(lines.any { it.contains("+7") })
    }
}
