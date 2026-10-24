package io.tima.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Окно вставляет и отправляет один раз** (заказчик 2026-10-24, «а»; беда
 * `doc_mig/БЕДЫ/2026-10-24-двойное-нажатие-копия.md`).
 *
 * Нажатие закрывает окно, но с экрана оно уходит на следующей отрисовке: на притормозившем телефоне
 * нажатия до неё вставляли второй виджет или отправляли второе сообщение. Защита — `rememberOnePress`
 * (`core-ui`). Этот тест сторожит, чтобы новые окна её не потеряли.
 *
 * Окно узнаётся по имени функции — `…Sheet` или `…Ask` (так в проекте называют подокна и вопросы), кнопка —
 * по имени действия в параметрах: `onDone`, `onInsert`, `onSend`, `onSave`, `onPick`, `onDelete`. Такая
 * функция обязана звать `rememberOnePress`. Нет — сборка падает и называет файл и функцию.
 */
class SheetOnePressTest {

    private val client = File(requireNotNull(System.getProperty("client.root")) {
        "не передан client.root — смотри build.gradle.kts этого модуля"
    })

    private val window = Regex("""@(?:[\w.]+\.)?Composable\s+(?:(?:private|internal|public)\s+)?fun\s+(\w+(?:Sheet|Ask))\s*\(""")
    private val action = Regex("""\b(onDone|onInsert|onSend|onSave|onPick|onDelete)\s*:""")

    @Test
    fun окно_с_вставкой_зовёт_одно_нажатие() {
        val code = client.walkTopDown()
            .onEnter {
                it.name != "build" && it.name != ".gradle" && it.name != "architecture-tests" &&
                    it.name != "test" && !it.name.endsWith("Test")
            }
            .filter { it.isFile && it.extension == "kt" }
        val missing = code.flatMap { file ->
            val text = file.readText()
            window.findAll(text).mapNotNull { m ->
                // Параметры — до парной скобки.
                var i = m.range.last + 1
                var depth = 1
                while (depth > 0 && i < text.length) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                    i++
                }
                val params = text.substring(m.range.last + 1, i)
                if (!action.containsMatchIn(params)) return@mapNotNull null
                val end = text.indexOf("\n}\n", i).let { if (it < 0) text.length else it }
                if ("rememberOnePress(" in text.substring(i, end)) null
                else "${file.relativeTo(client).invariantSeparatorsPath} — ${m.groupValues[1]}"
            }
        }.toList()
        assertTrue(
            missing.isEmpty(),
            "Окно вставляет или отправляет без защиты от двойного нажатия:\n" + missing.joinToString("\n") { "  $it" } +
                "\n\nОберните кнопку действия: `val once = rememberOnePress(\"метка\")`, `onClick = { once.press { … } }` " +
                "(core-ui, OnePress.kt).",
        )
    }
}
