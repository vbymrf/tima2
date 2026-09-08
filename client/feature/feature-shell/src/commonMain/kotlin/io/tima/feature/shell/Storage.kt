package io.tima.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import io.tima.core.ui.Button
import io.tima.core.ui.ButtonKind
import io.tima.core.ui.Caption
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.Secondary
import io.tima.core.ui.Tertiary
import io.tima.core.ui.RussianWords
import io.tima.core.ui.StorageWords
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType

/**
 * Чем меряется срок хранения (решение заказчика 2026-09-06).
 *
 * Две единицы, а не свободный ввод дней: «месяц» человек называет сам, а «тридцать дней»
 * ему приходится сначала посчитать. Внутри всё равно дни — мусорщик удаляет по дням.
 */
//
// **Надписи и формы числа лежат в словаре** (ПЛАН-ЯЗЫКА Я2, Я3): перечисление осталось
// ключом и сроком в днях, а слово приходит из `Words.storage`.
enum class KeepUnit(val days: Int) {
    Weeks(7),
    Months(30),
}

/**
 * Сколько держим журнал: единица и число.
 *
 * **Месяц — ровно 30 дней, а не календарный.** Мусорщик считает днями, и «через месяц»
 * обязано значить одно и то же в феврале и в марте.
 */
data class KeepFor(val unit: KeepUnit = KeepUnit.Months, val count: Int = 1) {
    val days: Int get() = unit.days * count

    /** «1 месяц», «3 недели» — то, что человек читает в строке настройки. */
    fun words(words: StorageWords = RussianWords.storage): String =
        words.keepFor(count, unit == KeepUnit.Weeks)
}

/** Пределы журнала: срок и объём. Срабатывает тот, что наступит раньше. */
data class DiaryLimits(
    val keep: KeepFor = KeepFor(),
    /** Предел объёма в мегабайтах: 20, 50 или 100 (решение заказчика). */
    val megabytes: Int = 20,
)

/**
 * «Память и трафик» — что занимает место и когда это убирается.
 *
 * **Три раздела в порядке заказчика: файлы, сообщения, журнал.** Порядок по частоте
 * вопроса, а не по опасности: человек приходит сюда за местом, а место занимают файлы.
 *
 * **Первые два пока не нажимаются, и это осознанно** (решение заказчика 2026-09-06):
 * хранения файлов на устройстве ещё нет вовсе, а для сообщений не решено, что значит
 * «удалить» — стереть насовсем или скрыть с возможностью подтянуть заново. Пункты стоят,
 * чтобы был виден порядок; каждый говорит, чего именно ждёт, — пункт, который не
 * нажимается и не объясняет себя, читается как поломка. План — `doc_mig/ПЛАН-ПАМЯТИ.md`.
 */
@Composable
fun StorageScreen(
    limits: DiaryLimits,
    occupied: Long,
    onLimits: (DiaryLimits) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(
    modifier.fillMaxSize().padding(TimaSpacing.about4).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
) {
    val words = Tima.words.storage
    Caption(words.mediaAndFiles, fontSize = TimaType.sz4, weight = FontWeight.Bold)
    Tertiary(
        words.mediaAndFilesAbout,
    )

    Caption(words.messages, fontSize = TimaType.sz4, weight = FontWeight.Bold)
    Tertiary(
        words.messagesAbout,
    )

    Caption(words.diary, fontSize = TimaType.sz4, weight = FontWeight.Bold)
    Secondary(
        words.diaryAbout,
    )
    ListLine(
        middle = { Name(words.occupies) },
        right = { Secondary(megabytes(occupied)) },
    )

    Caption(words.keep, fontSize = TimaType.sz5, weight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
        KeepUnit.entries.forEach { unit ->
            Button(
                label = if (unit == KeepUnit.Weeks) words.weeks else words.months,
                onClick = { onLimits(limits.copy(keep = limits.keep.copy(unit = unit))) },
                kind = if (limits.keep.unit == unit) ButtonKind.Action else ButtonKind.Quiet,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
        Button(
            label = "−",
            onClick = { onLimits(limits.copy(keep = limits.keep.copy(count = limits.keep.count - 1))) },
            kind = ButtonKind.Quiet,
            enabled = limits.keep.count > 1,
        )
        Button(label = limits.keep.words(words), onClick = {}, enabled = false)
        Button(
            label = "+",
            onClick = { onLimits(limits.copy(keep = limits.keep.copy(count = limits.keep.count + 1))) },
            kind = ButtonKind.Quiet,
            // Год — предел не из осторожности, а из смысла: журнал старше года не
            // объясняет сегодняшнюю поломку, а место занимает.
            enabled = limits.keep.count < MAX_COUNT,
        )
    }
    Tertiary(words.olderThan(limits.keep.words(words)))

    Caption(words.butNoMore, fontSize = TimaType.sz5, weight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
        SIZES.forEach { size ->
            Button(
                label = words.megabytes(size),
                onClick = { onLimits(limits.copy(megabytes = size)) },
                kind = if (limits.megabytes == size) ButtonKind.Action else ButtonKind.Quiet,
            )
        }
    }
    // Два предела нужны оба, и это стоит сказать словами: срок защищает от давности,
    // объём — от одного разговорчивого дня, который сам съест сотню мегабайт.
    Tertiary(words.whicheverFirst)

    Button(label = words.clearDiaryNow, onClick = onClear, kind = ButtonKind.Dangerous)
    Tertiary(
        words.clearDiaryAbout,
    )
}

/** Предел числа: больше года не держим — см. пояснение у кнопки. */
private const val MAX_COUNT = 12

/** Значения объёма — решение заказчика 2026-09-06. */
private val SIZES = listOf(20, 50, 100)

/** Мегабайты с одним знаком: точные байты человеку не говорят ничего. */
private fun megabytes(bytes: Long): String {
    if (bytes < 1024L * 1024) return (bytes / 1024).toString() + " КБ"
    val tenths = (bytes * 10 + 524_288) / 1_048_576
    return (tenths / 10).toString() + "," + (tenths % 10) + " МБ"
}
