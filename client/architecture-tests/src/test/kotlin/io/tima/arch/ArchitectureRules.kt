package io.tima.arch

import java.io.File

/**
 * Правило архитектуры: где оно действует и что запрещает.
 *
 * Правила выражены запретом импорта, потому что импорт — единственная зависимость,
 * которую видно в файле, не собирая проект.
 */
data class Rule(
    /** Человеческое имя: попадёт в текст падения, поэтому пишется для человека. */
    val name: String,
    /** Где действует: путь файла относительно корня клиента содержит любой из отрезков. */
    val appliesToPathContaining: List<String>,
    /** Запрещённые начала импортов. */
    val forbiddenImportPrefixes: List<String> = emptyList(),
    /** Запрещённые суффиксы имени файла (для правила про jvmCommon). */
    val forbiddenFileSuffixes: List<String> = emptyList(),
    /**
     * Запрещённые куски текста в файле — для правил, которые нельзя выразить импортом.
     *
     * Пример: зашитый цвет в дизайн-системе. Импорт `Color` там законен, а вот
     * `Color(0xFF…)` вне файла токенов означает компонент, который не следует теме.
     */
    val forbiddenContent: List<String> = emptyList(),
    /** Файлы, которым это правило не адресовано: имя файла содержит любую из строк. */
    val exceptFiles: List<String> = emptyList(),
    /** Почему запрещено — попадает в текст падения: правило без причины отключают. */
    val why: String,
)

/** Нарушение: файл, правило и что именно нашлось. */
data class Violation(val file: String, val rule: String, val detail: String, val why: String) {
    override fun toString(): String = "$file — ${rule}: $detail\n    почему: $why"
}

object ArchitectureRules {

    val rules: List<Rule> = listOf(
        Rule(
            name = "в общем коде нет JVM-библиотек",
            appliesToPathContaining = listOf("/src/commonMain/", "/src/commonTest/"),
            forbiddenImportPrefixes = listOf("java.", "javax.", "android.", "androidx."),
            why = "java.time и прочее компилируется на JVM и падает на iOS, то есть " +
                "ошибка находится позже всего. Время — kotlinx-datetime, файлы — okio. " +
                "Исключение только kotlin.jvm.* — это аннотации, влияющие лишь на JVM.",
        ),
        Rule(
            name = "domain ничего не знает о транспорте и платформе",
            appliesToPathContaining = listOf("/domain/"),
            forbiddenImportPrefixes = listOf(
                "io.ktor.", "app.cash.sqldelight.", "org.jetbrains.compose.", "androidx.",
                "android.", "io.tima.crypto.", "com.squareup.wire.", "java.", "javax.",
            ),
            why = "Domain зависит только от kotlinx.*. Ktor в Domain означает, что туда " +
                "протёк транспорт, и слой перестал быть слоем (Plan.md §2.2 п.2).",
        ),
        Rule(
            name = "feature не знает о данных и крипто",
            appliesToPathContaining = listOf("/feature/"),
            forbiddenImportPrefixes = listOf(
                "io.ktor.", "app.cash.sqldelight.", "io.tima.crypto.", "com.squareup.wire.",
            ),
            why = "Presentation говорит с Domain только через UseCase. Иначе правка " +
                "интерфейса снова становится правкой логики — та самая связанность, " +
                "из-за которой v1 переписывается (Plan.md §1).",
        ),
        Rule(
            name = "в jvmCommon нет классов слоёв",
            appliesToPathContaining = listOf("/src/jvmCommon/"),
            forbiddenFileSuffixes = listOf(
                "Repository.kt", "UseCase.kt", "Store.kt", "Service.kt", "Mapper.kt",
            ),
            why = "В v1 в jvmCommon осели TimaChatService на 1374 строки и MessageStore " +
                "на 296 — бизнес-логика, отрезанная от iOS не по необходимости, а потому " +
                "что так было проще. Набор существует только под доказанную нужду в JVM " +
                "(драйвер, JNI-обёртка), и логики в нём не бывает (Plan.md §3.2).",
        ),
        Rule(
            name = "в дизайн-системе нет зашитых цветов",
            // Только боевой код: в проверках чистый чёрный и белый нужны законно —
            // это концы шкалы контраста, 21 : 1.
            appliesToPathContaining = listOf("/core/core-ui/src/commonMain/"),
            forbiddenContent = listOf("Color(0x", "Color.White", "Color.Black"),
            // Токены — единственное место, где цвет записывается значением.
            exceptFiles = listOf("Tokens.kt"),
            why = "Признак готовности У.2 звучит как «компонент рисуется в двух темах без " +
                "правки кода экрана», и держится он ровно на этом: цвет берётся из темы. " +
                "Зашитый Color(0x…) выглядит безобидно и работает — в одной теме. В другой " +
                "он остаётся прежним, и находят это глазами на чужом устройстве.",
        ),
        Rule(
            name = "core-model без зависимостей",
            appliesToPathContaining = listOf("/core/core-model/"),
            forbiddenImportPrefixes = listOf("io.", "kotlinx.", "com.", "app.", "org.", "java.", "javax."),
            why = "На core-model ссылаются все. Как только сюда приезжает что-нибудь " +
                "своё, оно приезжает во всех остальных (Plan.md §3.1). Разрешены только " +
                "kotlin.* и io.tima.core.model.*",
        ),
    )

    /** Найти нарушения под [root]. Каталоги сборки не читаются. */
    fun violations(root: File): List<Violation> {
        val files = root.walkTopDown()
            .onEnter { dir ->
                dir.name !in setOf("build", ".gradle", ".kotlin", ".git", "fixtures")
            }
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        val found = mutableListOf<Violation>()
        for (file in files) {
            val path = file.absolutePath.replace('\\', '/')
            val relative = path.substringAfter(root.absolutePath.replace('\\', '/')).ifEmpty { path }
            for (rule in rules) {
                if (rule.appliesToPathContaining.none { path.contains(it) }) continue
                found += check(file, relative, rule)
            }
        }
        return found
    }

    /** Та же проверка для одного каталога — нужна, чтобы доказать, что правила ловят. */
    fun violationsIn(dir: File, rule: Rule): List<Violation> =
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { check(it, it.name, rule).asSequence() }
            .toList()

    private fun check(file: File, relative: String, rule: Rule): List<Violation> {
        val out = mutableListOf<Violation>()

        rule.forbiddenFileSuffixes.firstOrNull { file.name.endsWith(it) }?.let { suffix ->
            out += Violation(relative, rule.name, "имя файла кончается на $suffix", rule.why)
        }

        if (rule.forbiddenContent.isNotEmpty() && rule.exceptFiles.none { file.name.contains(it) }) {
            val текст = file.readText()
            for (кусок in rule.forbiddenContent) {
                if (текст.contains(кусок)) {
                    out += Violation(relative, rule.name, "найдено «$кусок»", rule.why)
                }
            }
        }

        if (rule.forbiddenImportPrefixes.isNotEmpty()) {
            for (line in file.readLines()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("import ")) {
                    // Импорты идут до первого объявления: дальше читать нечего.
                    if (trimmed.startsWith("class ") || trimmed.startsWith("object ") ||
                        trimmed.startsWith("fun ") || trimmed.startsWith("interface ")
                    ) break
                    continue
                }
                val imported = trimmed.removePrefix("import ").trim().removeSuffix(";")
                // kotlin.jvm.* — аннотации вроде @JvmInline: на iOS они просто ничего не
                // значат, поэтому в общем коде допустимы и запретом не считаются.
                if (imported.startsWith("kotlin.jvm.")) continue
                // androidx.compose.* — Compose Multiplatform, а не Android. Запрет на
                // androidx писался тогда, когда префикс означал «только Android»; Compose
                // под этим же именем собирается и под iOS, и под ПК. Смысл правила —
                // «не тащить в общий код то, что упадёт на iOS», и Compose ему не
                // противоречит. Настоящий заслон здесь всё равно компилятор:
                // платформенные части Compose (LocalContext и подобные) он не соберёт.
                if (imported.startsWith("androidx.compose.")) continue
                if (imported.startsWith("io.tima.core.model.")) continue
                val hit = rule.forbiddenImportPrefixes.firstOrNull { imported.startsWith(it) }
                if (hit != null) {
                    out += Violation(relative, rule.name, "импорт $imported", rule.why)
                }
            }
        }
        return out
    }
}
