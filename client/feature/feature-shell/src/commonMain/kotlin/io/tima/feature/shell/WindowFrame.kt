package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.tima.core.ui.ChipKind
import io.tima.core.ui.InCenter
import io.tima.core.ui.Secondary
import io.tima.core.ui.Name
import io.tima.core.ui.IconButton
import io.tima.core.ui.LayoutLocal
import io.tima.core.ui.ControlRow
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Tima
import io.tima.core.ui.Chip
import io.tima.core.ui.WindowHeader
import io.tima.core.ui.bottomLine

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
 * - логотип есть на всех форматах. До 2026-09-02 он зависел от ширины — макет ПК
 *   отдаёт «Т» шапке рейки, — и на настольной сборке буквы не было вовсе. Решение
 *   заказчика: «на ПК он там же должен быть, вставить Т в плашку шапки»;
 * - вся плашка — одна область нажатия, она открывает переключение окон;
 * - «🔍» стоит в шапке **каждого** окна (`§1`), поэтому это не необязательный
 *   параметр, а обязанность вызывающего;
 * - **шапка, вкладки и второй ряд — один серый блок с одной линией снизу.** Линию
 *   рисует каркас, а не составные части: линия у каждой части давала бы их две или
 *   три, смотря сколько рядов сегодня есть у окна;
 * - **ряды переносятся на новую строку, а не прокручиваются.** Прокрутка без видимого
 *   признака прячет: на колонке ПК в 340 точек «Каталог» исчезал за краем и выглядел
 *   несуществующим.
 */
@Composable
fun WindowFrame(
    window: Window,
    tabs: List<String>,
    selected: String,
    onTab: (String) -> Unit,
    onSwitchWindows: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Хвост ряда вкладок: переключатель режима **всего окна**.
     *
     * Отличается от [secondRow] тем, к чему относится. «Лента / Слайды» — состояние
     * окна 3: оно переживает смену вкладки, и стоять ему поэтому в одном ряду с
     * вкладками, а не под ними среди фильтров вкладки.
     */
    tabsTrailing: (@Composable () -> Unit)? = null,
    /** Второй ряд: подвкладки, фильтры, режимы вкладки. Есть не у всех окон. */
    secondRow: (@Composable () -> Unit)? = null,
    /**
     * Соседние окна: свайп по средней зоне содержимого.
     *
     * `null` — свайпа нет. Так на широких форматах: там окна меняют рейкой, и
     * горизонталь содержимого принадлежит содержимому целиком.
     */
    onNeighbourWindow: ((InSide) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = Tima.colors
    Column(modifier.fillMaxSize().background(colors.surface)) {
        // Один блок управления: шапка, вкладки, второй ряд. Линия у него одна, снизу,
        // независимо от того, есть сегодня второй ряд или нет.
        //
        // **Фон блок НЕ красит**, хотя соблазн был: его красят сами ряды. В тёмной теме
        // `functional` полупрозрачен, и вторая заливка поверх первой давала 0,135
        // вместо 0,07 — шапка выходила заметно светлее вкладок под ней, то есть ровно
        // тем «двумя разными фонами», от которых уходили. Поймал это снимок.
        Column(Modifier.bottomLine(colors.line)) {
            WindowHeader(
                title = window.short,
                logo = "Т",
                onSwitchWindows = onSwitchWindows,
                right = {
                    ControlRow {
                        IconButton(glyph = "🔍", onClick = onSearch)
                        IconButton(glyph = "⚙", onClick = onSettings)
                    }
                },
            )

            TabRow(tabs, selected, onTab, trailing = tabsTrailing)
            secondRow?.invoke()
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .windowSwipe(
                    onLeft = { onNeighbourWindow?.invoke(InSide.Next) },
                    onRight = { onNeighbourWindow?.invoke(InSide.Previous) },
                    // На широком формате окна меняют рейкой, и горизонталь содержимого
                    // принадлежит содержимому целиком. Правило живёт здесь, а не у
                    // каждого вызывающего: пять копий одного условия разошлись бы.
                    enabled = onNeighbourWindow != null && LayoutLocal.current.phone,
                ),
        ) { content() }
    }
}

/**
 * Куда ведёт свайп.
 *
 * Названо по порядку окон, а не по стороне движения: палец идёт влево, а окно
 * приходит следующее — и путать эти два направления в вызывающем коде дороже, чем
 * завести перечень из двух слов.
 */
enum class InSide { Previous, Next }

/**
 * Ряд вкладок.
 *
 * Вкладку меняют **касанием**, а не свайпом: горизонталь в содержимом отдана окнам
 * (`§1 «Жесты»`), и отдать её заодно вкладкам нельзя — одно движение получило бы два
 * смысла. Размен осознанный: вкладок три и они под рукой, а окон пять.
 *
 * **Переносится на новую строку**, а не прокручивается: четыре вкладки окна 5 вместе с
 * переключателем режима в колонку ПК одной строкой не помещаются, а прокрутка без
 * видимого признака прячет то, что за краем.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabRow(
    tabs: List<String>,
    selected: String,
    onTab: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Хвост ряда: переключатель режима окна. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Tima.colors
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.functional)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        for (name in tabs) {
            Chip(
                label = name,
                // Выбранная вкладка — залитый чип: тот же язык, что у фильтров и
                // эмоций (`§1 «Язык состояний»`), и человеку не приходится учить второй.
                kind = if (name == selected) ChipKind.Selected else ChipKind.Quiet,
                onClick = { onTab(name) },
            )
        }
        trailing?.invoke()
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
fun TabStub(
    willWhat: String,
    thanHolds: String,
    modifier: Modifier = Modifier,
) = InCenter(modifier.fillMaxSize()) {
    Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
        modifier = Modifier.padding(TimaSpacing.about5),
    ) {
        Name(willWhat)
        Secondary(thanHolds)
    }
}
