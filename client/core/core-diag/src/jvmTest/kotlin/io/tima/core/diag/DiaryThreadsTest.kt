package io.tima.core.diag

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Журнал из многих потоков сразу — случай Redmi 2026-09-27.
 *
 * Звонок падал на `ConcurrentModificationException` в `Diary.flush`: главный поток собирал
 * очередь в строку, а поток звонка в этот миг дописывал в неё. Замок в журнале был
 * заглушкой. Здесь несколько потоков пишут и сбрасывают разом — без замка проверка падает
 * на первых же сотнях записей.
 */
class DiaryThreadsTest {

    @Test
    fun запись_из_многих_потоков_не_роняет_и_не_теряет() {
        val written = StringBuffer()
        val diary = Diary(
            now = { System.currentTimeMillis() },
            maxNotes = WRITERS * NOTES,
            files = DiaryFiles(
                days = { emptyList() },
                append = { _, text -> written.append(text) },
                read = { written.toString() },
                remove = {},
                size = { written.length.toLong() },
            ),
            policy = DiaryPolicy(),
        )
        val failures = CopyOnWriteArrayList<Throwable>()
        val start = CountDownLatch(1)
        val writers = (1..WRITERS).map { w ->
            thread {
                start.await()
                runCatching {
                    repeat(NOTES) { n ->
                        // Код у каждой записи свой: одинаковые подряд журнал вправе
                        // свернуть как «работу по кругу», а здесь считаем каждую.
                        diary.note("T$w-$n", "поток $w, запись $n")
                        if (n % 7 == 0) diary.flush()
                    }
                }.onFailure { failures += it }
            }
        }
        start.countDown()
        writers.forEach { it.join() }
        diary.flush()

        assertTrue(failures.isEmpty(), "журнал упал из-под потоков: ${failures.firstOrNull()}")
        assertEquals(WRITERS * NOTES, written.lines().count { it.isNotBlank() }, "до диска дошли не все записи")
    }

    private companion object {
        const val WRITERS = 8
        const val NOTES = 2_000
    }
}
