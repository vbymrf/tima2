package io.tima.core.media

/**
 * Пределы раздела папки медиа («Память и трафик → Медиа и другие файлы», Решения §6): держать
 * [keepDays] дней (0 — всегда) и не больше [maxMegabytes]. Срабатывает то, что наступит раньше.
 */
data class MediaLimits(val keepDays: Int, val maxMegabytes: Int) {
    companion object {
        /** Фото вне пузыря — 3 месяца, 1 ГБ (принято 2026-10-16). */
        val PHOTO = MediaLimits(90, 1024)

        /** Файлы вне пузыря — 1 месяц, 1 ГБ. */
        val FILE = MediaLimits(30, 1024)

        /** Голосовые — 1 месяц, 300 МБ (ПЛАН-(ВМ) Р41). */
        val VOICE = MediaLimits(30, 300)

        const val MB = 1024L * 1024
        fun defaultsOf(kind: String) = when (kind) {
            MediaFolder.PHOTO -> PHOTO
            MediaFolder.VOICE -> VOICE
            else -> FILE
        }
    }
}

/** Что убрать из раздела: старше срока, затем самые старые, пока не влезет в предел. */
object MediaSweep {

    fun victims(entries: List<MediaEntry>, limits: MediaLimits, nowMs: Long): List<String> {
        val dayMs = 24L * 60 * 60 * 1000
        val old = if (limits.keepDays > 0) entries.filter { nowMs - it.atMs > limits.keepDays * dayMs } else emptyList()
        val left = (entries - old.toSet()).sortedBy { it.atMs }
        var total = left.sumOf { it.size }
        val cap = limits.maxMegabytes * MediaLimits.MB
        val over = mutableListOf<MediaEntry>()
        for (e in left) {
            if (total <= cap) break
            over += e
            total -= e.size
        }
        return (old + over).map { it.name }
    }

    /** Убрать раздел по пределам; вернуть, сколько файлов убрано. */
    fun sweep(folder: MediaFolder, kind: String, limits: MediaLimits, nowMs: Long): Int {
        val names = victims(folder.entries(kind), limits, nowMs)
        names.forEach { folder.delete(kind, it) }
        return names.size
    }
}
