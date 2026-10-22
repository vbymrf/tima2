package io.tima.domain.chat

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Правила приёма — у сервера, но спрашиваются **не чаще раза в сутки** (заказчик 2026-10-22,
 * ПЛАН-(ИФ) ИФ2). Ответ запоминается в настройках устройства вместе со временем: перезапуск не
 * повод спрашивать снова. Сервер не ответил — последние запомненные, а нет и их — числа решений.
 *
 * [fetch] — поля ответа `GET /media/rules` строками; `null` — не ответил.
 */
class CachedMediaRules(
    private val settings: Settings,
    private val fetch: suspend () -> Map<String, String>?,
    private val now: () -> Long,
) : MediaRulesSource {

    private val lock = Mutex()
    private var held: Pair<Long, MediaRules>? = null

    override suspend fun rules(): MediaRules = lock.withLock {
        val t = now()
        held?.let { (at, rules) -> if (t - at in 0 until DAY_MS) return@withLock rules }
        val saved = settings.all().first()
        val savedAt = saved[AT_KEY]?.toLongOrNull()
        val stored = saved[RULES_KEY]?.let(::parse)
        if (stored != null && savedAt != null && t - savedAt in 0 until DAY_MS) {
            held = savedAt to stored
            return@withLock stored
        }
        val answer = fetch()
        if (answer == null) {
            // Не ответил — не спрашивать на каждой отправке: следующая попытка — через сутки после
            // прошлого ответа, а пока последние известные.
            val rules = stored ?: MediaRules()
            held = t to rules
            return@withLock rules
        }
        val text = answer.entries.joinToString("\n") { (k, v) -> "$k=$v" }
        settings.put(RULES_KEY, text)
        settings.put(AT_KEY, t.toString())
        val rules = parse(text) ?: MediaRules()
        held = t to rules
        rules
    }

    companion object {
        const val RULES_KEY = "media.rules"
        const val AT_KEY = "media.rules.at"
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Строки `ключ=значение`; непонятное поле — число решения. */
        fun parse(text: String): MediaRules? {
            val map = text.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            if (map.isEmpty()) return null
            val d = MediaRules()
            return MediaRules(
                fileBytes = map["file_bytes"]?.toLongOrNull()?.takeIf { it > 0 } ?: d.fileBytes,
                selfBytes = map["self_bytes"]?.toLongOrNull()?.takeIf { it > 0 } ?: d.selfBytes,
                photoSide = map["photo_side"]?.toIntOrNull()?.takeIf { it > 0 } ?: d.photoSide,
                maxPhotos = map["max_photos"]?.toIntOrNull()?.takeIf { it > 0 } ?: d.maxPhotos,
                blockedNames = map["blocked_names"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: d.blockedNames,
            )
        }
    }
}
