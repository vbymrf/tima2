package io.tima.feature.shell

import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.words.Language
import io.tima.testui.ReferenceFont
import io.tima.testui.capture
import kotlin.test.Test

/**
 * **Обмер, а не проверка.** С какого множителя обрезается текст, который не переносится.
 *
 * В шапке подокна и в строке списка настроек стоит `lineOne = true`: текст не уходит на
 * вторую строку, а **молча обрезается многоточием**. Это ровно та болезнь, из-за которой
 * заведён `RailWidthTest`, — только там её поймали, а здесь нет.
 *
 * Уйдёт вместе с [ScaleSurveyTest], когда пределы будут выбраны.
 */
class CutoffSurveyTest {

    @Test
    fun обмер_обрезки_по_множителям() {
        val ширины = mutableMapOf<String, Int>()
        val строки = строки()
        capture("обмер-обрезки", 200, 60, dark = false) {
            val measurer = rememberTextMeasurer()
            remember {
                for ((ключ, текст) in строки) {
                    val (кегль, вес) = when {
                        ключ.startsWith("шапка") -> TimaType.sz3 to FontWeight.ExtraBold
                        else -> TimaType.sz4 to FontWeight.Bold
                    }
                    for (scale in МНОЖИТЕЛИ) {
                        val style = TextStyle(
                            fontSize = кегль * scale,
                            fontWeight = вес,
                            fontFamily = ReferenceFont.family,
                        )
                        ширины["$ключ|$scale"] =
                            measurer.measure(AnnotatedString(текст), style).size.width
                    }
                }
                0
            }
        }

        println()
        println("── с какого множителя текст перестаёт влезать в строку (360 точек) ──")
        println("   место в строке: шапка ${ШАПКА} точек, настройки ${НАСТРОЙКИ} точек")
        for ((ключ, текст) in строки) {
            val место = if (ключ.startsWith("шапка")) ШАПКА else НАСТРОЙКИ
            val предел = МНОЖИТЕЛИ.firstOrNull { (ширины["$ключ|$it"] ?: 0) > место }
            val ответ = when {
                (ширины["$ключ|1.0"] ?: 0) > место -> "ОБРЕЗАНО УЖЕ при ×1"
                предел == null -> "влезает до ×2"
                else -> "обрезается с ×%.2f".format(предел)
            }
            println("   %-46s %-22s «%s»".format(ключ, ответ, текст.take(34)))
        }
        println()
    }

    @Test
    fun снимки_списка_настроек_при_множителях() {
        // Настоящий экран в телефонных 360, а не расчёт по токенам: расчёт занизил
        // место на сотню точек, и вывод по нему был бы неверным.
        for (scale in listOf(1.0f, 1.3f, 1.5f, 2.0f)) {
            capture("настройки-360-x${(scale * 100).toInt()}", 360, 760, dark = false) {
                io.tima.core.ui.ProvideTextScale(scale) {
                    SettingsScreen(opened = null, onOpen = {}, onBack = {}) { }
                }
            }
        }
    }

    private fun строки(): List<Pair<String, String>> = buildList {
        for (language in Language.entries) {
            val words = language.words ?: continue
            // Самый длинный пункт настроек и самый длинный заголовок подокна: обрежется
            // первым именно он, и решать пределы надо по нему, а не по среднему.
            val пункт = SettingsItem.entries.maxByOrNull { words.settings2.item(it).length } ?: continue
            add("настройки-${language.tag}" to words.settings2.item(пункт))
            add("шапка-${language.tag}" to words.settings2.item(пункт))
        }
    }

    private companion object {
        val МНОЖИТЕЛИ = listOf(1.0f, 1.15f, 1.3f, 1.5f, 1.75f, 2.0f)

        /**
         * Сколько точек остаётся тексту.
         *
         * Шапка: 360 − два поля по `about4` (16) − кнопка «назад» 42 − зазор 12.
         * Настройки: то же минус значок слева и значение справа, примерно 96 точек.
         */
        val ШАПКА = 360 - 2 * TimaSpacing.about4.value.toInt() - 42 - 12
        val НАСТРОЙКИ = ШАПКА - 96
    }
}
