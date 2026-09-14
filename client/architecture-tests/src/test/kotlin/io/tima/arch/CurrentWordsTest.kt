package io.tima.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Глобальный словарь: один писатель и ни одного лишнего читателя** (ПЛАН-ЯЗЫКА, Я-D).
 *
 * `CurrentWords` — глобальное изменяемое состояние, и заведено оно по делу: store не
 * `@Composable`, `LocalWords` ему недоступен, а язык приложения один на процесс. Решение
 * заказчика 2026-09-08, вариант «г»: store получает **ссылку** `words: () -> Words`, и
 * умолчанием этой ссылки служит глобал.
 *
 * Опасность у такого решения одна и известна заранее: договорённость «читать только в
 * умолчании» живёт ровно до первого, кто её не прочёл. Здесь она перестаёт быть
 * договорённостью.
 */
class CurrentWordsTest {

    private val client = File(requireNotNull(System.getProperty("client.root")) {
        "не передан client.root — смотри build.gradle.kts этого модуля"
    })

    /**
     * **Пишет один Root, и только он.**
     *
     * `TimaTheme` зовут и с готовым набором цветов — ради предпросмотра оформления на
     * экране темы. Запись из темы сбрасывала бы язык на русский при каждом таком вызове,
     * и выглядело бы это как случайная поломка перевода.
     */
    @Test
    fun у_глобального_словаря_один_писатель() {
        val writers = production().filter { it.readText().contains("CurrentWords.value =") }

        assertEquals(
            listOf("Root.kt"),
            writers.map { it.name }.sorted(),
            "Писать в CurrentWords вправе только композиционный корень: он же отдаёт " +
                "язык в тему, из того же значения, и разойтись им негде.",
        )
    }

    /**
     * **Читается только в объявлении по умолчанию.**
     *
     * Прочитанный в месте применения, глобал делает язык непроверяемым: подменить словарь
     * снаружи уже нечем, и тест «беда говорит на текущем языке» для такого места написать
     * нельзя.
     *
     * Бюджет — **4**, и это долг, а не дно. Под ним:
     *
     * | Где | Что мешает |
     * |---|---|
     * | `Problem.kt` ×2 | одно из чтений — свойство `data class ProblemState`, и словарь туда не передан вовсе |
     * | `Update.kt` ×2 | `UpdateStore` не получил `words: () -> Words` вместе с остальными восемнадцатью |
     *
     * Гасится вместе с тем, как эти два store получат ссылку в конструкторе. Число
     * **только убывает** и обязано понижаться тем же коммитом.
     */
    @Test
    fun глобальный_словарь_читается_только_в_умолчании() {
        val loose = production().flatMap { file ->
            file.readLines()
                .filter { it.contains("CurrentWords.value") }
                .filterNot { it.contains("CurrentWords.value =") }
                .filterNot { it.trim() == DEFAULT }
                .map { "${file.name}: ${it.trim()}" }
        }

        assertTrue(
            loose.size <= BUDGET,
            "Прямых чтений CurrentWords стало ${loose.size} при бюджете $BUDGET:\n" +
                loose.joinToString("\n") { "  $it" } +
                "\n\nStore получает словарь ссылкой в конструкторе — `words: () -> Words` " +
                "(ПЛАН-ЯЗЫКА, Я2-беды). Прочитанный на месте, глобал делает язык " +
                "непроверяемым: подменить словарь снаружи нечем.",
        )
        assertTrue(
            loose.size >= BUDGET,
            "Стало ${loose.size} при бюджете $BUDGET — понизьте бюджет тем же коммитом, " +
                "которым погашен долг. Список, живущий дольше долга, врёт о состоянии " +
                "проекта так же, как отсутствующий.",
        )
    }

    private fun production(): List<File> = client.walkTopDown()
        .onEnter { it.name !in setOf("build", ".gradle", ".kotlin", ".git", "fixtures") }
        .filter { it.isFile && it.extension == "kt" }
        .filterNot { it.path.contains("Test") }
        // Само объявление глобала — не чтение и не запись.
        .filterNot { it.name == "Words.kt" }
        .toList()

    private companion object {
        /** Единственная законная строка чтения — умолчание ссылки в конструкторе store. */
        const val DEFAULT = "private val words: () -> Words = { CurrentWords.value },"

        /** Измерено 2026-09-14. Только убывает. */
        const val BUDGET = 4
    }
}
