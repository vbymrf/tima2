package io.tima.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Значок раздела — **цветной, как в макете** (решение заказчика 2026-09-18, вечер).
 *
 * **История решения.** Утром того же дня набор был рисованным: двенадцать монохромных
 * значков штрихом на сетке 24, красящихся цветом темы, — ради одинаковости на всех
 * платформах. Вечером заказчик посмотрел на телефоне: «щас они чёрно-белые, не как в
 * макетах — сделай их цветными, как в макетах». В макете
 * (`doc/Layout-UI-light/телефон/разделы.html`) значки — эмодзи: 💼 🏠 📰 🎓 ⚽ 🎬 🛠 📦.
 * Значит, цветные — это они и есть, а не раскрашенные штрихи: раскрашивать двенадцать
 * рисунков вручную значило бы придумывать второй набор, которого в макете нет.
 *
 * **Цена, и она названа.** Эмодзи рисует прошивка, и на разных телефонах они выглядят
 * по-своему; на ПК без шрифта с цветными эмодзи знак может выйти пустым квадратом.
 * Заказчик выбрал вид из макета, зная это.
 *
 * **Рисуется по индексу, а не по перечню домена.** `core-ui` — набор кирпичей и от домена
 * не зависит; состав набора и смысл индексов задаёт `SectionIcon` в `domain-chat`. Два
 * списка на одну правду — риск, и держит их вместе тест в `feature-chat`, который видит
 * оба: у каждого индекса из перечня обязан быть знак.
 *
 * Неизвестный индекс (набор старше клиента) — пустое место: честнее чужого знака.
 *
 * @param color не влияет на эмодзи — цвет у них свой. Оставлен ради вызывающих, которые
 *   красят «выбранный» знак: с эмодзи выбор показывает подложка, а не знак.
 */
@Composable
fun SectionGlyph(
    index: Int,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    @Suppress("UNUSED_PARAMETER") color: Color = Tima.colors.text,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val emoji = sectionEmoji(index) ?: return@Box
        BasicText(
            text = emoji,
            // Знак занимает около 0,8 стороны: эмодзи рисуются с собственным полем, и на
            // полном размере они вылезали бы за квадрат подложки.
            style = TextStyle(fontSize = (size.value * 0.8f).sp, textAlign = TextAlign.Center),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Есть ли знак у индекса. Для теста, сверяющего набор с перечнем домена. */
fun hasSectionGlyph(index: Int): Boolean = sectionEmoji(index) != null

/**
 * Знак по индексу. Номера — те же, что в `SectionIcon`: 1 дом … 16 инструменты.
 * Переставлять нельзя: индексы лежат в базах телефонов. Новый — только в конец.
 *
 * Невидимый `U+FE0F` (VS16) после знаков с текстовым начертанием (✈ ⭐ 🛠) — просьба рисовать цветным.
 */
fun sectionEmoji(index: Int): String? = when (index) {
    1 -> "🏠"
    2 -> "💼"
    3 -> "🎓"
    4 -> "👨‍👩‍👧"
    5 -> "👥"
    6 -> "💰"
    7 -> "💊"
    8 -> "🛒"
    9 -> "✈️"
    10 -> "⚽"
    11 -> "🎵"
    12 -> "⭐️"
    13 -> "📰"
    14 -> "🎬"
    15 -> "📦"
    16 -> "🛠️"
    else -> null
}
