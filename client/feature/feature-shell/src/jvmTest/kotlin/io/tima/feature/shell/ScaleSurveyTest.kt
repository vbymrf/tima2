package io.tima.feature.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import io.tima.core.ui.LocalWords
import io.tima.core.ui.ProvideTextScale
import io.tima.core.ui.TimaColors
import io.tima.core.words.Language
import io.tima.testui.FOREIGN_BACKGROUND
import io.tima.testui.Snapshot
import io.tima.testui.capture
import kotlin.test.Test

/**
 * **Обмер, а не проверка.** Печатает таблицу: что переносится и на каком множителе.
 *
 * Заведён 2026-09-16, чтобы пределы читаемости выбирались по числам, а не на глаз.
 * Ничего не утверждает и всегда зелёный; уйдёт, когда пределы будут выбраны и станут
 * утверждениями в [RowFitTest].
 */
class ScaleSurveyTest {

    @Test
    fun обмер_переносов_по_множителям() {
        println()
        println("── ряды окон: с какого множителя переносятся (360 точек) ──")
        for (language in Language.entries) {
            val words = language.words ?: continue
            println("  язык ${language.tag}:")
            for ((имя, ряд) in RowFitTest.ROWS) {
                val порог = МНОЖИТЕЛИ.firstOrNull { переносится(имя, ряд, words.let { it }, it) }
                val было = if (переносится(имя, ряд, words, 1.0f)) "УЖЕ при ×1" else
                    порог?.let { "с ×%.2f".format(it) } ?: "не переносится до ×2"
                println("     %-22s %s".format(имя, было))
            }
        }
        println()
    }

    private fun переносится(
        имя: String,
        ряд: @Composable () -> Unit,
        words: io.tima.core.words.Words,
        scale: Float,
    ): Boolean {
        fun снять(ширина: Int, метка: String) = capture(
            "обмер-$имя-$метка-${(scale * 100).toInt()}", ширина, 300, dark = false, backdrop = FOREIGN_BACKGROUND,
        ) {
            CompositionLocalProvider(LocalWords provides words) {
                ProvideTextScale(scale) { Box(Modifier.fillMaxSize()) { ряд() } }
            }
        }
        return полоса(снять(PHONE, "узкий")) > полоса(снять(WIDE, "широкий"))
    }

    /** Высота серой полосы ряда — как в [RowFitTest]. */
    private fun полоса(snapshot: Snapshot): Int {
        var last = 0
        for (y in 0 until snapshot.height) {
            if (Snapshot.close(snapshot.color(2, y), TimaColors.light.functional)) last = y + 1
        }
        return last
    }

    private companion object {
        const val PHONE = 360
        const val WIDE = 900
        val МНОЖИТЕЛИ = listOf(1.0f, 1.15f, 1.3f, 1.5f, 1.75f, 2.0f)
    }
}
