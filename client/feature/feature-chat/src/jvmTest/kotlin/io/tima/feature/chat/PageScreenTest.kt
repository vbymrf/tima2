package io.tima.feature.chat

import androidx.compose.runtime.Composable
import io.tima.domain.chat.PageEntry
import io.tima.testui.bothThemes
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Страница в снимках (ПЛАН-СОЦИУМА Г8, макет `уровень-сообщения.html` раздел 6).
 *
 * Проверяется различие, которое видно только на картинке: **принесённое подписано
 * источником**, а пустая страница отличается от незагруженной. И то и другое в состоянии
 * выглядит одинаково — списком из нуля записей.
 */
class PageScreenTest {

    @Test
    fun принесённое_отличается_от_своего() {
        val carried = bothThemes("страница-принесённое", WIDTH, HEIGHT) { screen(page(carried = true)) }
        val own = bothThemes("страница-своё", WIDTH, HEIGHT) { screen(page(carried = false)) }

        val difference = carried.getValue("светлая").difference(own.getValue("светлая"))
        assertTrue(difference > 0.0, "принесённое выглядит как своё — источник не показан")
    }

    @Test
    fun пустая_страница_и_незагруженная_выглядят_по_разному() {
        val empty = bothThemes("страница-пусто", WIDTH, HEIGHT) { screen(PageState(loaded = true)) }
        val waiting = bothThemes("страница-ждём", WIDTH, HEIGHT) { screen(PageState(loaded = false)) }

        val difference = empty.getValue("светлая").difference(waiting.getValue("светлая"))
        assertTrue(difference > 0.0, "«здесь пусто» и «загружаем» нарисованы одинаково")
    }

    @Test
    fun страница_рисуется_в_обеих_темах() {
        val snapshots = bothThemes("страница-темы", WIDTH, HEIGHT) { screen(page(carried = true)) }
        val difference = snapshots.getValue("светлая").difference(snapshots.getValue("тёмная"))
        assertTrue(difference > 0.10, "темы расходятся лишь на ${(difference * 100).toInt()}% — цвет взят мимо темы")
    }

    private companion object {
        const val WIDTH = 380
        const val HEIGHT = 800

        fun page(carried: Boolean) = PageState(
            loaded = true,
            entries = listOf(
                PageEntry(
                    postId = 1,
                    level = 1,
                    atMs = 1_700_000_000_000,
                    authorId = "u-author",
                    text = "Записи прошлой встречи выложены, смотреть можно без вступления.",
                    carriedBy = if (carried) "u-me" else "",
                    sourceTitle = if (carried) "Ядро" else "",
                    refGroupId = if (carried) "g-1" else "",
                    refMessageId = if (carried) 7 else 0,
                ),
            ),
        )

        @Composable
        fun screen(state: PageState) = PageScreen(state = state, onRemove = {})
    }
}
