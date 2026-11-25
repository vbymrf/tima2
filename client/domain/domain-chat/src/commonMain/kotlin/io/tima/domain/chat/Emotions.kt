package io.tima.domain.chat

/**
 * Шкала «Оценки» (ПЛАН-(СЦ) §1.18; макет `doc/Layout-UI-light/телефон/подокна/оценка.html`): восемь эмоций четырьмя
 * осями и покой. Это не набор наклеек, а шкала: на оси положительное слева, отрицательное справа; «Спокойствие» —
 * посередине, без пары. Выбрать можно одну; сервер хранит ключ, знак рисует приложение.
 *
 * [axis] — номер оси сверху вниз (0…4), [positive] — левый столбец; у покоя — `null`.
 */
enum class Emotion(val key: String, val sign: String, val axis: Int, val positive: Boolean?) {
    APPROVAL("approval", "👍", 0, true),
    CONTEMPT("contempt", "🙄", 0, false),
    LAUGHTER("laughter", "😂", 1, true),
    PAIN("pain", "😖", 1, false),
    RAGE("rage", "👊", 2, true),
    FEAR("fear", "😨", 2, false),
    INTEREST("interest", "🔍", 3, true),
    BOREDOM("boredom", "😑", 3, false),
    CALM("calm", "🧘", 4, null),
    ;

    companion object {
        fun of(key: String?): Emotion? = entries.firstOrNull { it.key == key }

        /** Сколько осей у шкалы. */
        const val AXES = 5
    }
}

/** Сколько человек поставили эмоцию [emotion] (ключ шкалы). */
data class EmotionCount(val emotion: String, val count: Int)

/** Эмоции сообщения: счёт по убыванию и своя. */
data class Reactions(val counts: List<EmotionCount> = emptyList(), val mine: String? = null) {
    /** Своя эмоция сменилась на [next] (`null` — снята): прежняя минус один, новая плюс один — как ответит сервер. */
    fun with(next: String?): Reactions {
        val after = counts.map { c -> if (c.emotion == mine) c.copy(count = c.count - 1) else c }.toMutableList()
        if (next != null) {
            val at = after.indexOfFirst { it.emotion == next }
            if (at >= 0) after[at] = after[at].copy(count = after[at].count + 1) else after += EmotionCount(next, 1)
        }
        return Reactions(after.filter { it.count > 0 }.sortedByDescending { it.count }, next)
    }
}

/** Порт эмоций, пальцев и комментариев «Описания» (ПЛАН-(СЦ) §1.18, СЦ37–СЦ40). */
interface Reacting {
    /** Своя эмоция у записи канала или сообщения уровня 0; `null` — снять. Ответ — эмоции после; `null` — не вышло. */
    suspend fun react(kind: String, entityId: String, messageId: Long, emotion: String?): Reactions?

    /** Свой палец у записи канала: 1, −1, 0 — снять. `false` — не вышло. */
    suspend fun vote(channelId: String, postId: Long, vote: Int): Boolean
}

/** Настройки «Описания» сущности (§1.18, 11): целиком или обрезкой, комментарии к сообщениям уровня 0. */
data class ShowcaseSettings(val whole: Boolean = false, val comments: Boolean = false)
