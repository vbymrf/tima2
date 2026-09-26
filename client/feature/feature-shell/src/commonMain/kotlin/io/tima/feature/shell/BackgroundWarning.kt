package io.tima.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/**
 * Почему звонок может не дойти — одна из трёх бед, по важности (ВЗ0г).
 *
 * Порядок перечисления и есть порядок показа: беда выше закрывает собой ниже. Без
 * уведомлений неважно, выключен ли канал; без канала неважно, душит ли батарея.
 */
enum class BackgroundTrouble { Notices, Calls, Battery }

/**
 * Полоса «звонки не дойдут» — ПЛАН-ВХОДЯЩЕГО-ЗВОНКА.md §7 (заказчик 2026-09-26).
 *
 * @param onFix ведёт в «Настройки → Уведомления» — там все три выключателя разом.
 * @param onLater прячет полосу на неделю; беда, появившаяся заново после исправления,
 *   показывается сразу.
 */
data class BackgroundWarning(
    val trouble: BackgroundTrouble,
    val onFix: () -> Unit,
    val onLater: () -> Unit,
)

/**
 * Полоса — **композиционным местным**, как плашка идущего звонка: окон пять, и протащить
 * её параметром через пять обёрток значило бы однажды забыть шестую.
 */
val LocalBackgroundWarning = compositionLocalOf<BackgroundWarning?> { null }

/**
 * Красная полоса под шапкой окна: что не так и две кнопки.
 *
 * **Красная у всех трёх бед** (заказчик 2026-09-26): каждая означает, что звонок может не
 * дойти, и серым она читалась бы примечанием. Красный в приложении редкий — этот случай
 * его стоит.
 */
@Composable
internal fun BackgroundWarningStrip(warning: BackgroundWarning) {
    val colors = Tima.colors
    val words = Tima.words.settings2
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(BACKGROUND_WARNING_TAG)
            .background(colors.alarm)
            .padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
        verticalArrangement = Arrangement.spacedBy(TimaSpacing.about2),
    ) {
        Caption(
            text = when (warning.trouble) {
                BackgroundTrouble.Notices -> words.warnNotices
                BackgroundTrouble.Calls -> words.warnCalls
                BackgroundTrouble.Battery -> words.warnBattery
            },
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
            color = colors.onAccent,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
            Button(label = words.warnFix, onClick = warning.onFix, kind = ButtonKind.Action)
            Button(label = words.warnLater, onClick = warning.onLater, kind = ButtonKind.Quiet)
        }
    }
}

/** Метка полосы для живых сценариев. */
const val BACKGROUND_WARNING_TAG: String = "background:warning"
