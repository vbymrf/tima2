package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.ВидЧипа
import io.tima.core.ui.ВЦентре
import io.tima.core.ui.Второстепенное
import io.tima.core.ui.Имя
import io.tima.core.ui.КнопкаИконка
import io.tima.core.ui.LocalРаскладка
import io.tima.core.ui.РядУправления
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Тима
import io.tima.core.ui.Чип
import io.tima.core.ui.ШапкаОкна

/**
 * Каркас основного окна: шапка, ряд вкладок, содержимое.
 *
 * ── ПОЧЕМУ ОДИН КАРКАС НА ВСЕ ОКНА ──────────────────────────────────────────
 *
 * Пять окон устроены одинаково: плашка с именем, кнопки поиска и настроек справа,
 * ряд вкладок под шапкой, дальше содержимое. Пять копий этой разметки разошлись бы
 * молча — в одном окне вкладки под линией, в другом над ней, — и заметили бы это
 * глазами через полгода.
 *
 * **Правила, которые каркас держит за все окна:**
 *
 * - логотип есть только на телефоне: на широком формате он живёт в рейке;
 * - логотип с именем — одна область нажатия, она открывает переключение окон;
 * - «🔍» стоит в шапке **каждого** окна (`§1`), поэтому это не необязательный
 *   параметр, а обязанность вызывающего;
 * - ряд вкладок прокручивается вбок: три коротких имени влезают, а «Коллекции» с
 *   «Подписан» на узком телефоне уже нет.
 */
@Composable
fun КаркасОкна(
    окно: Окно,
    вкладки: List<String>,
    выбрана: String,
    onВкладка: (String) -> Unit,
    onПереключитьОкна: () -> Unit,
    onПоиск: () -> Unit,
    onНастройки: () -> Unit,
    modifier: Modifier = Modifier,
    /** Второй ряд: подвкладки, фильтры, режимы. Есть не у всех окон. */
    второйРяд: (@Composable () -> Unit)? = null,
    содержимое: @Composable () -> Unit,
) {
    val цвета = Тима.цвета
    Column(modifier.fillMaxSize().background(цвета.поверхность)) {
        ШапкаОкна(
            название = окно.краткое,
            логотип = if (LocalРаскладка.current.телефон) "Т" else null,
            onПереключитьОкна = onПереключитьОкна,
            справа = {
                РядУправления {
                    КнопкаИконка(знак = "🔍", onClick = onПоиск)
                    КнопкаИконка(знак = "⚙", onClick = onНастройки)
                }
            },
        )

        РядВкладок(вкладки, выбрана, onВкладка)
        второйРяд?.invoke()

        содержимое()
    }
}

/**
 * Ряд вкладок.
 *
 * Вкладку меняют **касанием**, а не свайпом: горизонталь в содержимом отдана окнам
 * (`§1 «Жесты»`), и отдать её заодно вкладкам нельзя — одно движение получило бы два
 * смысла. Размен осознанный: вкладок три и они под рукой, а окон пять.
 */
@Composable
fun РядВкладок(
    вкладки: List<String>,
    выбрана: String,
    onВкладка: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val цвета = Тима.цвета
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(цвета.функц)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = TimaSpacing.о4, vertical = TimaSpacing.о2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о2),
    ) {
        for (имя in вкладки) {
            Чип(
                надпись = имя,
                // Выбранная вкладка — залитый чип: тот же язык, что у фильтров и
                // эмоций (`§1 «Язык состояний»`), и человеку не приходится учить второй.
                вид = if (имя == выбрана) ВидЧипа.Выбран else ВидЧипа.Тихий,
                onClick = { onВкладка(имя) },
            )
        }
    }
}

/**
 * Честная заглушка вкладки: что здесь будет и почему этого ещё нет.
 *
 * **Пустой экран без слов — брак**, потому что неотличим от поломки: человек видит
 * белое поле и не знает, ждать ли ему чего-то. Поэтому у заглушки две строки — что
 * тут появится и чем оно держится сейчас.
 *
 * Выдуманных записей здесь не бывает по правилу дорожной карты: нарисованные данные
 * однажды уезжают в сборку и принимаются за работающее.
 */
@Composable
fun ЗаглушкаВкладки(
    чтоБудет: String,
    чемДержится: String,
    modifier: Modifier = Modifier,
) = ВЦентре(modifier.fillMaxSize()) {
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.о2),
        modifier = Modifier.padding(TimaSpacing.о5),
    ) {
        Имя(чтоБудет)
        Второстепенное(чемДержится)
    }
}
