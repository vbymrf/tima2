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
            name = "в дизайн-системе и на экранах нет зашитых цветов",
            // Только боевой код: в проверках чистый чёрный и белый нужны законно —
            // это концы шкалы контраста, 21 : 1.
            //
            // Экраны попали под то же правило, как только появились: зашитый цвет на
            // экране — та же поломка, что в компоненте, и находят её так же — глазами
            // на чужом устройстве.
            appliesToPathContaining = listOf(
                "/core/core-ui/src/commonMain/",
                "/feature/",
            ),
            forbiddenContent = listOf("Color(0x", "Color.White", "Color.Black"),
            // Токены — единственное место, где цвет записывается значением.
            exceptFiles = listOf("Tokens.kt"),
            why = "Признак готовности У.2 звучит как «компонент рисуется в двух темах без " +
                "правки кода экрана», и держится он ровно на этом: цвет берётся из темы. " +
                "Зашитый Color(0x…) выглядит безобидно и работает — в одной теме. В другой " +
                "он остаётся прежним, и находят это глазами на чужом устройстве.",
        ),
        Rule(
            name = "платформа не называется строкой в общем коде",
            appliesToPathContaining = listOf("/src/commonMain/"),
            forbiddenContent = listOf("platform = \""),
            // Единственное место, где эти три строки записаны значением, — сам перечень.
            exceptFiles = listOf("Платформа.kt"),
            why = "Строка platform = \"desktop\" стояла в commonMain, то есть телефон " +
                "объявлял себя ПК. Отказ при этом тихий: регистрация проходит, сообщения " +
                "ходят — а подтвердить привязку нового устройства по QR сервер разрешает " +
                "только телефону (key-lifecycle.md §2), и телефон получал бы not_a_phone. " +
                "По симптому это неотличимо от поломки самого QR. Платформу объявляет " +
                "платформенный вход, перечнем Платформа, а не строкой в общем коде.",
        ),
        Rule(
            name = "feature не импортирует чужой feature",
            appliesToPathContaining = listOf("/feature/"),
            forbiddenImportPrefixes = listOf("io.tima.feature."),
            // Свой пакет разрешён механикой check(): импорт, начинающийся с пакета
            // собственного модуля, нарушением не считается.
            why = "Раздел разрабатывается независимо ровно до тех пор, пока не трогает " +
                "чужие экраны. Импорт соседнего feature связывает два раздела навсегда: " +
                "их больше нельзя ни раздать двум людям, ни выкатить порознь. Общее " +
                "выносится в domain или core, а не импортируется у соседа.",
        ),
        Rule(
            name = "feature не знает адаптеров сети и базы",
            appliesToPathContaining = listOf("/feature/"),
            forbiddenImportPrefixes = listOf(
                "io.tima.core.network.",
                "io.tima.core.database.",
                "io.tima.core.encryption.",
            ),
            why = "Feature говорит с миром через порты domain, которые наполняет " +
                "адаптерами композиция. Прямой импорт GroupsOverHttp или Sql* из feature " +
                "делает транспорт и схему базы частью экрана: миграция схемы или смена " +
                "транспорта превращается в правку UI. Сегодня это держит только узкий " +
                "манифест — до первой строки, добавленной соседом.",
        ),
        Rule(
            name = "shared не обходит крипто-фасад",
            appliesToPathContaining = listOf("/shared/src/commonMain/"),
            forbiddenImportPrefixes = listOf("io.tima.crypto."),
            why = "Смена крипто-DTO или сериализатора должна быть правкой одного " +
                "модуля. Каждый импорт io.tima.crypto выше core-encryption добавляет " +
                "место, которое правится синхронно и однажды будет забыто.",
        ),
        Rule(
            name = "SQLDelight не выходит за core-database",
            appliesToPathContaining = listOf("/shared/", "/feature/"),
            forbiddenImportPrefixes = listOf("app.cash.sqldelight."),
            forbiddenContent = listOf(".chatsQueries", ".outboxQueries"),
            why = "Прямой запрос из shared делает схему базы публичным API: миграция " +
                "схемы превращается в правку экранов и приёмника.",
        ),
        Rule(
            name = "Ktor не поднимается выше core-network",
            appliesToPathContaining = listOf("/shared/src/commonMain/"),
            forbiddenImportPrefixes = listOf("io.ktor."),
            why = "Публичный HttpClient в Сеть делает транспорт частью API shared: " +
                "смена движка или политики токенов задевает всех потребителей.",
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

    /**
     * Пакет модуля, выведенный из пути файла: `/feature/feature-chat/` →
     * `io.tima.feature.chat.`
     *
     * Нужен правилу «feature не импортирует чужой feature»: запрет на префикс
     * `io.tima.feature.` без этого запретил бы модулю его собственные импорты, то есть
     * оказался бы невыполним и был бы снят первым же человеком, который его встретил.
     */
    private fun свойПакет(path: String): String? {
        // Слой и имя модуля совпадают по построению: /feature/feature-chat/.
        val m = Regex("""/(feature|core|domain)/\1-([a-z0-9-]+)/""").find(path)
            ?: return null
        val слой = m.groupValues[1]
        val имя = m.groupValues[2].replace("-", "")
        return "io.tima.$слой.$имя."
    }

    private fun check(file: File, relative: String, rule: Rule): List<Violation> {
        val out = mutableListOf<Violation>()

        // Исключение относится к ФАЙЛУ, а не к виду проверки. До 2026-08-25 оно
        // действовало только на forbiddenContent, и правило с exceptFiles по импортам
        // падало на собственном исключении — то есть было невыразимо.
        if (rule.exceptFiles.any { file.name.contains(it) }) return out

        rule.forbiddenFileSuffixes.firstOrNull { file.name.endsWith(it) }?.let { suffix ->
            out += Violation(relative, rule.name, "имя файла кончается на $suffix", rule.why)
        }

        if (rule.forbiddenContent.isNotEmpty()) {
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
                // Свой пакет — не чужая зависимость. Частный случай core-model был
                // записан отдельной строкой; теперь это общее правило для всех слоёв.
                val свой = свойПакет(file.absolutePath.replace('\\', '/'))
                if (свой != null && imported.startsWith(свой)) continue
                val hit = rule.forbiddenImportPrefixes.firstOrNull { imported.startsWith(it) }
                if (hit != null) {
                    out += Violation(relative, rule.name, "импорт $imported", rule.why)
                }
            }
        }
        return out
    }
}
