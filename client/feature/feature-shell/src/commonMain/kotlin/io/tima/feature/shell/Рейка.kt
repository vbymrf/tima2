package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.tima.core.ui.Имя
import io.tima.core.ui.Раскладка
import io.tima.core.ui.Счётчик
import io.tima.core.ui.TimaShapes
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.Тима

/**
 * Рейка окон — то, чем на широких форматах заменяется подокно «Переключение окон».
 *
 * **Переключателя там нет вовсе** (`интерфейс.md §1 «Три формата»`): полоса слева
 * всегда на виду, и открывать поверх неё панель значило бы прятать список за списком.
 *
 * Разница планшета и ПК ровно одна: на планшете только знаки, на ПК знаки с подписями.
 * Решает это [Раскладка], а не отдельный флаг — экран про устройство не спрашивает.
 *
 * Счётчики непрочитанного переезжают сюда: это то самое «единственное место, где они
 * видны разом», просто на широком формате оно всегда открыто.
 */
@Composable
fun Рейка(
    раскладка: Раскладка,
    текущее: Окно,
    onВыбрать: (Окно) -> Unit,
    modifier: Modifier = Modifier,
    счётчики: Map<Окно, Int> = emptyMap(),
    onНастройки: (() -> Unit)? = null,
) {
    val цвета = Тима.цвета
    val сПодписями = раскладка.подписиРейки
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(цвета.функц)
            .padding(vertical = TimaSpacing.о3, horizontal = TimaSpacing.о2),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.о2),
    ) {
        for (окно in Окно.entries) {
            Пункт(
                окно = окно,
                выбрано = окно == текущее,
                сколько = счётчики[окно] ?: 0,
                сПодписью = сПодписями,
                onClick = { onВыбрать(окно) },
            )
        }

        // Настройки внизу — так в макете, и это не только вид: то, чем пользуются
        // редко, не должно стоять на пути к тому, чем пользуются постоянно.
        Spacer(Modifier.weight(1f))
        if (onНастройки != null) {
            Пункт(
                знак = "⚙",
                подпись = "Настройки",
                выбрано = false,
                сколько = 0,
                сПодписью = сПодписями,
                onClick = onНастройки,
            )
        }
    }
}

@Composable
private fun Пункт(
    окно: Окно,
    выбрано: Boolean,
    сколько: Int,
    сПодписью: Boolean,
    onClick: () -> Unit,
) = Пункт(окно.знак, окно.полное, выбрано, сколько, сПодписью, onClick)

@Composable
private fun Пункт(
    знак: String,
    подпись: String,
    выбрано: Boolean,
    сколько: Int,
    сПодписью: Boolean,
    onClick: () -> Unit,
) {
    val цвета = Тима.цвета
    Row(
        modifier = Modifier
            .then(if (сПодписью) Modifier.fillMaxWidth() else Modifier)
            .background(
                if (выбрано) цвета.навигация else цвета.функц,
                RoundedCornerShape(TimaShapes.квадратМал),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = TimaSpacing.о3, vertical = TimaSpacing.о2),
        horizontalArrangement = Arrangement.spacedBy(TimaSpacing.о2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) { Имя(знак) }
        if (сПодписью) {
            Имя(подпись, modifier = Modifier.weight(1f))
        }
        if (сколько > 0) Счётчик(сколько)
    }
}
