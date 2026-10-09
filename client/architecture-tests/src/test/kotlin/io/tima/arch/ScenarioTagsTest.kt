package io.tima.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **Сценарии на телефонах опираются на то, что есть в коде** — ПЛАН-(МС), этап МС4 (2026-10-09).
 *
 * Сценарии (потоки Maestro в `maestro/` и скрипты Python в `maestro/phone`) нажимают кнопки по меткам (`testTag`) и
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
 */
class ScenarioTagsTest {

    private val client = File(requireNotNull(System.getProperty("client.root")) {
        "не передан client.root — смотри build.gradle.kts этого модуля"
    })
    private val root = client.parentFile
    private val maestro = File(root, "maestro")
    private val ledger = File(root, "doc_mig/ФУНКЦИОНАЛ/АВТОТЕСТЫ/СЦЕНАРИИ-ПО-ОКНАМ.md")

    private val scenarios: List<File> by lazy {
        maestro.walkTopDown()
            .onEnter { it.name != "__pycache__" && it.name != "node_modules" }
            .filter { it.isFile && (it.extension == "yaml" || it.extension == "py") }
            .toList()
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

    /** Метка → в каких сценариях. Метка — `слово:слово` в кавычках; `android:id/…` — системная, не наша. */
    private fun tags(): Map<String, List<String>> {
        val found = linkedMapOf<String, MutableList<String>>()
        for (file in scenarios) {
            for (m in TAG.findAll(file.readText())) {
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
        val body = code.values.firstNotNullOfOrNull { text ->
            Regex("""enum class $enumName\b[^{]*\{(.*?)(;|\n})""", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)
        } ?: return false
        return Regex("""(^|[\s,])$name\s*[(,;\n]""").containsMatchIn(body + "\n")
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
        val HAS = Regex("""has\(\s*\w+\s*,\s*'([^']+)'""")
        val IN_LINE = Regex("""'([^']+)'\s+in\s+(?:l|line)\b""")

        /** Код записи журнала впереди строки: `CALL `, `NET-CALL `. Код без текста остаётся как есть. */
        val CODE_PREFIX = Regex("""^[A-Z][A-Z-]*\s+(?=\S)""")

        val ENUM_OF_PREFIX = mapOf("tab" to "WindowTab", "switcher" to "Window", "settings" to "SettingsItem")

        /** Строки системы, а не журнала приложения: `dumpsys`, драйвер Maestro, окна Android. */
        val NOT_JOURNAL = setOf("=Awake", "dev.mobile.maestro", "mInputShown=true", "NotificationShade", "Keyguard")
    }
}
