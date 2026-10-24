package io.tima.shared

import io.tima.core.encryption.TextBodyCodec
import io.tima.core.encryption.TypedOverQuickMarkdown
import io.tima.core.ui.HelpFiles
import io.tima.crypto.MarkupRules
import io.tima.crypto.QuickMarkdown
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageDisplay
import io.tima.feature.chat.ChatScreen
import io.tima.feature.chat.ChatState
import io.tima.testui.capture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * «Частые вопросы» (заказчик 2026-10-24): снимки экранов рисует этот тест — они не устаревают, когда меняется
 * вид (2а#); страницы — нашим быстрым markdown, и каждая обязана разобраться без отката в простой текст.
 */
class HelpTest {

    /** Ресурсы справки в `core-ui` — тест идёт из каталога модуля `shared`. */
    private val help = File(File("").absoluteFile.parentFile, "core/core-ui/src/commonMain/composeResources/files/help")

    /** Пример для снимков: заголовок, жирный, зачёркнутый — знаки серым в поле, у получателя стиль. */
    private val sample = "# Сбор\nв **9:00** у входа, ~~10:00~~ отменили"

    @Test
    fun снимки_нарисованы_и_страницы_разбираются() {
        shots()
        val missing = mutableListOf<String>()
        val bad = mutableListOf<String>()
        for ((section, _) in HelpFiles.list(File(help, "sections.txt").readText())) {
            for ((tab, _) in HelpFiles.list(File(help, "$section/tabs.txt").readText())) {
                val file = File(help, "$section/$tab.md")
                if (!file.exists()) {
                    missing += "$section/$tab.md"
                    continue
                }
                val pages = HelpFiles.pages(file.readText())
                if (pages.isEmpty()) bad += "$section/$tab.md: ни одной страницы (строки «@@ Название»)"
                for (page in pages) for (part in page.parts) when (part) {
                    is HelpFiles.Part.Text -> {
                        val parsed = QuickMarkdown.parse(part.markdown)
                        val problems = MarkupRules.check(parsed, strict = true)
                        if (problems.isNotEmpty()) bad += "$section/$tab.md «${page.title}»: $problems"
                    }
                    is HelpFiles.Part.Shot -> for (theme in listOf("light", "dark")) {
                        if (!File(help, "img/${part.name}-$theme.png").exists()) missing += "img/${part.name}-$theme.png"
                    }
                }
            }
        }
        // Приложение читает справку из ресурсов — тем же путём, что на телефоне.
        val read = kotlinx.coroutines.runBlocking { HelpFiles.text("sections.txt") }
        assertTrue(read != null && HelpFiles.list(read).isNotEmpty(), "справка не читается из ресурсов")
        assertTrue(missing.isEmpty(), "нет файлов справки: $missing")
        assertTrue(bad.isEmpty(), "страницы справки ушли бы простым текстом:\n" + bad.joinToString("\n"))
    }

    /** Снимки — в ресурсы справки, обе темы. */
    private fun shots() {
        val img = File(help, "img").apply { mkdirs() }
        val rich = TextBodyCodec.decodeLine(TextBodyCodec.encodeTyped(sample))?.rich
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            capture("help-input", WIDTH, 260, dark) {
                ChatScreen(source = TypedOverQuickMarkdown, state = ChatState(draft = sample), peer = PEER, onSet = {}, onSend = {}, onBack = {})
            }
            File("build/снимки/help-input-$theme.png").copyTo(File(img, "help-input-$theme.png"), overwrite = true)
            val line = ChatLine(
                dedupKey = "help-1", chatId = "help", display = MessageDisplay.RECEIVED, text = sample,
                outgoing = false, atMs = 1_700_000_000_000, localId = 1, rich = rich,
            )
            capture("help-result", WIDTH, 300, dark) {
                ChatScreen(state = ChatState(lines = listOf(line)), peer = PEER, onSet = {}, onSend = {}, onBack = {})
            }
            File("build/снимки/help-result-$theme.png").copyTo(File(img, "help-result-$theme.png"), overwrite = true)
        }
    }

    private companion object {
        const val WIDTH = 380
        const val PEER = "Аня"
    }
}
