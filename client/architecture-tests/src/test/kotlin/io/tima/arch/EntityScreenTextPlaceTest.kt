package io.tima.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Окно сущности задаёт группу размера** «Шрифтов и размеров» (заказчик 2026-11-23: «проверь, привязаны ли настройки
 * Шрифты и размеры у сущности. Все ли привязаны»).
 *
 * Страница сущности, её вкладки и подокна управления рисуются поверх приложения, вне каркаса окна, и «Списки» от
 * него не наследуют. До 2026-11-23 из всех них размер менялся только у шапки (она ставит «Шапки» сама) и у пузырей
 * ленты; остальное — «О сущности», участники, подписчики, «Управление», «Настройки», «Содержимое» — стояло на размере
 * по умолчанию при любом выборе.
 *
 * Окно узнаётся по шапке подокна `SubwindowHeader(`. В той же функции **до** шапки должна стоять `ProvidePlace(…)`:
 * списки и страницы — «Списки», формы и разделы управления — «Меню и настройки», сообщения — «Сообщения».
 */
class EntityScreenTextPlaceTest {

    private val client = File(requireNotNull(System.getProperty("client.root")) {
        "не передан client.root — смотри build.gradle.kts этого модуля"
    })

    /** Окна сущности: весь модуль групп и «О сущности», который живёт в модуле переписки. */
    private val places = listOf(
        "feature/feature-group/src/commonMain",
        "feature/feature-chat/src/commonMain/kotlin/io/tima/feature/chat/ShowcaseScreen.kt",
    )

    private val header = Regex("""SubwindowHeader\(""")

    /**
     * Начало функции верхнего уровня — от него до шапки ищется группа. Только с начала строки: вложенная `fun label(…)`
     * внутри окна стоит после `ProvidePlace(` и иначе заслоняла бы её.
     */
    private val function = Regex("""^(?:(?:private|internal|public)\s+)?fun\s""", RegexOption.MULTILINE)

    @Test
    fun окно_сущности_задаёт_группу_размера() {
        val files = places.map { File(client, it) }.onEach { assertTrue(it.exists(), "нет $it") }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(files.size > 10, "окна сущности не нашлись: ${files.size}")
        val missing = files.flatMap { file ->
            val text = file.readText()
            header.findAll(text).mapNotNull { hit ->
                val start = function.findAll(text).lastOrNull { it.range.first < hit.range.first }?.range?.first ?: 0
                if ("ProvidePlace(" in text.substring(start, hit.range.first)) {
                    null
                } else {
                    "${file.relativeTo(client).invariantSeparatorsPath}:${text.substring(0, hit.range.first).count { it == '\n' } + 1}"
                }
            }.toList()
        }
        assertTrue(
            missing.isEmpty(),
            "Окно сущности без группы размера «Шрифтов и размеров» — не вырастет ни от одной настройки:\n" +
                missing.joinToString("\n") { "  $it" } +
                "\n\nОберните окно в ProvidePlace(TextPlace.LISTS) или ProvidePlace(TextPlace.MENU) до SubwindowHeader " +
                "(doc_mig/ПЛАН-(Ш)-ШРИФТОВ.md, Ш8).",
        )
    }
}
