package io.tima.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

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
    /** `true` — тёмная. Значение приходит от платформы, экран его не выбирает. */
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) TimaColors.dark else TimaColors.light
    CompositionLocalProvider(LocalTimaColors provides colors, content = content)
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
