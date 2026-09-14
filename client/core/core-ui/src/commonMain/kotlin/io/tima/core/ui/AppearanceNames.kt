package io.tima.core.ui

import io.tima.core.words.AppearanceWords

/**
 * Раскладка «ключ → надпись» для перечней оформления (ПЛАН-ЯЗЫКА, Я-D).
 *
 * Тема, цветовое место и защищаемая пара остаются в `core-ui`: это и правда дизайн-система,
 * а не чужое знание, забредшее сюда ради словаря. Раскладка — здесь же, по общему правилу:
 * её делает тот, чей ключ.
 *
 * [ColorTrouble] сюда попал по второй причине. Фраза о нём собирается из частей —
 * «Знаков 5, а нужно 6», — а части на разных языках ставятся в разном порядке. Поэтому
 * словарь отдаёт готовую фразу по примитивам, а разбор вида беды остаётся там, где цвет
 * и разбирается.
 */

/** Название темы. */
fun AppearanceWords.theme(choice: ThemeChoice): String = when (choice) {
    ThemeChoice.Light -> themeLight
    ThemeChoice.Dark -> themeDark
    ThemeChoice.Custom -> themeCustom
}

/** Название цветового места. */
fun AppearanceWords.slot(slot: ColorSlot): String = slotWords(slot).name

/** Пояснение под названием. Пустое у трёх мест, и это решение, а не пропуск. */
fun AppearanceWords.about(slot: ColorSlot): String = slotWords(slot).about

/** Беда с набранным цветом — фраза целиком, а не склейка на экране. */
fun AppearanceWords.colorTrouble(trouble: ColorTrouble): String = when (trouble) {
    ColorTrouble.Empty -> colorEmpty
    is ColorTrouble.NotHex -> colorNotHex(trouble.listed)
    is ColorTrouble.WrongLength -> colorWrongLength(trouble.length)
}

/** Где видна защищаемая пара — словами, которые человек прочтёт в предупреждении. */
fun AppearanceWords.place(pair: VitalPair): String = when (pair) {
    VitalPair.PLATE -> placePlate
    VitalPair.CONTENT -> placeContent
}

private fun AppearanceWords.slotWords(slot: ColorSlot) = when (slot) {
    ColorSlot.NAVIGATION -> slotNavigation
    ColorSlot.ACTIVITY -> slotActivity
    ColorSlot.CONFIRMED -> slotConfirmed
    ColorSlot.SURFACE -> slotSurface
    ColorSlot.FUNCTIONAL -> slotFunctional
    ColorSlot.TEXT -> slotText
    ColorSlot.TEXT_2 -> slotText2
    ColorSlot.TEXT_3 -> slotText3
    ColorSlot.MY -> slotMy
    ColorSlot.AUTHOR -> slotAuthor
    ColorSlot.BORDER -> slotBorder
    ColorSlot.LINE -> slotLine
    ColorSlot.ON_ACCENT -> slotOnAccent
    ColorSlot.ON_AMBER -> slotOnAmber
    ColorSlot.IN_PLATE -> slotInPlate
    ColorSlot.SOFT_ACCENT -> slotSoftAccent
    ColorSlot.QUIET -> slotQuiet
}
