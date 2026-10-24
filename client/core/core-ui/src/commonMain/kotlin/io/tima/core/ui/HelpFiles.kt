package io.tima.core.ui

import io.tima.core.ui.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * «Частые вопросы» (заказчик 2026-10-24, 1а#): страницы — файлы в ресурсах, набранные нашим же быстрым
 * markdown; показываются тем же видом, что сообщение. Только русский (3#).
 *
 * Раскладка `files/help/`:
 * - `sections.txt` — разделы: `ключ|Название` построчно;
 * - `<раздел>/tabs.txt` — вкладки раздела, так же;
 * - `<раздел>/<вкладка>.md` — страницы вкладки: каждая начинается строкой `@@ Название`; строка
 *   `[[shot имя]]` — снимок экрана `img/имя-light.png` или `-dark.png` по теме (снимки рисуют тесты, 2а#).
 */
object HelpFiles {

    /** Текст файла справки; нет или не прочёлся — `null`. */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun text(path: String): String? = runCatching { Res.readBytes("files/help/$path").decodeToString() }.getOrNull()

    /** Байты файла справки (снимок); нет — `null`. */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun bytes(path: String): ByteArray? = runCatching { Res.readBytes("files/help/$path") }.getOrNull()

    /** Список `ключ|Название` построчно; пустые строки и строки без черты пропускаются. */
    fun list(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
        val key = line.substringBefore('|', "").trim()
        val title = line.substringAfter('|', "").trim()
        if (key.isEmpty() || title.isEmpty()) null else key to title
    }

    /** Страница справки: название и части — текст markdown или снимок. */
    data class Page(val title: String, val parts: List<Part>)

    sealed interface Part {
        data class Text(val markdown: String) : Part
        data class Shot(val name: String) : Part
    }

    /** Страницы вкладки — по строкам `@@ Название`; снимок — строкой `[[shot имя]]`. */
    fun pages(text: String): List<Page> {
        val pages = mutableListOf<Page>()
        var title: String? = null
        val parts = mutableListOf<Part>()
        val buffer = StringBuilder()
        fun flushText() {
            val md = buffer.toString().trim('\n', ' ')
            if (md.isNotEmpty()) parts += Part.Text(md)
            buffer.clear()
        }
        fun flushPage() {
            flushText()
            title?.let { pages += Page(it, parts.toList()) }
            parts.clear()
        }
        for (line in text.replace("\r\n", "\n").lines()) {
            when {
                line.startsWith(PAGE) -> {
                    flushPage()
                    title = line.removePrefix(PAGE).trim()
                }
                SHOT.matches(line.trim()) -> {
                    flushText()
                    parts += Part.Shot(SHOT.find(line.trim())!!.groupValues[1])
                }
                else -> buffer.append(line).append('\n')
            }
        }
        flushPage()
        return pages
    }

    private const val PAGE = "@@ "
    private val SHOT = Regex("""^\[\[shot ([\w-]+)]]$""")
}
