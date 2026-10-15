package io.tima.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Один экземпляр TIMA на ПК (отчёт 2026-10-15: три TIMA на одной базе). Второй «процесс» здесь —
 * второй вызов в той же JVM: замок файла тот же, и упирается он в него так же.
 */
class SingleInstanceTest {

    @Test
    fun второй_запуск_просит_первый_показать_окно_и_уходит() {
        val dir = kotlin.io.path.createTempDirectory("tima-single").toFile()
        assertTrue(SingleInstance.claim(dir, show = true), "первый запуск занимает место")
        val shown = CountDownLatch(1)
        SingleInstance.onShow { shown.countDown() }

        assertFalse(SingleInstance.claim(dir, show = true), "второй запуск уходит")
        assertTrue(shown.await(5, TimeUnit.SECONDS), "первый показал окно")
        assertEquals(1, SingleInstance.asked)

        // Автозагрузка при работающей TIMA окна не просит — просто уходит.
        assertFalse(SingleInstance.claim(dir, show = false))
        assertEquals(1, SingleInstance.asked)
    }
}
