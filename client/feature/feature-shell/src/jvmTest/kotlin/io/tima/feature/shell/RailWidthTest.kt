package io.tima.feature.shell

import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import io.tima.core.ui.FormatTima
import io.tima.core.ui.TimaType
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ширина рейки выведена из текста, а не выбрана.
 *
 * ── ЗАЧЕМ ЭТА ПРОВЕРКА СУЩЕСТВУЕТ ───────────────────────────────────────────
 *
 * До 2026-09-02 кегли жили в `TimaType`, ширины полос — в `FormatTima`, и друг о друге
 * они не знали. Итог виден на снимке ПК: «Социальн…», «Свободное об…» — подписи окон
 * молча обрезались многоточием. **Молча** здесь ключевое слово: обрезка — штатное
 * поведение `lineOne`, и ни сборка, ни один тест на неё не жаловались.
 *
 * Теперь два числа связаны: рейка обязана вмещать обвязку строки плюс самую длинную
 * подпись, измеренную настоящим измерителем текста тем же кеглем и начертанием, каким
 * она рисуется.
 *
 * ── ЧТО БУДЕТ С ДРУГИМ ЯЗЫКОМ ───────────────────────────────────────────────
 *
 * Этот тест покраснеет. Так и задумано, и это лучший из доступных исходов.
 *
 * Считать ширину рейки по содержимому в бегу — соблазн, от которого отказались: от неё
 * зависит `DESKTOP_THRESHOLD`, то есть появится ли четвёртая полоса. Раскладка, которая
 * меняется от загруженного перевода, ловится хуже красного теста: у одного человека
 * панель есть, у другого нет, и оба правы.
 *
 * Поэтому решение остаётся человеческим — поднять число или укоротить слово, — а тест
 * лишь не даёт принять его молча.
 */
class RailWidthTest {

    @Test
    fun рейка_вмещает_самую_длинную_подпись_окна() {
        val around = FormatTima.CAPTION_RAIL - RAIL_AROUND_CAPTION
        val forCaption = around.value.toInt()

        val widths = mutableMapOf<String, Int>()
        capture("рейка-мера", 200, 60, dark = false) {
            val measurer = rememberTextMeasurer()
            remember {
                // Тот же кегль и то же начертание, каким подпись рисует `Name`.
                val style = TextStyle(fontSize = TimaType.sz4, fontWeight = FontWeight.Bold)
                for (caption in captions) {
                    widths[caption] = measurer.measure(AnnotatedString(caption), style).size.width
                }
                0
            }
        }

        val tight = widths.filterValues { it > forCaption }
        assertTrue(
            tight.isEmpty(),
            "под подпись в рейке остаётся $forCaption точек, а не влезают: " +
                tight.entries.sortedByDescending { it.value }.joinToString { "«${it.key}» — ${it.value}" } +
                ". Поднимите FormatTima.CAPTION_RAIL или укоротите подпись — молча обрезать нельзя",
        )
    }

    /**
     * Запас не бесконечный: рейка шире нужного забирает место у колонки и главной.
     *
     * Порог в полтора раза выбран как «явно с запасом, но не вдвое». Сработает он
     * тогда, когда подписи укоротят, а число забудут вернуть.
     */
    @Test
    fun рейка_не_шире_нужного_в_полтора_раза() {
        val forCaption = (FormatTima.CAPTION_RAIL - RAIL_AROUND_CAPTION).value
        assertTrue(
            forCaption <= LONGEST * 1.5,
            "под подпись отведено $forCaption точек при самой длинной в $LONGEST — " +
                "рейка забирает место у колонки без причины",
        )
    }

    private companion object {
        /**
         * Всё, что рейка печатает подписью: имена окон и «Настройки» внизу.
         *
         * Список собран из [Window] и одной строки рейки, а не переписан руками: новое
         * окно попадёт сюда само и само же проверится.
         */
        val captions: List<String> = Window.entries.map { it.full } + "Настройки"

        /** «Свободное общение», `sz4` полужирный — измерено 2026-09-02. */
        const val LONGEST = 152
    }
}
