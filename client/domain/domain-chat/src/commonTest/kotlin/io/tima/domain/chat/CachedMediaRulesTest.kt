package io.tima.domain.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Правила приёма (ПЛАН-(ИФ) ИФ2): сервер спрашивается не чаще раза в сутки, ответ переживает перезапуск. */
class CachedMediaRulesTest {

    private class MemorySettings : Settings {
        val map = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun all(): Flow<Map<String, String>> = map
        override suspend fun put(name: String, value: String) {
            map.value = map.value + (name to value)
        }
    }

    private val answer = mapOf("file_bytes" to "5242880", "self_bytes" to "52428800", "photo_side" to "1600", "max_photos" to "4", "blocked_names" to ".exe,.svg")

    @Test
    fun сервер_спрашивается_раз_в_сутки_и_после_перезапуска_не_спрашивается() = runTest {
        val settings = MemorySettings()
        var asked = 0
        var now = 1_000L
        val rules = CachedMediaRules(settings, { asked++; answer }, { now })
        val first = rules.rules()
        assertEquals(1600, first.photoSide)
        assertEquals(4, first.maxPhotos)
        assertEquals(5L * 1024 * 1024, first.fileBytes)
        assertTrue(first.blocked("Вирус.EXE"))
        now += CachedMediaRules.DAY_MS - 1
        rules.rules()
        assertEquals(1, asked, "в те же сутки — без запроса")
        val restarted = CachedMediaRules(settings, { asked++; answer }, { now })
        assertEquals(1600, restarted.rules().photoSide)
        assertEquals(1, asked, "перезапуск — запомненное, без запроса")
        now += 2
        rules.rules()
        assertEquals(2, asked, "сутки прошли — спросить снова")
    }

    @Test
    fun сервер_молчит_числа_решений_и_без_запроса_на_каждой_отправке() = runTest {
        var asked = 0
        val rules = CachedMediaRules(MemorySettings(), { asked++; null }, { 5L })
        assertEquals(MediaRules(), rules.rules())
        rules.rules()
        assertEquals(1, asked)
    }
}
