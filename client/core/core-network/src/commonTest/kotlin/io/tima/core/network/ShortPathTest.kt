package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Путь в журнале (ПЛАН-ОТЛАДКИ.md §6, случай 2026-09-06).
 *
 * Первый живой отчёт с телефона содержал строку `PUT <вырезано 27> → 401`: общая чистка
 * журнала честно вырезала идентификатор в пути, и строка стала бесполезной — непонятно,
 * какая ручка отказала. Идентификатор в пути не секрет, а вот имя ручки — половина
 * разбора.
 */
class ShortPathTest {

    @Test
    fun имя_ручки_остаётся_видимым() {
        assertEquals("/api/v1/app/version", shortPath("/api/v1/app/version"))
        assertEquals("/api/v1/problem-reports", shortPath("/api/v1/problem-reports"))
    }

    @Test
    fun идентификатор_заменяется_а_не_вырезается() {
        assertEquals(
            "/api/v1/chats/{id}/state",
            shortPath("/api/v1/chats/7f3a1c02-9b44-4d19-8a51-2c6e0f8b1d77/state"),
        )
    }

    @Test
    fun длинное_слово_без_цифр_не_идентификатор() {
        // Ручка может называться длинно. Превратив её в {id}, мы потеряли бы ровно то,
        // ради чего путь и пишется.
        assertEquals("/api/v1/verylongreadablename", shortPath("/api/v1/verylongreadablename"))
    }
}
