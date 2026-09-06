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
        var askedFor: Int = 0

        override fun dump(days: Int): String {
            askedFor = days
            return text
        }
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

    // ── Что видно про отправку (решение заказчика 2026-09-06) ─────────────────

    @Test
    fun пустому_отчёту_сказано_чего_не_хватает() {
        // Кнопка, которая просто не работает, читается как поломка приложения.
        val store = store(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        val state = store.state.value

        assertFalse(state.canSend)
        assertEquals("Напишите, что случилось — без этого отчёт не отправить.", state.missing)
    }

    @Test
    fun с_текстом_претензий_нет() {
        val store = store(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        store.changedText("не уходят сообщения")

        assertTrue(store.state.value.canSend)
        assertEquals(null, store.state.value.missing)
    }

    @Test
    fun отправленный_отчёт_остаётся_отправленным() = runTest {
        // 2026-09-06 в базу легли два одинаковых отчёта с одного телефона — K2PD и NAWR,
        // оба по 78 697 знаков: человек не понял, ушло ли, и нажал ещё раз.
        val store = store(backgroundScope, sender = Sender(SendOutcome.Sent("A7K3")))
        store.changedText("что-то не так")
        store.send()
        store.state.first { it.outcome != null }

        assertTrue(store.state.value.delivered)
        assertFalse(store.state.value.canSend, "второй отчёт уходит только после повторного входа")

        // Правка текста исход НЕ стирает: иначе исчезнет единственный признак отправки.
        store.changedText("что-то не так, дописал")
        assertTrue(store.state.value.delivered)
        assertFalse(store.state.value.canSend)
    }

    @Test
    fun отчёт_в_очереди_тоже_считается_доставленным() = runTest {
        // Для человека разницы нет: жалоба принята и уйдёт сама. Повторять незачем.
        val store = store(backgroundScope, sender = Sender(SendOutcome.Queued))
        store.changedText("нет сети")
        store.send()
        store.state.first { it.outcome != null }

        assertTrue(store.state.value.delivered)
    }

    @Test
    fun отказ_сервера_позволяет_повторить() = runTest {
        // Отказ — не доставка: здесь повтор как раз имеет смысл.
        val store = store(backgroundScope, sender = Sender(SendOutcome.Refused("сервер не принял")))
        store.changedText("что-то не так")
        store.send()
        store.state.first { it.outcome != null }

        assertFalse(store.state.value.delivered)
        assertTrue(store.state.value.canSend)
    }

    @Test
    fun глубина_журнала_идёт_от_ответа_человека() {
        // «Отправить журнал за семь дней» — вопрос к инженеру; «когда началось» знает
        // любой. Срок выводим сами (решение заказчика 2026-09-06).
        val log = Log("строка")
        val store = ProblemStore(
            log = log,
            sender = Sender(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            origin = null,
            facts = facts,
        )
        assertEquals(1, log.askedFor, "по умолчанию сутки")

        store.chose(Began.Week)
        assertEquals(7, log.askedFor)
        assertEquals(Began.Week, store.state.value.began)

        store.chose(Began.Earlier)
        assertTrue(log.askedFor > 30, "«раньше» — это всё, что сохранилось")
    }

    @Test
    fun сказано_сколько_уйдёт() {
        // До 2026-09-06 нигде не было сказано даже того, что журнал берётся за сутки.
        val store = ProblemStore(
            log = Log(listOf("одна", "две", "три").joinToString("\n")),
            sender = Sender(),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            origin = null,
            facts = facts,
        )

        val said = store.state.value.attachment()
        assertContains(said, "за сутки")
        assertContains(said, "3 строк")
    }
}
