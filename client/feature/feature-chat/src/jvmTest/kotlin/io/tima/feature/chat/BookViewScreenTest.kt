package io.tima.feature.chat

import io.tima.core.ui.TimaColors
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Подокно «Вид»: отметки видно, и выбранное отличается цветом.
 *
 * Правка 2026-09-16 по словам заказчика — «плохо видно галочки и точки». До неё знак
 * рисовался `Tertiary`: самый мелкий кегль темы и самый тихий цвет, то есть тем же,
 * чем набирают пояснения ПОД строкой. Отметка же отвечает на единственный вопрос
 * строки — включено или нет, — и обязана читаться первой.
 *
 * Проверяется не «красиво», а два факта, которые глазом ловятся, а сборкой нет:
 * зелёный на экране есть (значит выбранное выделено цветом, а не только формой), и
 * выбранное с невыбранным дают РАЗНЫЕ картинки.
 */
class BookViewScreenTest {

    @Test
    fun выбранная_отметка_зелёная() {
        val shot = capture("вид-отметки", 360, 640, dark = false) {
            BookViewScreen(view = BookView(folders = true, showName = true), onChange = {})
        }
        // Салатовый навигации, а не «подтверждено»: так задано в макете
        // (`.галка.вкл { background: var(--навигация) }`). Зелёных в теме два, и
        // выбирать между ними на глаз нельзя — это решает макет.
        assertTrue(
            shot.has(TimaColors.light.navigation, tolerance = 0.06),
            "салатового на экране нет — выбранное ничем не выделено, кроме формы значка",
        )
    }

    @Test
    fun включённая_и_выключенная_галка_рисуются_по_разному() {
        val on = capture("вид-галка-вкл", 360, 640, dark = false) {
            BookViewScreen(view = BookView(showName = true), onChange = {})
        }
        val off = capture("вид-галка-выкл", 360, 640, dark = false) {
            BookViewScreen(view = BookView(showName = false), onChange = {})
        }
        assertTrue(on.difference(off) > 0.0, "с галкой и без неё экран одинаков — состояние не видно")
    }
}
