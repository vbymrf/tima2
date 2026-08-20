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
        val found = ArchitectureRules.violationsIn(fixtures, domainRule)

        assertEquals(
            1,
            found.size,
            "Ожидалось ровно одно нарушение в образце, найдено ${found.size}: $found",
        )
        assertTrue(found.single().detail.contains("io.ktor"), "не тот импорт: ${found.single()}")
    }

    @Test
    fun у_каждого_правила_есть_причина() {
        // Правило без объяснения снимают при первом же неудобстве, потому что никто
        // не помнит, что оно защищало.
        val безПричины = ArchitectureRules.rules.filter { it.why.length < 40 }
        assertTrue(безПричины.isEmpty(), "правила без внятной причины: ${безПричины.map { it.name }}")
    }
}
