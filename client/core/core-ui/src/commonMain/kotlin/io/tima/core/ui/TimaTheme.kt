package io.tima.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Тема приложения — У.1.
 *
 * **Что она даёт и чего не даёт.** Даёт экрану цвета текущей темы, не сообщая, какая
 * она: признак готовности У.1 звучит как «компонент рисуется в двух темах без правки
 * кода экрана», и достигается он тем, что светлого и тёмного варианта в коде экрана
 * не существует.
 *
 * Формы, отступы и кегли темой не меняются, поэтому лежат объектами
 * ([TimaShapes], [TimaSpacing], [TimaType]) и в подстановку не идут: тема — это про
 * значения, а не про геометрию.
 */
@Composable
fun TimaTheme(
    /** `true` — тёмная. Короткий путь для проверок и для двух готовых тем. */
    dark: Boolean = false,
    content: @Composable () -> Unit,
) = TimaTheme(colors = if (dark) TimaColors.dark else TimaColors.light, content = content)

/**
 * Та же тема, но набором цветов.
 *
 * Появилась 2026-09-02 вместе с пользовательской темой: тем стало три, и «светлая или
 * тёмная» перестало быть исчерпывающим вопросом. Экраны от этого не изменились — они
 * по-прежнему не знают, в какой теме рисуются, и это по-прежнему признак готовности У.1.
 */
@Composable
fun TimaTheme(
    colors: TimaColors,
    /**
     * Словарь надписей (ПЛАН-ЯЗЫКА Я1). Умолчание — русский: приложение обязано говорить
     * даже там, где язык ещё не выбран.
     *
     * Раздаётся тем же способом, что цвета, и по той же причине: смена языка на лету —
     * это подмена словаря, а не перезапуск экрана.
     */
    words: Words = RussianWords,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTimaColors provides colors, LocalWords provides words) {
        // **Тема даёт непрозрачный фон, и это не украшение** (находка 2026-09-06).
        //
        // Фон красили только `Stage` и `SettingsScreen`. Всё, что рисуется вместо них —
        // порог обновления, подокно «Обновление не завершилось», переключение окон, —
        // оказывалось прозрачным: кнопки со своей заливкой было видно, а текст читался
        // поверх чужого слоя и выглядел затемнённым. Заказчик увидел это первым.
        //
        // Чинить каждое окно по отдельности значило бы ждать, пока следующее забудут.
        Box(Modifier.fillMaxSize().background(colors.surface)) { content() }
    }
}

/**
 * Цвета текущей темы.
 *
 * `staticCompositionLocalOf`, а не `compositionLocalOf`: тема меняется целиком и
 * редко, и точечная перерисовка читателей здесь стоила бы дороже, чем даёт.
 */
val LocalTimaColors = staticCompositionLocalOf {
    // Светлая по умолчанию — но не «на всякий случай»: без темы компонент рисоваться
    // не должен, и падение здесь было бы честнее. Ошибка вида «забыл обернуть в тему»
    // ловится скриншот-тестом (У.3), а не исключением в проде у человека.
    TimaColors.light
}

/** Короткий доступ: `Тима.цвета.навигация`. */
object Tima {
    val colors: TimaColors
        @Composable @ReadOnlyComposable get() = LocalTimaColors.current
}
