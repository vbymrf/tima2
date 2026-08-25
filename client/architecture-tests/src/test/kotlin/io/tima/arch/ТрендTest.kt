package io.tima.arch

import java.io.File
import kotlin.test.Test

/**
 * **Отчёт о росте хабов. Не падает никогда.**
 *
 * Числа ниже — размер общих мест, в которые упирается каждая новая функция:
 * маршруты сервера, методы общих receiver-ов, публичные свойства `Сеть`, импорты
 * `Корень.kt`. Программа архитектурных изменений ставит целью «функция = файл»:
 * новый раздел добавляет свой файл, а эти счётчики не растут.
 *
 * Порог здесь намеренно не задан. Счётчик, который валит сборку, обходят
 * исключением; счётчик, который лежит в отчёте, обсуждают на ревью — и именно
 * обсуждение здесь и нужно: рост на единицу законен, рост на десять означает,
 * что шов снова обходят.
 *
 * Отчёт пишется в `client/architecture-tests/build/reports/trend.md`.
 */
class ТрендTest {

    private val clientRoot = File(requireNotNull(System.getProperty("client.root")) {
        "не передан client.root — смотри build.gradle.kts этого модуля"
    })

    @Test
    fun отчёт_о_хабах() {
        val сервер = File(clientRoot.parentFile, "server")

        val строки = listOf(
            Счётчик(
                "Маршруты сервера",
                "`mux.HandleFunc` в server/internal/api/server.go",
                считатьВФайле(File(сервер, "internal/api/server.go"), Regex("""mux\.HandleFunc""")),
            ),
            Счётчик(
                "Методы `Server`",
                "`func (s *Server)` в server/internal/api/*.go",
                считатьВКаталоге(File(сервер, "internal/api"), "go", Regex("""^func \(s \*Server\)""")),
            ),
            Счётчик(
                "Методы `Store`",
                "`func (s *Store)` в server/internal/store/*.go",
                считатьВКаталоге(File(сервер, "internal/store"), "go", Regex("""^func \(s \*Store\)""")),
            ),
            Счётчик(
                "Публичные `val` `Сеть`",
                "свойства класса Сеть в shared/Окружение.kt",
                публичныеСвойстваСети(),
            ),
            Счётчик(
                "Импорты `Корень.kt`",
                "строки import",
                считатьВФайле(корень(), Regex("""^import """)),
            ),
        )

        val отчёт = buildString {
            appendLine("# Хабы: сколько мест правит новая функция")
            appendLine()
            appendLine("Отчёт, а не проверка: тест не падает ни при каком значении.")
            appendLine("Рост любого числа при добавлении функции — предмет ревью.")
            appendLine()
            appendLine("| Счётчик | Как считается | Сейчас |")
            appendLine("|---|---|---:|")
            строки.forEach { appendLine("| ${it.имя} | ${it.как} | ${it.сколько} |") }
        }

        val файл = File(clientRoot, "architecture-tests/build/reports/trend.md")
        файл.parentFile.mkdirs()
        файл.writeText(отчёт)
        println(отчёт)
    }

    private data class Счётчик(val имя: String, val как: String, val сколько: Int)

    private fun корень(): File =
        File(clientRoot, "shared/src/commonMain/kotlin/io/tima/shared/Корень.kt")

    private fun считатьВФайле(файл: File, что: Regex): Int =
        if (!файл.isFile) -1 else файл.readLines().count { что.containsMatchIn(it.trim()) }

    private fun считатьВКаталоге(каталог: File, расширение: String, что: Regex): Int {
        if (!каталог.isDirectory) return -1
        return каталог.walkTopDown()
            .filter { it.isFile && it.extension == расширение && !it.name.endsWith("_test.go") }
            .sumOf { файл -> файл.readLines().count { что.containsMatchIn(it) } }
    }

    /**
     * Публичные свойства `Сеть` — и в конструкторе, и в теле класса.
     *
     * Считается по тексту: `val` без `private`, между `class Сеть` и концом файла.
     * Это оценка, а не разбор синтаксиса, — для отчёта о росте её достаточно.
     */
    private fun публичныеСвойстваСети(): Int {
        val файл = File(clientRoot, "shared/src/commonMain/kotlin/io/tima/shared/Окружение.kt")
        if (!файл.isFile) return -1
        val текст = файл.readText()
        val начало = текст.indexOf("class Сеть")
        if (начало < 0) return -1
        var счёт = 0
        for (строка in текст.substring(начало).lineSequence().drop(1)) {
            // Класс кончается закрывающей скобкой в первой колонке: дальше идут
            // соседние объявления файла, и их свойства к Сеть отношения не имеют.
            if (строка == "}") break
            // Только собственные свойства класса: отступ ровно четыре пробела.
            // `val` внутри метода стоит глубже и хабом не является.
            // override val считается наравне с val: свойство остаётся публичным.
            // Разница в другом — теперь его объявляет ПОРТ, а не сам класс.
            if (!строка.startsWith("    val ") && !строка.startsWith("    override val ")) continue
            if (строка.startsWith("    private") || строка.startsWith("    internal")) continue
            счёт++
        }
        return счёт
    }
}
