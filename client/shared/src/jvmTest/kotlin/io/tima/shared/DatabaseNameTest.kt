package io.tima.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Имя базы для аккаунта (ПЛАН-КОНТАКТОВ.md, Д11).
 *
 * Проверяется одно, но важное: **первый аккаунт сохраняет прежнее имя**. Ошибка здесь не
 * ломает сборку и не роняет приложение — она открывает пустую базу, и человек видит, что
 * вся переписка исчезла, хотя файл лежит рядом.
 */
class DatabaseNameTest {

    private val первый = "11111111-2222-3333-4444-555555555555"
    private val второй = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    @Test
    fun первый_аккаунт_остаётся_в_прежнем_файле() {
        assertEquals(LEGACY, databaseFor(первый, first = первый))
    }

    @Test
    fun остальные_получают_свой_файл() {
        val имя = databaseFor(второй, first = первый)
        assertTrue(имя != LEGACY, "второй аккаунт открыл базу первого")
        assertTrue(имя.contains(второй), "имя не называет аккаунт: $имя")
        assertTrue(имя.endsWith(".db"))
    }

    @Test
    fun без_первого_аккаунта_имя_всё_равно_своё() {
        // Так выглядит первый запуск после установки: списка ещё нет, и «прежнего»
        // файла тоже — терять нечего.
        assertEquals("tima-$первый.db", databaseFor(первый))
    }

    @Test
    fun чужие_знаки_в_имя_файла_не_попадают() {
        // Идентификатор приходит от сервера, а из него получается путь. Точки и слэши
        // в нём — это способ написать не туда.
        val имя = databaseFor("../../etc/passwd", first = null)
        assertTrue(!имя.contains("/") && !имя.contains(".."), "путь пролез в имя файла: $имя")
    }

    @Test
    fun у_двух_аккаунтов_имена_разные() {
        assertTrue(databaseFor(первый) != databaseFor(второй))
    }
}
