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
class DependenciesTest {

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
    private val allowedDebts = emptySet<String>()

    @Test
    fun импорты_совпадают_с_манифестами() {
        val modules = assembleModules(clientRoot)
        assertTrue(modules.size >= 12, "модулей найдено ${modules.size} — карта собралась неверно")

        val found = mutableSetOf<String>()
        val explanations = mutableMapOf<String, MutableSet<String>>()

        for (module in modules) {
            val declared = module.dependencyDeclared()
            for ((import, file) in module.firstForeignImports()) {
                val whose = modules.firstOrNull { it != module && import.startsWith(it.packageValue + ".") }
                val foreignName = whose?.name
                    ?: if (import.startsWith(ПАКЕТ_КРИПТО)) МОДУЛЬ_КРИПТО else null
                if (foreignName == null || foreignName in declared) continue
                val edge = "${module.name} -> $foreignName"
                found += edge
                explanations.getOrPut(edge) { mutableSetOf() } += file
            }
        }

        val new = found - allowedDebts
        val settled = allowedDebts - found

        assertTrue(
            new.isEmpty(),
            "Модуль пользуется тем, чего не объявил:\n" +
                new.sorted().joinToString("\n") { "  $it — например ${explanations[it]?.first()}" } +
                "\n\nОбъявите зависимость в build.gradle.kts. Компилируется оно только " +
                "потому, что кто-то третий переэкспортировал типы через api: уберут " +
                "переэкспорт — упадёт модуль, который никто не трогал.",
        )

        assertEquals(
            emptySet(),
            settled,
            "Долг погашен, а запись о нём осталась в допущенныеДолги. Уберите строку " +
                "тем же коммитом: список, который живёт дольше долга, врёт о состоянии " +
                "проекта так же, как отсутствующий.",
        )
    }

    // ── разбор проекта ────────────────────────────────────────────────────────

    private data class Module(val name: String, val path: File, val packageValue: String) {

        /** Что объявлено в `build.gradle.kts`: `projects.*` и артефакт крипто. */
        fun dependencyDeclared(): Set<String> {
            val text = File(path, "build.gradle.kts").readText()
            val result = mutableSetOf<String>()
            for (match in PROJECT.findAll(text)) {
                result += fromAccessor(match.groupValues[1])
            }
            // Оба входа объявляют зависимости старой записью project(":core:core-ui").
            for (match in СТАРЫЙ_ПРОЕКТ.findAll(text)) {
                result += match.groupValues[1].substringAfterLast(':')
            }
            if (text.contains(АРТЕФАКТ_КРИПТО)) result += МОДУЛЬ_КРИПТО
            return result
        }

        /**
         * Импорты `io.tima.*` из production-наборов, по одному примеру на импорт.
         *
         * Тестовые наборы не считаются: тест вправе брать чужое напрямую, и правило
         * «объяви зависимость» относится к тому, что уезжает в сборку.
         */
        fun firstForeignImports(): List<Pair<String, String>> {
            val result = mutableListOf<Pair<String, String>>()
            val seen = mutableSetOf<String>()
            val src = File(path, "src")
            if (!src.isDirectory) return result
            for (setValue in src.listFiles().orEmpty()) {
                // Production — только наборы, уезжающие в сборку: commonMain, jvmMain,
                // androidMain, iosMain, desktopMain и jvmCommon. Всё прочее — тесты и
                // фикстуры: там чужое берут напрямую, и это законно.
                if (!setValue.isDirectory) continue
                if (!setValue.name.endsWith("Main") && setValue.name != "jvmCommon") continue
                setValue.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .forEach { file ->
                        for (line in file.readLines()) {
                            val clip = line.trim()
                            if (!clip.startsWith("import ")) {
                                if (clip.startsWith("class ") || clip.startsWith("object ") ||
                                    clip.startsWith("fun ") || clip.startsWith("interface ")
                                ) break
                                continue
                            }
                            val import = clip.removePrefix("import ").trim().removeSuffix(";")
                            if (!import.startsWith("io.tima.")) continue
                            if (import in seen) continue
                            seen += import
                            result += import to "${setValue.name}/${file.name}"
                        }
                    }
            }
            return result
        }
    }

    private fun assembleModules(root: File): List<Module> =
        root.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".kotlin", ".git", "src") }
            .filter { it.isFile && it.name == "build.gradle.kts" && it.parentFile != root }
            .mapNotNull { file ->
                val catalog = file.parentFile
                val packageValue = firstPackage(File(catalog, "src")) ?: return@mapNotNull null
                Module(catalog.name, catalog, packageValue)
            }
            .toList()

    private fun firstPackage(src: File): String? {
        if (!src.isDirectory) return null
        return src.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                file.readLines().firstOrNull { it.startsWith("package ") }
                    ?.removePrefix("package ")?.trim()
            }
            // Самый короткий пакет модуля и есть его корень: подпакеты начинаются с него.
            .minByOrNull { it.length }
    }

    private companion object {
        /** `projects.core.coreOutbox` → `core-outbox`. */
        val PROJECT = Regex("""projects\.((?:\w+\.)*\w+)""")
        val СТАРЫЙ_ПРОЕКТ = Regex("""project\("(:[\w:-]+)"\)""")
        const val ПАКЕТ_КРИПТО = "io.tima.crypto"
        const val МОДУЛЬ_КРИПТО = "messenger-crypto"
        const val АРТЕФАКТ_КРИПТО = "io.tima:messenger-crypto"

        fun fromAccessor(accessor: String): String {
            val last = accessor.substringAfterLast('.')
            // coreOutbox → core-outbox: имя каталога модуля пишется через дефис.
            return last.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
        }
    }
}
