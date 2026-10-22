package io.tima.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Сценарии на телефонах опираются на то, что есть в коде** — ПЛАН-(МС), этап МС4 (2026-10-09).
 *
 * Сценарии (потоки Maestro в `maestro/` и в навыке `.cursor/skills/phone-scenario-runner/flows`, скрипты Python в `maestro/phone`) нажимают кнопки по меткам (`testTag`) и
 * проверяют звонок по строкам журнала. 2026-10-09 вид панели звонка сменился, «🎤», по которой
 * сценарий узнавал окно звонка, пропал — и это выяснилось посреди живого звонка, а не при сборке.
 * Этот тест ловит то же на компьютере:
 *
 * - **метка из сценария есть в коде** — строкой `"call:hangup"` или, для меток из перечней, именем
 *   в перечне (`tab:` — `WindowTab`, `switcher:` — `Window`, `settings:` — `SettingsItem`);
 * - **метка из сценария записана в учёте** `doc_mig/ФУНКЦИОНАЛ/АВТОТЕСТЫ/СЦЕНАРИИ-ПО-ОКНАМ.md` —
 *   иначе учёт отстал, и тот, кто меняет окно, не узнает, что сломает;
 * - **строка журнала, которую проверяет сценарий, есть в коде и в учёте.**
 *
 * Надписи и значки тест не проверяет: они законно меняются и переводятся, и сценарии держат их
 * только запасным путём (заказчик 2026-10-09: «от слов и значков мы не отказываемся»).
 *
 * **Пара меток экрана** — место `screen:` и подокно `popup:` (ПЛАН-(МО)-МЕТОК-ОКОН, этап МО2):
 * коды вычисляются, а не стоят строкой у кнопки, поэтому сверяются отдельно — с перечнями
 * `PopupCode`, `PlaceCode`, с кодами подэкранов `whereCode` и с перечнями окон и вкладок. И в
 * сценариях нет проверок «не видно»: на экране, где элемент есть, такая проверка у Maestro ждёт
 * около 8 с (PH-36), а пара меток проверяется положительно.
 */
class ScenarioTagsTest {

    private val client = File(requireNotNull(System.getProperty("client.root")) {
        "не передан client.root — смотри build.gradle.kts этого модуля"
    })
    private val root = client.parentFile
    private val maestro = File(root, "maestro")

    /** Сценарии в навыке телефонов (заказчик 2026-10-22: «сценарии в навык, в git») — учёт тот же. */
    private val skillFlows = File(root, ".cursor/skills/phone-scenario-runner/flows")
    private val ledger = File(root, "doc_mig/ФУНКЦИОНАЛ/АВТОТЕСТЫ/СЦЕНАРИИ-ПО-ОКНАМ.md")

    private val scenarios: List<File> by lazy {
        listOf(maestro, skillFlows).filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown()
                .onEnter { it.name != "__pycache__" && it.name != "node_modules" }
                .filter { it.isFile && (it.extension == "yaml" || it.extension == "py") }
                .toList()
        }
    }

    /** Код клиента без сборок и тестов: метка, которая живёт только в тесте, сценарию не поможет. */
    private val code: Map<File, String> by lazy {
        client.walkTopDown()
            .onEnter {
                it.name != "build" && it.name != ".gradle" && it.name != "architecture-tests" &&
                    it.name != "test" && !it.name.endsWith("Test")
            }
            .filter { it.isFile && it.extension == "kt" }
            .associateWith { it.readText() }
    }

    private val codeText: String by lazy { code.values.joinToString("\n") }

    @Test
    fun метки_из_сценариев_есть_в_коде() {
        val missing = tags().filter { (tag, _) -> !inCode(tag) }
        assertTrue(
            missing.isEmpty(),
            "Сценарии опираются на метки, которых в коде нет:\n" +
                missing.entries.joinToString("\n") { (tag, files) -> "  $tag — ${files.joinToString()}" } +
                "\n\nМетку убрали или переименовали — верните её или перепишите сценарий; " +
                "учёт — doc_mig/ФУНКЦИОНАЛ/АВТОТЕСТЫ/СЦЕНАРИИ-ПО-ОКНАМ.md.",
        )
    }

    @Test
    fun метки_из_сценариев_записаны_в_учёте() {
        val text = ledger.readText()
        val missing = tags().keys.filter { "`$it`" !in text }
        assertTrue(
            missing.isEmpty(),
            "Метки из сценариев не записаны в СЦЕНАРИИ-ПО-ОКНАМ.md: ${missing.joinToString()}.\n" +
                "Строка — в таблицу окна: что, метка, запасная надпись, сценарии, где в коде.",
        )
    }

    @Test
    fun строки_журнала_из_проверок_есть_в_коде_и_в_учёте() {
        val text = ledger.readText()
        val phrases = journalPhrases()
        assertTrue(phrases.isNotEmpty(), "не нашлось ни одной строки журнала в сценариях — сломан разбор")
        val notInCode = phrases.filter { (phrase, _) -> phrase !in codeText }
        val notInLedger = phrases.keys.filter { it !in text }
        assertTrue(
            notInCode.isEmpty(),
            "Сценарии проверяют строки журнала, которых в коде нет:\n" +
                notInCode.entries.joinToString("\n") { (p, files) -> "  «$p» — ${files.joinToString()}" } +
                "\n\nЗапись переименовали — поправьте сценарий и учёт тем же коммитом.",
        )
        assertTrue(
            notInLedger.isEmpty(),
            "Строки журнала из проверок не записаны в СЦЕНАРИИ-ПО-ОКНАМ.md: ${notInLedger.joinToString { "«$it»" }}",
        )
    }

    @Test
    fun места_и_подокна_из_сценариев_и_учёта_есть_в_коде() {
        val text = ledger.readText()
        val used = screenTags(scenarios.associate { it.name to it.readText() }, SCREEN_TAG)
        val listed = screenTags(mapOf(ledger.name to text), LEDGER_SCREEN_TAG)
        val unknown = (used + listed).filterKeys { !screenTagKnown(it) }
        assertTrue(
            unknown.isEmpty(),
            "Места и подокна, которых код не выдаёт:\n" +
                unknown.entries.joinToString("\n") { (tag, files) -> "  $tag — ${files.joinToString()}" } +
                "\n\nКоды — PopupCode и PlaceCode (ScreenMarks.kt), whereCode и placeCode (Root.kt); " +
                "код убрали — уберите и из сценария, и из учёта.",
        )
        val notListed = used.keys.filter { "`$it`" !in text && !(it.endsWith('.') && "`$it" in text) }
        assertTrue(notListed.isEmpty(), "Места и подокна из сценариев не записаны в СЦЕНАРИИ-ПО-ОКНАМ.md: ${notListed.joinToString()}")
    }

    @Test
    fun каждое_место_и_подокно_кода_записано_в_учёте() {
        val text = ledger.readText()
        val missing = popupCodes().map { "popup:$it" }.filter { "`$it`" !in text } +
            (placeCodes() + whereCodes()).map { "screen:$it" }.filter { "`$it`" !in text } +
            enumNames("Window").filter { it != "Call" }.map { "screen:$it" }
                .filter { "`$it`" !in text && "`$it." !in text }
        assertTrue(
            missing.isEmpty(),
            "Места и подокна из кода не записаны в учёте, раздел «Места и подокна»: ${missing.joinToString()}.\n" +
                "Строка — код, что это, как попасть (у места) или как закрыть (у подокна), где в коде.",
        )
    }

    @Test
    fun в_сценариях_нет_проверок_не_видно() {
        val found = scenarios.filter { it.extension == "yaml" && "notVisible" in it.readText() }.map { it.name }
        assertTrue(
            found.isEmpty(),
            "notVisible в сценариях: ${found.joinToString()}.\nПереверни: «видно» с флажком, а не «не видно» — " +
                "на экране, где элемент есть, «не видно» у Maestro ждёт около 8 с (PH-36, ПЛАН-(МО) МО4).",
        )
    }

    /** `screen:`/`popup:` → где встретились. Точка на конце — начало кода: `screen:call.` в сценарии — все стадии звонка, `screen:Media.<вкладка>` в учёте — все вкладки. */
    private fun screenTags(texts: Map<String, String>, pattern: Regex): Map<String, List<String>> {
        val found = linkedMapOf<String, MutableList<String>>()
        for ((name, text) in texts) {
            for (m in pattern.findAll(text)) {
                found.getOrPut(m.groupValues[2]) { mutableListOf() }.add(name)
            }
        }
        return found.mapValues { (_, files) -> files.distinct() }
    }

    private fun screenTagKnown(tag: String): Boolean {
        val (prefix, value) = tag.split(':', limit = 2)
        if (value.endsWith('.')) {
            val start = value.dropLast(1)
            return if (prefix == "popup") popupCodes().any { it.startsWith(value) } else
                (placeCodes() + whereCodes()).any { it.startsWith(value) } || enumHas("Window", start) ||
                    start == "settings" // пункты настроек — `settings.<пункт>` по перечню SettingsItem
        }
        if (prefix == "popup") return value in popupCodes()
        if (value in placeCodes() || value in whereCodes()) return true
        val parts = value.split('.', limit = 2)
        if (parts[0] == "settings" && parts.size == 2) return enumHas("SettingsItem", parts[1].uppercase())
        return enumHas("Window", parts[0]) && (parts.size == 1 || enumHas("WindowTab", parts[1]))
    }

    private fun constValues(objectName: String): Set<String> {
        val body = code.values.firstNotNullOfOrNull { text ->
            Regex("""object $objectName\s*\{(.*?)\n}""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
        } ?: error("не нашёлся object $objectName — сломан разбор")
        return Regex("""const val \w+\s*=\s*"([^"]+)"""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    private fun popupCodes(): Set<String> = constValues("PopupCode")

    private fun placeCodes(): Set<String> = constValues("PlaceCode")

    /** Коды подэкранов из `whereCode`; `list` — «подэкрана нет», место тогда — окно с вкладкой. */
    private fun whereCodes(): Set<String> {
        val body = code.values.firstNotNullOfOrNull { text ->
            Regex("""fun whereCode\(.*?\n}""", RegexOption.DOT_MATCHES_ALL).find(text)?.value
        } ?: error("не нашлась whereCode — сломан разбор")
        return Regex(""""([a-z][a-z.]*)"""").findAll(body).map { it.groupValues[1] }.toSet() - "list"
    }

    private fun enumBody(enumName: String): String? = code.values.firstNotNullOfOrNull { text ->
        Regex("""enum class $enumName\b[^{]*\{(.*?)(;|\n})""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
    }

    private fun enumHas(enumName: String, name: String): Boolean {
        val body = enumBody(enumName) ?: return false
        return Regex("""(^|[\s,])${Regex.escape(name)}\s*[(,;\n]""").containsMatchIn(body + "\n")
    }

    private fun enumNames(enumName: String): List<String> {
        val body = enumBody(enumName) ?: error("не нашёлся enum $enumName")
        return Regex("""(?m)^\s*([A-Z][A-Za-z]*)\s*[(,]""").findAll(body).map { it.groupValues[1] }.toList()
    }

    /** Метка → в каких сценариях. Метка — `слово:слово` в кавычках; `android:id/…` — системная, не наша. */
    private fun tags(): Map<String, List<String>> {
        val found = linkedMapOf<String, MutableList<String>>()
        for (file in scenarios) {
            for (m in TAG.findAll(file.readText())) {
                // Пара меток экрана сверяется своими проверками: её коды вычисляются.
                if (m.groupValues[2].substringBefore(':') in SCREEN_PREFIXES) continue
                found.getOrPut(m.groupValues[2]) { mutableListOf() }.add(file.name)
            }
        }
        check(found.isNotEmpty()) { "в сценариях не нашлось ни одной метки — сломан разбор (${maestro.path})" }
        return found.mapValues { (_, files) -> files.distinct() }
    }

    private fun inCode(tag: String): Boolean {
        if ("\"$tag\"" in codeText) return true
        val (prefix, name) = tag.split(':', limit = 2)
        val enumName = ENUM_OF_PREFIX[prefix] ?: return false
        return enumHas(enumName, name)
    }

    /**
     * Строки журнала из проверок скриптов Python в `maestro/phone`: `has(журнал, '…')` и `'…' in строка`.
     * Код записи впереди («CALL ») и подробности после двух пробелов («  стала=Connected»)
     * отрезаются: в коде они пишутся отдельно от текста. Подробность без текста («видео=true») и
     * строки системы ([NOT_JOURNAL]) — не журнал приложения.
     */
    private fun journalPhrases(): Map<String, List<String>> {
        val found = linkedMapOf<String, MutableList<String>>()
        for (file in scenarios.filter { it.extension == "py" }) {
            val text = file.readText()
            val raw = HAS.findAll(text).map { it.groupValues[1] } + IN_LINE.findAll(text).map { it.groupValues[1] }
            for (r in raw) {
                if (r in NOT_JOURNAL) continue
                val phrase = r.substringBefore("  ").let { CODE_PREFIX.replace(it, "") }.trim()
                if (phrase.isEmpty() || ('=' in phrase && ' ' !in phrase)) continue
                found.getOrPut(phrase) { mutableListOf() }.add(file.name)
            }
        }
        return found.mapValues { (_, files) -> files.distinct() }
    }

    private companion object {
        val TAG = Regex("""(["'])([a-z]+:[A-Za-z][A-Za-z_-]*)\1""")
        val SCREEN_PREFIXES = setOf("screen", "popup")
        val SCREEN_TAG = Regex("""(["'])((?:screen|popup):[A-Za-z][A-Za-z0-9_.]*)\1""")
        val LEDGER_SCREEN_TAG = Regex("""(`)((?:screen|popup):[A-Za-z][A-Za-z0-9_.]*)""")
        val HAS = Regex("""has\(\s*\w+\s*,\s*'([^']+)'""")
        val IN_LINE = Regex("""'([^']+)'\s+in\s+(?:l|line)\b""")

        /** Код записи журнала впереди строки: `CALL `, `NET-CALL `. Код без текста остаётся как есть. */
        val CODE_PREFIX = Regex("""^[A-Z][A-Z-]*\s+(?=\S)""")

        val ENUM_OF_PREFIX = mapOf("tab" to "WindowTab", "switcher" to "Window", "settings" to "SettingsItem")

        /** Строки системы, а не журнала приложения: `dumpsys`, драйвер Maestro, окна Android. */
        val NOT_JOURNAL = setOf("=Awake", "dev.mobile.maestro", "mInputShown=true", "NotificationShade", "Keyguard")
    }
}
