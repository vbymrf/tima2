package io.tima.arch

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureTest {

    private val clientRoot = File(requireNotNull(System.getProperty("client.root")) {
        "не передан client.root — смотри build.gradle.kts этого модуля"
    })

    private val fixtures = File(requireNotNull(System.getProperty("fixtures.root")) {
        "не передан fixtures.root"
    })

    @Test
    fun правила_архитектуры_не_нарушены() {
        val violations = ArchitectureRules.violations(clientRoot)
        assertTrue(
            violations.isEmpty(),
            "Нарушений: ${violations.size}\n\n" + violations.joinToString("\n\n"),
        )
    }

    /**
     * Правило, которое ни разу не срабатывало, может быть просто выключено — и
     * узнать об этом надо сразу, а не через полгода (дорожная карта, К1).
     *
     * Поэтому здесь заведомо плохой файл, и проверка обязана его поймать. Он лежит
     * в `src/test/fixtures/`, вне наборов исходников: не компилируется и в основную
     * проверку не попадает.
     */
    @Test
    fun проверка_действительно_ловит_нарушение() {
        val domainRule = ArchitectureRules.rules.single { it.name.startsWith("domain") }
        // Образцов в каталоге несколько — берём тот, который заведён под это правило.
        val found = ArchitectureRules.violationsIn(fixtures, domainRule)
            .filter { it.file == "ОбразецUseCase.kt" }

        assertEquals(
            1,
            found.size,
            "Ожидалось ровно одно нарушение в образце, найдено ${found.size}: $found",
        )
        assertTrue(found.single().detail.contains("io.ktor"), "не тот импорт: ${found.single()}")
    }

    /**
     * То же доказательство для пяти правил: крипто-фасад, SQLDelight, Ktor и два
     * заградительных — про соседний feature и про адаптеры данных. Каждое из них живёт с временными исключениями, а правило с исключениями
     * особенно легко превратить в правило, которое не ловит ничего.
     *
     * Образец нарушает все три сразу — по одному импорту на правило.
     */
    @Test
    fun правила_шага_1_ловят_нарушения() {
        val ожидания = mapOf(
            "shared не обходит крипто-фасад" to "io.tima.crypto",
            "SQLDelight не выходит за core-database" to "app.cash.sqldelight",
            "Ktor не поднимается выше core-network" to "io.ktor",
            // Заградительные: нарушений в проекте нет ни одного, и образец —
            // единственное, что отличает их от выключенных.
            "feature не импортирует чужой feature" to "io.tima.feature.chat",
            "feature не знает адаптеров сети и базы" to "io.tima.core.network",
        )

        for ((имя, признак) in ожидания) {
            val rule = ArchitectureRules.rules.single { it.name == имя }
            val found = ArchitectureRules.violationsIn(fixtures, rule)
            assertTrue(
                found.any { it.detail.contains(признак) },
                "правило «$имя» не поймало $признак в образце: $found",
            )
        }
    }

    /**
     * **У модуля с Compose обязан быть Android-таргет.**
     *
     * Правило проверяет файлы сборки, а не код, и появилось после падения на живом
     * телефоне. Модуль без `androidTarget()` попадает в Android-приложение **jvm-вариантом**
     * — то есть кодом, собранным против Compose для ПК. Собирается, ставится, запускается;
     * падает на первом же вызове, у которого реализация платформенная: `Path()` на ПК
     * приводит к skiko, и на телефоне это `NoClassDefFoundError: SkiaBackedPath_skikoKt`.
     *
     * Компилятор такого не видит: у Compose Multiplatform имена классов совпадают, и
     * подмена варианта заметна только там, где реализация действительно разная. Вход и
     * список переписок нарисовались, а первый пузырь уронил приложение.
     *
     * Исключение одно — **вход для ПК**: он и есть платформа, Android ему не нужен.
     */
    @Test
    fun у_модуля_с_compose_есть_android_таргет() {
        val файлы = clientRoot.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".kotlin", ".git") }
            .filter { it.isFile && it.name == "build.gradle.kts" }
            // Корневой файл сборки не модуль: он объявляет плагины с `apply false`, то
            // есть только версии, и таргетов у него не бывает вовсе.
            .filter { it.parentFile != clientRoot }
            .toList()

        val нарушители = файлы.filter { файл ->
            val текст = файл.readText()
            val compose = текст.contains("composeMultiplatform")
            val мультиплатформа = текст.contains("kotlinMultiplatform")
            // Вход для ПК — платформенный по определению: у него `compose.desktop`.
            val входДляПК = текст.contains("compose.desktop")
            compose && мультиплатформа && !входДляПК && !текст.contains("androidTarget()")
        }

        assertTrue(
            нарушители.isEmpty(),
            "модули с Compose без androidTarget(): " +
                нарушители.joinToString { it.parentFile.name } +
                ". Android-приложение заберёт их jvm-вариантом и упадёт на первом " +
                "платформенном вызове Compose — проверено живым телефоном",
        )
    }

    @Test
    fun у_каждого_правила_есть_причина() {
        // Правило без объяснения снимают при первом же неудобстве, потому что никто
        // не помнит, что оно защищало.
        val безПричины = ArchitectureRules.rules.filter { it.why.length < 40 }
        assertTrue(безПричины.isEmpty(), "правила без внятной причины: ${безПричины.map { it.name }}")
    }
}
