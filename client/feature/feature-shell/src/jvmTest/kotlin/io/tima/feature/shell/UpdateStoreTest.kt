package io.tima.feature.shell

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Вкладка «Обновление»: что происходит между нажатием и установкой
 * (ПЛАН-ОБНОВЛЕНИЯ.md, О3).
 *
 * Главное здесь — **между «Обновить» и загрузкой стоит вопрос**. Установка на ПК
 * закрывает приложение, и человек обязан узнать это до, а не в тот момент, когда окно
 * исчезло посреди разговора (решение заказчика 2026-09-06).
 */
class UpdateStoreTest {

    private val offer = UpdateOffer(
        versionCode = 7,
        versionName = "2.0.7-dev",
        url = "https://api.example.com/download/TIMA-7.msi",
        notes = "",
        stream = "v2",
        sha256 = "abc",
        size = 82_001_783,
    )

    private class Fake(val outcome: InstallOutcome = InstallOutcome.Started) : UpdateInstaller {
        var calls = 0
        var seen: UpdateOffer? = null

        override suspend fun install(offer: UpdateOffer, onProgress: (Int) -> Unit): InstallOutcome {
            calls++
            seen = offer
            onProgress(50)
            return outcome
        }
    }

    private fun store(
        scope: kotlinx.coroutines.CoroutineScope,
        installer: UpdateInstaller? = Fake(),
        onLeaving: () -> Unit = {},
        offered: UpdateOffer? = offer,
        installedCode: Int = 2,
        memory: UpdateMemory = UpdateMemory.Forgetful,
    ) = UpdateStore(
        versions = { offered },
        scope = scope,
        installed = "2.0." + installedCode + "-dev (" + installedCode + ")",
        installedCode = installedCode,
        stream = "v2",
        installer = installer,
        onLeaving = onLeaving,
        memory = memory,
    )

    /** Память на одной строке: ровно то, что делает платформа с файлом или настройками. */
    private fun memory(held: Array<String?>) =
        UpdateMemory(load = { held[0] }, save = { held[0] = it })

    @Test
    fun нажатие_обновить_не_начинает_загрузку() = runTest {
        val installer = Fake()
        val store = store(backgroundScope, installer)
        store.state.first { it.offer != null }

        store.ask()

        assertTrue(store.state.value.asking, "человеку не показали, чем это кончится")
        assertFalse(store.state.value.installing)
        assertEquals(0, installer.calls, "загрузка началась до подтверждения")
    }

    @Test
    fun подтверждение_ставит_и_закрывает_приложение() = runTest {
        val installer = Fake()
        var закрылись = false
        val store = store(backgroundScope, installer, onLeaving = { закрылись = true })
        store.state.first { it.offer != null }

        store.ask()
        store.install()
        store.state.first { it.outcome != null }

        assertEquals(1, installer.calls)
        assertEquals(offer, installer.seen, "установщику отдали не то предложение")
        assertTrue(закрылись, "MSI не заменит файлы работающей программы — окно обязано закрыться")
        assertFalse(store.state.value.asking)
    }

    @Test
    fun неудача_оставляет_приложение_открытым() = runTest {
        val installer = Fake(InstallOutcome.BadPackage)
        var закрылись = false
        val store = store(backgroundScope, installer, onLeaving = { закрылись = true })
        store.state.first { it.offer != null }

        store.ask()
        store.install()
        val state = store.state.first { it.outcome != null }

        assertEquals(InstallOutcome.BadPackage, state.outcome)
        assertFalse(state.installing, "экран остался бы в «скачиваем» навсегда")
        assertFalse(закрылись, "закрывать приложение после несостоявшейся установки незачем")
    }

    @Test
    fun без_установщика_ставить_нечем() = runTest {
        val store = store(backgroundScope, installer = null)
        store.state.first { it.offer != null }

        assertFalse(store.canInstall, "кнопки установки быть не должно")
        store.ask()
        store.install()

        // Ни исхода, ни загрузки: нечему было начаться.
        assertEquals(null, store.state.value.outcome)
        assertFalse(store.state.value.installing)
    }

    @Test
    fun порог_совместимости_блокирует_работу() = runTest {
        val store = store(backgroundScope, offered = offer.copy(minClient = 5))
        val state = store.state.first { it.offer != null }

        // Установлена 2, порог 5, поток тот же — работать нельзя.
        assertTrue(state.mustUpdate)
    }

    @Test
    fun без_порога_ничего_не_блокируется() = runTest {
        // Отсутствие поля означает «гейта нет», а не «блокировать»: иначе откат сервера
        // на прежнюю версию выключил бы все установленные приложения разом.
        val store = store(backgroundScope, offered = offer.copy(minClient = 0))
        val state = store.state.first { it.offer != null }

        assertFalse(state.mustUpdate)
    }

    @Test
    fun порог_чужого_потока_не_наш() = runTest {
        val store = store(backgroundScope, offered = offer.copy(stream = "v1", minClient = 30))
        val state = store.state.first { it.offer != null }

        // `min_client` соседнего ряда сборок к нам не относится: приняв его, мы выключили
        // бы приложение по числу из чужой нумерации.
        assertFalse(state.mustUpdate)
    }

    @Test
    fun чужой_поток_не_предлагается() = runTest {
        val store = store(backgroundScope, offered = offer.copy(stream = "v1", versionCode = 24))
        val state = store.state.first { it.offer != null }

        // Номера версий сравнимы только внутри потока: 24 больше 2, но это прошлогодняя v1.
        assertFalse(state.updateAvailable)
        assertTrue(state.alienStream, "человеку надо сказать, что предложение чужое")
    }

    // ── Подокно при запуске (решение заказчика 2026-09-06) ────────────────────

    @Test
    fun начатая_установка_запоминается() = runTest {
        val held = arrayOf<String?>(null)
        val store = store(backgroundScope, memory = memory(held))
        store.state.first { it.offer != null }

        store.ask()
        store.install()
        store.state.first { it.outcome != null }

        // Без этой записи успех и обрыв установки неотличимы: приложение закрылось, и
        // спросить у него потом, чем всё кончилось, будет некого.
        assertTrue(held[0].orEmpty().startsWith("7	"), "намерение не записано: ${held[0]}")
    }

    @Test
    fun после_успешной_установки_говорим_об_этом() = runTest {
        // Записано «пошёл ставить 7», стоит 7 — значит доехало.
        val held = arrayOf<String?>("7	2.0.7-dev	журнал переживает перезапуск")
        val store = store(backgroundScope, memory = memory(held), installedCode = 7)

        val news = store.state.value.news
        assertTrue(news is UpdateNews.Installed, "человеку не сказали, что обновление встало")
        assertEquals("журнал переживает перезапуск", (news as UpdateNews.Installed).notes)
        assertEquals("", held[0], "запись обязана стереться: успех говорится один раз")
    }

    @Test
    fun брошенная_установка_названа_словами() = runTest {
        // Записано «пошёл ставить 7», стоит по-прежнему 2 — человек начал и не довёл.
        val held = arrayOf<String?>("7	2.0.7-dev	")
        val store = store(backgroundScope, memory = memory(held), installedCode = 2)

        val news = store.state.value.news
        assertTrue(news is UpdateNews.Broken, "обрыв выглядел бы как молчание")
        assertEquals("2.0.7-dev", (news as UpdateNews.Broken).wanted)
    }

    @Test
    fun без_записи_окна_нет() = runTest {
        // Обычный запуск: говорить не о чем, и окно не поднимается.
        val store = store(backgroundScope, offered = null)
        store.state.first { !it.expect }

        assertEquals(null, store.state.value.news)
    }

    @Test
    fun важное_обновление_поднимает_окно() = runTest {
        val store = store(backgroundScope, offered = offer.copy(important = true))
        val news = store.state.first { it.news != null }.news

        assertTrue(news is UpdateNews.Important)
        assertTrue(store.state.value.important)
    }

    @Test
    fun обычное_обновление_окна_не_поднимает() = runTest {
        // Уровень 0 молчит: окно при каждом запуске ради необязательного обновления —
        // самый быстрый способ научить человека закрывать его не читая.
        val store = store(backgroundScope, offered = offer.copy(important = false))
        store.state.first { it.offer != null }

        assertEquals(null, store.state.value.news)
    }

    @Test
    fun важность_чужого_потока_не_наша() = runTest {
        val store = store(backgroundScope, offered = offer.copy(stream = "v1", important = true))
        store.state.first { it.offer != null }

        assertFalse(store.state.value.important, "важность соседнего ряда сборок к нам не относится")
        assertEquals(null, store.state.value.news)
    }

    @Test
    fun исход_установки_важнее_предложения() = runTest {
        // Человек уже что-то сделал, и сказать надо сначала про это. Предложение
        // поставить новое подождёт до следующего запуска.
        val held = arrayOf<String?>("7	2.0.7-dev	")
        val store = store(
            backgroundScope,
            offered = offer.copy(versionCode = 8, important = true),
            memory = memory(held),
            installedCode = 7,
        )
        store.state.first { it.offer != null }

        assertTrue(store.state.value.news is UpdateNews.Installed)
    }

    @Test
    fun окно_закрывается() = runTest {
        val held = arrayOf<String?>("7	2.0.7-dev	")
        val store = store(backgroundScope, memory = memory(held), installedCode = 7, offered = null)
        store.state.first { it.news != null }

        store.dismissNews()

        assertEquals(null, store.state.value.news, "окно обязано закрываться — даже важное")
    }
}
