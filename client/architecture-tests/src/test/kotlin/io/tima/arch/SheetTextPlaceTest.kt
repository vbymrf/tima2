package io.tima.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Подокно задаёт группу размера** «Шрифтов и размеров» (заказчик 2026-10-24, 4б: «если мы что-то новое
 * добавим — как не забыть привязать размер?»).
 *
 * Содержимое вкладок получает «Списки» от каркаса окна само (`WindowFrame`). Подокна — нет: они рисуются
 * поверх всего приложения, вне каркаса, и наследовать им не от кого. Без своей группы подокно стоит на
 * размере по умолчанию при любом выборе в настройках — так было у меню сообщения и у десятка других.
 *
 * Подокно узнаётся по затемнению под ним — `text.copy(alpha = 0.45f)`. В той же функции **до** затемнения
 * должна стоять `ProvidePlace(…)`: обычно «Меню и настройки» на всё подокно, а заголовок внутри — «Шапки»,
 * строки сообщений — «Сообщения».
 */
class SheetTextPlaceTest {

    private val client = File(requireNotNull(System.getProperty("client.root")) {
        "не передан client.root — смотри build.gradle.kts этого модуля"
    })

    /** Затемнение под подокном. */
    private val dim = Regex("""text\.copy\(alpha = 0\.45f\)""")

    /** Начало функции — от него до затемнения ищется группа. */
    private val function = Regex("""^\s*(?:@\w+\s+)*(?:(?:private|internal|public)\s+)?fun\s""", RegexOption.MULTILINE)

    @Test
    fun подокно_задаёт_группу_размера() {
        val code = client.walkTopDown()
            .onEnter {
                it.name != "build" && it.name != ".gradle" && it.name != "architecture-tests" &&
                    it.name != "test" && !it.name.endsWith("Test")
            }
            .filter { it.isFile && it.extension == "kt" }
        val missing = code.flatMap { file ->
            val text = file.readText()
            dim.findAll(text).mapNotNull { hit ->
                val start = function.findAll(text).lastOrNull { it.range.first < hit.range.first }?.range?.first ?: 0
                if ("ProvidePlace(" in text.substring(start, hit.range.first)) {
                    null
                } else {
                    "${file.relativeTo(client).invariantSeparatorsPath}:${text.substring(0, hit.range.first).count { it == '\n' } + 1}"
                }
            }
        }.toList()
        assertTrue(
            missing.isEmpty(),
            "Подокно без группы размера «Шрифтов и размеров» — не вырастет ни от одной настройки:\n" +
                missing.joinToString("\n") { "  $it" } +
                "\n\nОберните подокно в ProvidePlace(TextPlace.MENU) до затемнения; заголовок — TextPlace.HEADERS, " +
                "строки сообщений — TextPlace.MESSAGES (doc_mig/ПЛАН-(Ш)-ШРИФТОВ.md, Ш7).",
        )
    }
}
