package io.tima.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Кириллицы нет в именах файлов кода.** Правило заказчика от 2026-08-25.
 *
 * **Документация под правило не подпадает** — отдельное решение того же дня: её читает
 * человек и ищет по названию. Поэтому из счёта исключены `.md` и `.docx` где угодно,
 * макет `doc/Layout-UI-*` и папки документации целиком.
 *
 * Проверяется бюджетом, а не запретом: файлов кода с такими именами сегодня 1, и
 * запрет упал бы в первую же секунду. Бюджет решает ту же задачу иначе — **новых
 * появиться не может, а старые обязаны уходить**.
 *
 * Тест падает в обе стороны:
 *
 * - стало больше — кто-то завёл новый файл с кириллицей в имени;
 * - стало меньше, а бюджет прежний — партия переименования прошла, но число не
 *   понижено тем же коммитом, и бюджет перестал что-либо значить.
 *
 * Порядок и партии переименования — `doc_mig/plan-latin-names.md`.
 */
class LatinTest {

    private val client = File(requireNotNull(System.getProperty("client.root")) {
        "не передан client.root — смотри build.gradle.kts этого модуля"
    })

    /** Корень репозитория: клиент лежит в нём соседом server, doc и doc_mig. */
    private val root = client.parentFile

    @Test
    fun имён_с_кириллицей_не_прибавляется() {
        val found = withCyrillic(root)

        assertTrue(
            found.size <= BUDGET,
            "Файлов и папок с кириллицей в имени стало ${found.size} при бюджете $BUDGET.\n" +
                "Новые:\n" + found.takeLast(NAME).joinToString("\n") { "  $it" } +
                "\n\nИмена — латиницей и по смыслу (CLAUDE.md, «Соглашения репозитория»). " +
                "Русский остаётся в комментариях, тексте и надписях на экране.",
        )

        assertTrue(
            found.size >= BUDGET,
            "Стало ${found.size} при бюджете $BUDGET — понизьте бюджет тем же коммитом, " +
                "которым прошла партия переименования. Бюджет, который живёт дольше долга, " +
                "врёт о состоянии проекта так же, как отсутствующий.",
        )
    }

    private fun withCyrillic(root: File): List<String> = root.walkTopDown()
        .onEnter { it.name !in SKIP }
        .filter { it.isFile && CYRILLIC.containsMatchIn(it.name) }
        .map { it.relativeTo(root).path.replace('\\', '/') }
        .filter { thisValue -> DOCUMENTATION.none { thisValue.startsWith(it) } }
        .filter { it.substringAfterLast('.') !in DOCUMENTS }
        .sorted()
        .toList()

    private companion object {
        val CYRILLIC = Regex("[А-Яа-яЁё]")

        /**
         * Бюджет на 2026-08-25 — 1: запускалка `.bat` в корне. Партия 3 (84 файла
         * `.kt`) прошла, и число понижено тем же коммитом.
         *
         * Считается по рабочему дереву, а не по индексу: файл, лежащий рядом и не
         * добавленный в git, ломает те же инструменты.
         */
        const val BUDGET = 1

        /** Сколько имён назвать в тексте падения: весь список читать никто не станет. */
        const val NAME = 15

        /**
         * Чего не смотрим: не наше, сборочное или заведомо временное.
         *
         * `doc_add` в `.gitignore` — там черновики, и на вторую машину они не поедут;
         * требовать от них латиницы значит требовать от заметок.
         */
        val SKIP = setOf(
            ".git", ".gradle", ".kotlin", ".idea", "build", "node_modules",
            "doc_add", "doc_arh", "third-party",
        )

        /** Расширения документов: их имена — дело человека, а не инструментов. */
        val DOCUMENTS = setOf("md", "docx")

        /**
         * Каталоги документации целиком, включая макет.
         *
         * Макет — это `.html` и `.css`, то есть формально не документ; но читает его
         * человек, и страницы он открывает по именам окон. Под тем же решением.
         */
        val DOCUMENTATION = listOf("doc/", "doc_mig/", "ДОКУМЕНТАЦИЯ/")
    }
}
