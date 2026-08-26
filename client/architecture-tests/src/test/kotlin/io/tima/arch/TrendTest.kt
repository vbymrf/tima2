package io.tima.arch

import java.io.File
import kotlin.test.Test

/**
 * **Отчёт о росте хабов. Не падает никогда.**
 *
 * Числа ниже — размер общих мест, в которые упирается каждая новая функция:
 * маршруты сервера, методы общих receiver-ов, публичные свойства `Сеть`, импорты
 * `Root.kt`. Программа архитектурных изменений ставит целью «функция = файл»:
 * новый раздел добавляет свой файл, а эти счётчики не растут.
 *
 * Порог здесь намеренно не задан. Счётчик, который валит сборку, обходят
 * исключением; счётчик, который лежит в отчёте, обсуждают на ревью — и именно
 * обсуждение здесь и нужно: рост на единицу законен, рост на десять означает,
 * что шов снова обходят.
 *
 * Отчёт пишется в `client/architecture-tests/build/reports/trend.md`.
 */
class TrendTest {

    private val clientRoot = File(requireNotNull(System.getProperty("client.root")) {
        "не передан client.root — смотри build.gradle.kts этого модуля"
    })

    @Test
    fun отчёт_о_хабах() {
        val server = File(clientRoot.parentFile, "server")

        val lines = listOf(
            Counter(
                "Маршруты сервера",
                "`mux.HandleFunc` в server/internal/api/server.go",
                countInFile(File(server, "internal/api/server.go"), Regex("""mux\.HandleFunc""")),
            ),
            Counter(
                "Методы `Server`",
                "`func (s *Server)` в server/internal/api/*.go",
                countInCatalog(File(server, "internal/api"), "go", Regex("""^func \(s \*Server\)""")),
            ),
            Counter(
                "Методы `Store`",
                "`func (s *Store)` в server/internal/store/*.go",
                countInCatalog(File(server, "internal/store"), "go", Regex("""^func \(s \*Store\)""")),
            ),
            Counter(
                "Публичные `val` `Сеть`",
                "свойства класса Сеть в shared/Environment.kt",
                publicPropertyNetwork(),
            ),
            Counter(
                "Импорты `Root.kt`",
                "строки import",
                countInFile(root(), Regex("""^import """)),
            ),
            Counter(
                "Чужих модулей видит `shared`",
                "различные io.tima.* модули в импортах production-файлов shared",
                spreadShared(),
            ),
            Counter(
                "Классов `*Api` в core-network",
                "объявления class …Api",
                countInCatalog(
                    File(clientRoot, "core/core-network/src/commonMain"), "kt",
                    // Скобка обязательна: без неё в счёт попадал LinkApiTest —
                    // тестовый класс, к размеру поверхности отношения не имеющий.
                    Regex("""^(internal )?class [A-Za-z0-9_]+Api\("""),
                ),
            ),
        )

        val report = buildString {
            appendLine("# Хабы: сколько мест правит новая функция")
            appendLine()
            appendLine("Отчёт, а не проверка: тест не падает ни при каком значении.")
            appendLine("Рост любого числа при добавлении функции — предмет ревью.")
            appendLine()
            appendLine("| Счётчик | Как считается | Сейчас |")
            appendLine("|---|---|---:|")
            lines.forEach { appendLine("| ${it.name} | ${it.asValue} | ${it.howMany} |") }
        }

        val file = File(clientRoot, "architecture-tests/build/reports/trend.md")
        file.parentFile.mkdirs()
        file.writeText(report)
        println(report)
    }

    private data class Counter(val name: String, val asValue: String, val howMany: Int)

    /**
     * Сколько чужих модулей видит композиция.
     *
     * Растёт ровно тогда, когда новая функция врастает в общий контур вместо своего
     * модуля: каждый новый импорт из чужого пакета — это ещё одна связь, которую
     * потом придётся распутывать при разделении работы.
     */
    private fun spreadShared(): Int {
        val src = File(clientRoot, "shared/src")
        if (!src.isDirectory) return -1
        return src.listFiles().orEmpty()
            .filter { it.isDirectory && (it.name.endsWith("Main") || it.name == "jvmCommon") }
            .flatMap { setValue ->
                setValue.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            .flatMap { file -> file.readLines() }
            .map { it.trim() }
            .filter { it.startsWith("import io.tima.") }
            .mapNotNull { line ->
                // io.tima.core.network.KeysApi → io.tima.core.network
                line.removePrefix("import ").split('.').take(4).joinToString(".")
            }
            .filter { !it.startsWith("io.tima.shared") }
            .distinct()
            .size
    }

    private fun root(): File =
        File(clientRoot, "shared/src/commonMain/kotlin/io/tima/shared/Root.kt")

    private fun countInFile(file: File, what: Regex): Int =
        if (!file.isFile) -1 else file.readLines().count { what.containsMatchIn(it.trim()) }

    private fun countInCatalog(catalog: File, extension: String, what: Regex): Int {
        if (!catalog.isDirectory) return -1
        return catalog.walkTopDown()
            .filter { it.isFile && it.extension == extension && !it.name.endsWith("_test.go") }
            .sumOf { file -> file.readLines().count { what.containsMatchIn(it) } }
    }

    /**
     * Публичные свойства `Сеть` — и в конструкторе, и в теле класса.
     *
     * Считается по тексту: `val` без `private`, между `class Сеть` и концом файла.
     * Это оценка, а не разбор синтаксиса, — для отчёта о росте её достаточно.
     */
    private fun publicPropertyNetwork(): Int {
        val file = File(clientRoot, "shared/src/commonMain/kotlin/io/tima/shared/Environment.kt")
        if (!file.isFile) return -1
        val text = file.readText()
        val start = text.indexOf("class Сеть")
        if (start < 0) return -1
        var count = 0
        for (line in text.substring(start).lineSequence().drop(1)) {
            // Класс кончается закрывающей скобкой в первой колонке: дальше идут
            // соседние объявления файла, и их свойства к Сеть отношения не имеют.
            if (line == "}") break
            // Только собственные свойства класса: отступ ровно четыре пробела.
            // `val` внутри метода стоит глубже и хабом не является.
            // override val считается наравне с val: свойство остаётся публичным.
            // Разница в другом — теперь его объявляет ПОРТ, а не сам класс.
            if (!line.startsWith("    val ") && !line.startsWith("    override val ")) continue
            if (line.startsWith("    private") || line.startsWith("    internal")) continue
            count++
        }
        return count
    }
}
