package io.tima.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Модуль компилируется только тем, что объявил.**
 *
 * Правила импортов (`ArchitectureRules`) стерегут слои: domain не знает транспорта,
 * feature не знает базы. Но есть второй род поломки, которого они не видят вовсе:
 * модуль пользуется чужими типами, **не объявив зависимость**, и компилируется лишь
 * потому, что кто-то третий переэкспортировал их через `api`.
 *
 * Такое ребро называется теневым. Оно опасно тем, что не видно ни в одном файле
 * сборки: `core-network` работает с `domain-chat` через `api`-экспорт `core-outbox`,
 * и стоит `core-outbox` перевести свою зависимость в `implementation` — падает
 * `core-network`, который никто не трогал.
 *
 * Здесь ребро восстанавливается по импортам и сверяется с манифестом. Известные
 * долги перечислены явно и **обязаны исчезать вместе с починкой**: тест падает и
 * тогда, когда долг погашен, а запись о нём осталась. Иначе список сам становится
 * тем, что устаревает молча.
 */
class ЗависимостиTest {

    private val clientRoot = File(requireNotNull(System.getProperty("client.root")) {
        "не передан client.root — смотри build.gradle.kts этого модуля"
    })

    /**
     * Теневые рёбра, известные на момент шага 1 программы, и шаг, который их гасит.
     *
     * Запись читается как «модуль пользуется чужим, не объявив»: пока строка здесь,
     * тест это терпит. Когда зависимость объявлена — строку обязан убрать тот же
     * коммит, иначе тест упадёт на «долг погашен, а запись осталась».
     */
    private val допущенныеДолги = setOf(
        // Шаг 2 погасил пять рёбер: core-network -> domain-chat, три ребра shared
        // и test-harness -> messenger-crypto. Остался один долг — он умрёт не
        // объявлением зависимости, а её исчезновением.
        "shared -> messenger-crypto",    // гасится шагом 3: два импорта в Приёмник.kt
    )

    @Test
    fun импорты_совпадают_с_манифестами() {
        val модули = собратьМодули(clientRoot)
        assertTrue(модули.size >= 12, "модулей найдено ${модули.size} — карта собралась неверно")

        val найденные = mutableSetOf<String>()
        val объяснения = mutableMapOf<String, MutableSet<String>>()

        for (модуль in модули) {
            val объявлено = модуль.объявленныеЗависимости()
            for ((импорт, файл) in модуль.первыеСтороннИмпорты()) {
                val чей = модули.firstOrNull { it != модуль && импорт.startsWith(it.пакет + ".") }
                val имяЧужого = чей?.имя
                    ?: if (импорт.startsWith(ПАКЕТ_КРИПТО)) МОДУЛЬ_КРИПТО else null
                if (имяЧужого == null || имяЧужого in объявлено) continue
                val ребро = "${модуль.имя} -> $имяЧужого"
                найденные += ребро
                объяснения.getOrPut(ребро) { mutableSetOf() } += файл
            }
        }

        val новые = найденные - допущенныеДолги
        val погашенные = допущенныеДолги - найденные

        assertTrue(
            новые.isEmpty(),
            "Модуль пользуется тем, чего не объявил:\n" +
                новые.sorted().joinToString("\n") { "  $it — например ${объяснения[it]?.first()}" } +
                "\n\nОбъявите зависимость в build.gradle.kts. Компилируется оно только " +
                "потому, что кто-то третий переэкспортировал типы через api: уберут " +
                "переэкспорт — упадёт модуль, который никто не трогал.",
        )

        assertEquals(
            emptySet(),
            погашенные,
            "Долг погашен, а запись о нём осталась в допущенныеДолги. Уберите строку " +
                "тем же коммитом: список, который живёт дольше долга, врёт о состоянии " +
                "проекта так же, как отсутствующий.",
        )
    }

    // ── разбор проекта ────────────────────────────────────────────────────────

    private data class Модуль(val имя: String, val путь: File, val пакет: String) {

        /** Что объявлено в `build.gradle.kts`: `projects.*` и артефакт крипто. */
        fun объявленныеЗависимости(): Set<String> {
            val текст = File(путь, "build.gradle.kts").readText()
            val итог = mutableSetOf<String>()
            for (совпадение in ПРОЕКТ.findAll(текст)) {
                итог += изАксессора(совпадение.groupValues[1])
            }
            // Оба входа объявляют зависимости старой записью project(":core:core-ui").
            for (совпадение in СТАРЫЙ_ПРОЕКТ.findAll(текст)) {
                итог += совпадение.groupValues[1].substringAfterLast(':')
            }
            if (текст.contains(АРТЕФАКТ_КРИПТО)) итог += МОДУЛЬ_КРИПТО
            return итог
        }

        /**
         * Импорты `io.tima.*` из production-наборов, по одному примеру на импорт.
         *
         * Тестовые наборы не считаются: тест вправе брать чужое напрямую, и правило
         * «объяви зависимость» относится к тому, что уезжает в сборку.
         */
        fun первыеСтороннИмпорты(): List<Pair<String, String>> {
            val итог = mutableListOf<Pair<String, String>>()
            val видели = mutableSetOf<String>()
            val src = File(путь, "src")
            if (!src.isDirectory) return итог
            for (набор in src.listFiles().orEmpty()) {
                // Production — только наборы, уезжающие в сборку: commonMain, jvmMain,
                // androidMain, iosMain, desktopMain и jvmCommon. Всё прочее — тесты и
                // фикстуры: там чужое берут напрямую, и это законно.
                if (!набор.isDirectory) continue
                if (!набор.name.endsWith("Main") && набор.name != "jvmCommon") continue
                набор.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { файл ->
                        for (строка in файл.readLines()) {
                            val обрез = строка.trim()
                            if (!обрез.startsWith("import ")) {
                                if (обрез.startsWith("class ") || обрез.startsWith("object ") ||
                                    обрез.startsWith("fun ") || обрез.startsWith("interface ")
                                ) break
                                continue
                            }
                            val импорт = обрез.removePrefix("import ").trim().removeSuffix(";")
                            if (!импорт.startsWith("io.tima.")) continue
                            if (импорт in видели) continue
                            видели += импорт
                            итог += импорт to "${набор.name}/${файл.name}"
                        }
                    }
            }
            return итог
        }
    }

    private fun собратьМодули(корень: File): List<Модуль> =
        корень.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".kotlin", ".git", "src") }
            .filter { it.isFile && it.name == "build.gradle.kts" && it.parentFile != корень }
            .mapNotNull { файл ->
                val каталог = файл.parentFile
                val пакет = первыйПакет(File(каталог, "src")) ?: return@mapNotNull null
                Модуль(каталог.name, каталог, пакет)
            }
            .toList()

    private fun первыйПакет(src: File): String? {
        if (!src.isDirectory) return null
        return src.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { файл ->
                файл.readLines().firstOrNull { it.startsWith("package ") }
                    ?.removePrefix("package ")?.trim()
            }
            // Самый короткий пакет модуля и есть его корень: подпакеты начинаются с него.
            .minByOrNull { it.length }
    }

    private companion object {
        /** `projects.core.coreOutbox` → `core-outbox`. */
        val ПРОЕКТ = Regex("""projects\.((?:\w+\.)*\w+)""")
        val СТАРЫЙ_ПРОЕКТ = Regex("""project\("(:[\w:-]+)"\)""")
        const val ПАКЕТ_КРИПТО = "io.tima.crypto"
        const val МОДУЛЬ_КРИПТО = "messenger-crypto"
        const val АРТЕФАКТ_КРИПТО = "io.tima:messenger-crypto"

        fun изАксессора(аксессор: String): String {
            val последний = аксессор.substringAfterLast('.')
            // coreOutbox → core-outbox: имя каталога модуля пишется через дефис.
            return последний.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
        }
    }
}
