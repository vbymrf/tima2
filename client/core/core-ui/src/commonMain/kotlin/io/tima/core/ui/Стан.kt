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
fun Стан(
    /** Список: корневое окно целиком — шапка, вкладки, содержимое. На телефоне это экран. */
    колонка: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Рейка окон. На телефоне переключение окон — подокно, и рейки нет. */
    рейка: (@Composable (Раскладка) -> Unit)? = null,
    /** Подокно: чат, пост, слайд. `null` — ничего не выбрано. */
    главная: (@Composable () -> Unit)? = null,
    /** Страница объекта. Показывается только на ПК. */
    панель: (@Composable () -> Unit)? = null,
    /** Что показать в главной области, пока ничего не выбрано. */
    пусто: @Composable () -> Unit = { ПустаяОбласть() },
) {
    BoxWithConstraints(modifier) {
        val доступно = maxWidth
        val раскладка = раскладкаДля(доступно)
        val цвета = Тима.цвета
        CompositionLocalProvider(LocalРаскладка provides раскладка) {
            if (раскладка.телефон) {
                // Перерисовка, а не полосы: выбранное подокно занимает окно целиком, и
                // пустого состояния на телефоне не бывает вовсе — там список и есть экран.
                Box(Modifier.fillMaxSize()) { (главная ?: колонка)() }
                return@CompositionLocalProvider
            }

            Row(Modifier.fillMaxSize()) {
                раскладка.рейка?.let { ширина ->
                    Box(
                        Modifier
                            .width(ширина)
                            .fillMaxHeight()
                            .background(цвета.функц)
                            .линияСправа(цвета.линия),
                    ) { рейка?.invoke(раскладка) }
                }

                Box(
                    Modifier
                        .width(раскладка.колонка ?: доступно)
                        .fillMaxHeight()
                        .линияСправа(цвета.линия),
                ) { колонка() }

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(цвета.поверхность),
                ) { главная?.invoke() ?: пусто() }

                if (панель != null) {
                    раскладка.панель?.let { ширина ->
                        Box(
                            Modifier
                                .width(ширина)
                                .fillMaxHeight()
                                .background(цвета.поверхность)
                                .линияСлева(цвета.линия),
                        ) { панель() }
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
fun ВЦентре(modifier: Modifier = Modifier, содержимое: @Composable () -> Unit) = Box(
    modifier = modifier.fillMaxWidth(),
    contentAlignment = Alignment.TopCenter,
) {
    Box(Modifier.widthIn(max = TimaФорматы.ПРЕДЕЛ_СОДЕРЖИМОГО)) { содержимое() }
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
fun ПустаяОбласть(
    знак: String? = null,
    заголовок: String = "Ничего не выбрано",
    пояснение: String? = null,
    modifier: Modifier = Modifier,
) = Column(
    modifier = modifier.fillMaxSize().padding(TimaSpacing.о6),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.о2, Alignment.CenterVertically),
) {
    знак?.let { Подпись(it, кегль = TimaType.щ1, цвет = Тима.цвета.текст3) }
    Подпись(заголовок, кегль = TimaType.щ3, вес = FontWeight.ExtraBold)
    пояснение?.let { Второстепенное(it) }
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
fun СГроздью(
    гроздь: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Подпись рядом с кнопками. Появляется только внизу колонки: на телефоне места нет. */
    подпись: String? = null,
    содержимое: @Composable () -> Unit,
) {
    val раскладка = LocalРаскладка.current
    val цвета = Тима.цвета
    if (раскладка.телефон) {
        Box(modifier.fillMaxSize()) {
            содержимое()
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(TimaSpacing.о4),
            ) { гроздь() }
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { содержимое() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(цвета.функц)
                .линияСверху(цвета.линия)
                .padding(horizontal = TimaSpacing.о4, vertical = TimaSpacing.о3),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            гроздь()
            подпись?.let { Подпись(it, кегль = TimaType.щ5, вес = FontWeight.Bold, цвет = цвета.текст2) }
        }
    }
}
