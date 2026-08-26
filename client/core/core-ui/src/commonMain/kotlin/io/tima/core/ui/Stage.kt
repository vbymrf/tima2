package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/**
 * Стан — ряд полос. Одна разметка на все три формата (У.4).
 *
 * **Формат различается контейнерным запросом**: спрашивается доступная ширина, а не
 * устройство и не окно. На ПК окно приложения бывает узким, на планшете — половиной
 * экрана в разделённом режиме; спрашивать устройство значит однажды получить телефонную
 * раскладку на ПК и наоборот.
 *
 * Экраны про формат не знают ничего. Они отдают четыре слота — рейку, колонку, главную
 * область и панель, — а сколько из них видно, решает ширина:
 *
 * - **телефон**: одна полоса. Главная область не стоит рядом с колонкой, а **заменяет
 *   её**: подокно открывается перерисовкой. Это не «спрятать полосы», а то же самое
 *   поведение, что было в телефонном макете;
 * - **планшет**: рейка значками, колонка списка, главная область;
 * - **ПК**: рейка с подписями, колонка шире, справа страница объекта.
 *
 * Слот `панель` показывается только если ширина его вытерпела. Правило макета — «третья
 * полоса появляется, только когда на неё хватило места», — и вычисляет это [раскладкаДля],
 * а не таблица устройств.
 */
@Composable
fun Stage(
    /** Список: корневое окно целиком — шапка, вкладки, содержимое. На телефоне это экран. */
    column: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Рейка окон. На телефоне переключение окон — подокно, и рейки нет. */
    rail: (@Composable (Layout) -> Unit)? = null,
    /** Подокно: чат, пост, слайд. `null` — ничего не выбрано. */
    main: (@Composable () -> Unit)? = null,
    /** Страница объекта. Показывается только на ПК. */
    panel: (@Composable () -> Unit)? = null,
    /** Что показать в главной области, пока ничего не выбрано. */
    empty: @Composable () -> Unit = { EmptyArea() },
) {
    BoxWithConstraints(modifier) {
        val available = maxWidth
        val layout = layoutFor(available)
        val colors = Tima.colors
        CompositionLocalProvider(LayoutLocal provides layout) {
            if (layout.phone) {
                // Перерисовка, а не полосы: выбранное подокно занимает окно целиком, и
                // пустого состояния на телефоне не бывает вовсе — там список и есть экран.
                Box(Modifier.fillMaxSize()) { (main ?: column)() }
                return@CompositionLocalProvider
            }

            Row(Modifier.fillMaxSize()) {
                layout.rail?.let { width ->
                    Box(
                        Modifier
                            .width(width)
                            .fillMaxHeight()
                            .background(colors.functional)
                            .rightLine(colors.line),
                    ) { rail?.invoke(layout) }
                }

                Box(
                    Modifier
                        .width(layout.column ?: available)
                        .fillMaxHeight()
                        .rightLine(colors.line),
                ) { column() }

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(colors.surface),
                ) { main?.invoke() ?: empty() }

                if (panel != null) {
                    layout.panel?.let { width ->
                        Box(
                            Modifier
                                .width(width)
                                .fillMaxHeight()
                                .background(colors.surface)
                                .leftLine(colors.line),
                        ) { panel() }
                    }
                }
            }
        }
    }
}

/**
 * Содержимое главной области по центру полосы.
 *
 * Поток не растягивается во всю ширину: строка длиной в метр не читается. Предел тот же,
 * что в макете, — [TimaФорматы.ПРЕДЕЛ_СОДЕРЖИМОГО].
 */
@Composable
fun InCenter(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Box(
    modifier = modifier.fillMaxWidth(),
    contentAlignment = Alignment.TopCenter,
) {
    Box(Modifier.widthIn(max = FormatTima.ПРЕДЕЛ_СОДЕРЖИМОГО)) { content() }
}

/**
 * Пустая главная область: пока ничего не выбрано.
 *
 * На телефоне такого состояния нет вовсе, и [Стан] его там не показывает.
 *
 * **Знака по умолчанию нет намеренно.** В первой редакции здесь стоял «✉», и на снимке он
 * вышел пустым прямоугольником: этого глифа нет в шрифте, которым рисует система, а
 * подстановки для него не нашлось. Знак — дело набора значков (К5), и до него лучше
 * пустое место, чем квадратик: пустое место человек не примет за поломку.
 */
@Composable
fun EmptyArea(
    glyph: String? = null,
    title: String = "Ничего не выбрано",
    explanation: String? = null,
    modifier: Modifier = Modifier,
) = Column(
    modifier = modifier.fillMaxSize().padding(TimaSpacing.about6),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2, Alignment.CenterVertically),
) {
    glyph?.let { Caption(it, fontSize = TimaType.sz1, color = Tima.colors.text3) }
    Caption(title, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold)
    explanation?.let { Secondary(it) }
}

/**
 * Зона 3 — гроздь создания — там, где ей место в этом формате.
 *
 * **Одна разметка, разное место.** На телефоне круглые кнопки висят над списком: там они
 * опираются на содержимое, и это нормально. На широком формате они **опускаются** в
 * отдельную область у нижнего края колонки, отделённую линией, — тем же приёмом, что
 * настройки внизу рейки. Круглая кнопка, висящая поверх широкого списка, опирается только
 * на воздух.
 *
 * Кнопки при этом те же самые. Вызывающий передаёт их один раз и не спрашивает про формат.
 */
@Composable
fun WithCluster(
    cluster: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Подпись рядом с кнопками. Появляется только внизу колонки: на телефоне места нет. */
    caption: String? = null,
    /**
     * Второй вход, стоящий отдельно от главного.
     *
     * **Отдельный слот, а не ещё одна кнопка внутри [cluster].** Найдено глазами на ПК
     * 2026-08-26: «Группа», положенная в ту же гроздь, встала вплотную к кругу «Написать»
     * и читалась как часть его — то есть подпись объясняла не ту кнопку. В макете
     * (`Layout-UI-light/пк/телефон.html`, `низ-колонки`) там ровно один круг с подписью.
     *
     * На телефоне гроздь ещё и складывалась: `Box` кладёт детей друг на друга, и два
     * входа оказывались один поверх другого в том же углу.
     */
    secondary: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val layout = LayoutLocal.current
    val colors = Tima.colors
    if (layout.phone) {
        Box(modifier.fillMaxSize()) {
            content()
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(TimaSpacing.about4),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
            ) {
                secondary?.invoke()
                cluster()
            }
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { content() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.functional)
                .topLine(colors.line)
                .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about3),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cluster()
            caption?.let { Caption(it, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colors.text2) }
            // Второй вход уходит к дальнему краю: между ним и подписанным кругом остаётся
            // пустота, и она и есть то, что разделяет два разных действия.
            if (secondary != null) {
                Box(Modifier.weight(1f))
                secondary()
            }
        }
    }
}
