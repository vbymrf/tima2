package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Второстепенное
import io.tima.core.ui.Имя
import io.tima.core.ui.КнопкаИконка
import io.tima.core.ui.СтрокаСписка
import io.tima.core.ui.Счётчик
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Третьестепенное
import io.tima.core.ui.Тима

/**
 * Подокно «Переключение окон» — единственный видимый способ сменить окно на телефоне.
 *
 * Три решения макета, которые здесь важнее вида:
 *
 * 1. **Панель выезжает снизу, а не разворачивается от логотипа.** До низа экрана палец
 *    дотягивается, до верхнего левого угла — нет. Открывает её при этом верхний левый
 *    угол, и это не противоречие: нажимают редко, а выбирают из списка часто.
 * 2. **У каждого окна вторая строка о том, что внутри.** «Свободное общение» ничего не
 *    говорит человеку, который туда не ходил, — а решение зайти принимается здесь.
 * 3. **Это единственное место, где счётчики всех окон видны разом.** Панели вкладок в
 *    приложении нет, собрать их больше негде (`интерфейс.md §1`).
 *
 * Под панелью остаётся то окно, где человек был: он не ушёл никуда, а приподнял
 * список поверх. Поэтому фон затемняется, а не подменяется.
 */
@Composable
fun ЭкранПереключенияОкон(
    текущее: Окно,
    имя: String,
    псевдоним: String,
    onВыбрать: (Окно) -> Unit,
    onЗакрыть: () -> Unit,
    modifier: Modifier = Modifier,
    /** Непрочитанное по окнам. Нет записи — нет и числа. */
    счётчики: Map<Окно, Int> = emptyMap(),
    onНастройки: (() -> Unit)? = null,
) {
    val цвета = Тима.цвета
    Box(
        modifier = modifier
            .fillMaxSize()
            // Затемнение поверх окна, из которого пришли: человек не ушёл никуда, а
            // приподнял список над тем, где был.
            .background(цвета.текст.copy(alpha = ЗАТЕМНЕНИЕ))
            // Касание вне панели закрывает — то же, что «✕». Оба входа обязаны быть:
            // касание вне угадывают не все, а «✕» ищут глазами.
            .clickable(onClick = onЗакрыть),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = TimaShapes.радиус, topEnd = TimaShapes.радиус))
                .background(цвета.поверхность)
                // Нажатие по самой панели не должно закрывать её вместе с фоном.
                .clickable(enabled = false, onClick = {}),
        ) {
            Шапка(имя, псевдоним, onЗакрыть)

            for (окно in Окно.entries) {
                Пункт(
                    окно = окно,
                    текущее = окно == текущее,
                    сколько = счётчики[окно] ?: 0,
                    onClick = { onВыбрать(окно) },
                )
            }

            if (onНастройки != null) {
                СтрокаСписка(
                    onClick = onНастройки,
                    слева = { Знак("⚙") },
                    середина = { Имя("Настройки, помощь, баги") },
                )
            }

            // Блогерские окна включаются в настройках; пока их нет, заголовок раздела
            // тоже не рисуем: пустой раздел обещает то, чего не существует.
        }
    }
}

@Composable
private fun Шапка(имя: String, псевдоним: String, onЗакрыть: () -> Unit) {
    val цвета = Тима.цвета
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(цвета.функц)
            .padding(horizontal = TimaSpacing.о4, vertical = TimaSpacing.о3),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Знак("Т")
        Column(modifier = Modifier.weight(1f)) {
            Имя("Окна")
            // Кто я — здесь, а не в шапке окна: имя нужно тому, кто выбирает, от чьего
            // лица он сейчас в приложении, а не тому, кто читает переписку.
            Третьестепенное("$имя · $псевдоним", однойСтрокой = true)
        }
        КнопкаИконка(знак = "✕", onClick = onЗакрыть)
    }
}

@Composable
private fun Пункт(окно: Окно, текущее: Boolean, сколько: Int, onClick: () -> Unit) {
    СтрокаСписка(
        onClick = onClick,
        слева = { Знак(окно.знак) },
        справа = { if (сколько > 0) Счётчик(сколько) },
        середина = {
            Column {
                Имя(окно.полное)
                Второстепенное(
                    // Текущее окно называет себя текущим словом, а не только цветом:
                    // цвет здесь один на всё приложение и уже занят навигацией.
                    if (текущее) "${окно.очём} · вы здесь" else окно.очём,
                    однойСтрокой = true,
                )
            }
        },
    )
}

@Composable
private fun Знак(знак: String) {
    val цвета = Тима.цвета
    Box(
        modifier = Modifier
            .background(цвета.акцентМягкий, RoundedCornerShape(TimaShapes.квадратМал))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Имя(знак) }
}

/** Насколько затемняется окно под панелью. Меньше — панель «висит», больше — окно исчезает. */
private const val ЗАТЕМНЕНИЕ = 0.32f
