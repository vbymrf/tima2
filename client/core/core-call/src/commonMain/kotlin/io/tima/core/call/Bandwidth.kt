package io.tima.core.call

/**
 * Полоса звонка — правила ПЛАН-(ПЗ)-ПОЛОСЫ-ЗВОНКА.md (решения заказчика 2026-10-15).
 *
 * Здесь только счёт: что сказать собеседнику, какой битрейт поставить, выключить ли своё видео,
 * когда переподписаться. Платформы подают числа из статистики WebRTC и исполняют ответ — правила
 * одни на Android и ПК, и проверяются тестами без телефона.
 *
 * Общее для всех правил — **среднее за 10 с**: «Есть проблема: выключим, когда падение было
 * кратковременным. Замеры нужно делать в течение 10 секунд и среднее считать. Как для решения
 * выключить, так и включить» (заказчик).
 */

/** Окно усреднения: 10 с (ПЗ-В1, ПЗ-В9). */
const val BAND_WINDOW_MS = 10_000L

/** Сколько в окне должно набраться, чтобы среднему верить: опрос раз в 3 с даёт 3–4 замера. */
const val BAND_WINDOW_FULL_MS = 6_000L

/** Запас на звук, кбит/с: «+50 на звук ещё нужно» (заказчик). */
const val AUDIO_KBPS = 50

/**
 * Среднее за последние [spanMs]. `null`, пока окно не набралось: решение по одному-двум
 * замерам и есть «выключили по кратковременному падению».
 */
class BandWindow(private val spanMs: Long = BAND_WINDOW_MS, private val fullMs: Long = BAND_WINDOW_FULL_MS) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(atMs: Long, kbps: Double) {
        samples.addLast(atMs to kbps)
        while (samples.isNotEmpty() && samples.first().first < atMs - spanMs) samples.removeFirst()
    }

    fun clear() = samples.clear()

    fun average(nowMs: Long): Double? {
        val live = samples.filter { it.first >= nowMs - spanMs }
        if (live.isEmpty()) return null
        if (nowMs - live.first().first < fullMs) return null
        return live.sumOf { it.second } / live.size
    }
}

/**
 * «Сколько ко мне пролезает» — атрибутом участника LiveKit, как [VideoPause] (ПЗ7). Идёт по
 * служебному каналу, а не по видео, поэтому доходит и тогда, когда видео стоит. Число — кбит/с
 * на всё (видео и звук); пусто — ограничения нет.
 */
object ReceiveLimit {
    const val ATTRIBUTE = "tima.recv"

    fun write(kbps: Int?): String = kbps?.toString().orEmpty()

    fun read(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it > 0 }
}

/**
 * ПЗ7, получатель звонка на двоих: что сказать собеседнику о своём приёме.
 *
 * - **вниз:** среднее за 10 с упало на 20 % от сказанного (а пока не сказано ничего — ниже
 *   потолка с запасом на звук) или сервер поставил видео на паузу — сказать сразу;
 * - **вверх:** не чаще раза в 10 с и только при росте на 30 %;
 * - приём с запасом над потолком — ограничение снять (пустой атрибут);
 * - число с округлением до 50 — атрибут не дёргается на каждом колебании.
 *
 * Пороги подтверждены заказчиком (ПЗ-В6, ПЗ-В9).
 *
 * @param ceilingKbps потолок видео, который сервер дал звонку. У звонка на двоих он один на обе
 *   стороны — свой и есть собеседника.
 */
class ReceiveTeller(private val ceilingKbps: Int) {
    /** Что сказано сейчас: `null` — ограничения нет. */
    var told: Int? = null
        private set
    private var changedAt = Long.MIN_VALUE / 2
    private var wasPaused = false

    /** Ответ: новое значение атрибута. `null` — ничего не менять. */
    class Change(val kbps: Int?)

    /**
     * @param estimate среднее за 10 с оценки приёма WebRTC (`availableIncomingBitrate`), кбит/с;
     *   `null` — оценки нет или окно не набралось.
     * @param serverPaused сервер держит видео собеседника на паузе.
     * @param incoming среднее за 10 с того, что приходило на самом деле (видео и звук) — запасная
     *   мера при паузе, когда оценки нет.
     */
    fun next(nowMs: Long, estimate: Double?, serverPaused: Boolean, incoming: Double?): Change? {
        val pausedNow = serverPaused && !wasPaused
        wasPaused = serverPaused
        val full = ceilingKbps + AUDIO_KBPS
        if (pausedNow) {
            // Пауза сервера — признак, что сказанного мало, даже если оценка говорит иное: шаг вниз
            // минимум на 20 % от сказанного.
            val base = told ?: full
            val guess = listOfNotNull(estimate, incoming).minOrNull()
            val kbps = round50(minOf(guess ?: Double.MAX_VALUE, base * DOWN_SHARE))
            return set(nowMs, kbps)
        }
        val e = estimate ?: return null
        val now = told
        if (now == null) {
            if (e < full) return set(nowMs, round50(e))
            return null
        }
        if (e <= now * DOWN_SHARE) return set(nowMs, round50(e))
        if (nowMs - changedAt < UP_EVERY_MS) return null
        if (e >= full * CLEAR_SHARE) return set(nowMs, null)
        if (e >= now * UP_SHARE) return set(nowMs, round50(minOf(e, full.toDouble())))
        return null
    }

    private fun set(nowMs: Long, kbps: Int?): Change? {
        if (kbps == told) return null
        told = kbps
        changedAt = nowMs
        return Change(kbps)
    }

    private fun round50(kbps: Double): Int = maxOf(MIN_TOLD_KBPS, (kbps / 50).toInt() * 50)

    companion object {
        const val DOWN_SHARE = 0.8
        const val UP_SHARE = 1.3
        /** Приём на 20 % выше потолка с запасом на звук — ограничения нет. */
        const val CLEAR_SHARE = 1.2
        const val UP_EVERY_MS = 10_000L
        /** Ниже не говорим: WebRTC держит и 100 кбит/с на видео, но ноль — это не предел, а беда. */
        const val MIN_TOLD_KBPS = 100
    }
}

/**
 * ПЗ7, отправитель звонка на двоих: битрейт видео по слову собеседника.
 *
 * Сказал «пролезает X» — видео сразу min(потолок, X − 50 на звук). Сказанное снято — к потолку
 * ступенями: +25 % раз в 10 с (ПЗ-В6). Своего выключения видео здесь нет — «логику не меняем,
 * … выключиться так же, как выключается сейчас» (ПЗ-В5).
 */
class SendLimit(private val ceilingKbps: Int) {
    /** Битрейт видео сейчас, кбит/с. */
    var current: Int = ceilingKbps
        private set
    private var steppedAt = Long.MIN_VALUE / 2

    /** Новый битрейт видео или `null` — оставить. */
    fun next(nowMs: Long, peerTold: Int?): Int? {
        if (peerTold != null) {
            val want = (peerTold - AUDIO_KBPS).coerceIn(MIN_VIDEO_KBPS, ceilingKbps)
            if (want == current) return null
            current = want
            steppedAt = nowMs
            return want
        }
        if (current >= ceilingKbps) return null
        if (nowMs - steppedAt < STEP_EVERY_MS) return null
        current = minOf(ceilingKbps, (current * STEP_UP).toInt())
        steppedAt = nowMs
        return current
    }

    companion object {
        const val STEP_UP = 1.25
        const val STEP_EVERY_MS = 10_000L
        const val MIN_VIDEO_KBPS = 50
    }
}

/**
 * Что приходит к нам в групповом звонке — среднее за 10 с (ПЗ4). Считает движок, решает
 * [OwnVideoGuard].
 *
 * @param videoKbps всё приходящее видео — сумма по участникам, которых мы смотрим и которые его
 *   шлют. `null` — окно не набралось.
 * @param allKbps всё приходящее, со звуком: что прошло через соединение приёма.
 * @param videoArriving хоть один участник шлёт видео (камера включена, не свёрнут, не «слабая
 *   связь»). Пауза сервера сюда не входит: она и есть узкий канал.
 * @param measurable мерить можно: мы принимаем видео (не «скрыть») и окно набралось.
 */
data class IncomingBand(
    val videoKbps: Double?,
    val allKbps: Double?,
    val videoArriving: Boolean,
    val measurable: Boolean,
)

/**
 * ПЗ4, групповой: своё видео выключить, когда входящее видео за 10 с в среднем ниже 150 кбит/с,
 * и включить, когда не ниже 250 (ПЗ-В1, ПЗ-В1а). Пробных включений нет: чужое видео приходит и
 * тогда, когда своё стоит, — мерить можно всё время.
 *
 * Видео ни у кого нет — меряется всё входящее, то есть звук: меньше 250 — «оснований для
 * включения нет» (ПЗ-В1б). Выключать по одному звуку не нужно — решение не меняется.
 */
object OwnVideoGuard {
    const val OFF_BELOW_KBPS = 150.0
    const val ON_FROM_KBPS = 250.0

    enum class Decision { Pause, Resume }

    fun decide(pausedByUs: Boolean, band: IncomingBand?): Decision? {
        if (band == null || !band.measurable) return null
        return if (!pausedByUs) {
            val video = band.videoKbps ?: return null
            if (band.videoArriving && video < OFF_BELOW_KBPS) Decision.Pause else null
        } else if (band.videoArriving) {
            val video = band.videoKbps ?: return null
            if (video >= ON_FROM_KBPS) Decision.Resume else null
        } else {
            val all = band.allKbps ?: return null
            if (all >= ON_FROM_KBPS) Decision.Resume else null
        }
    }
}

/**
 * ПЗ5, групповой: повторная подача видео, которое сервер держит на паузе, — отписаться и
 * подписаться снова. Через 15, 30, 60 с, дальше раз в 60 с (ПЗ-В7). Вернулось — счёт сначала.
 */
class Resubscribe {
    private class Paused(var since: Long, var attempts: Int, var nextAt: Long)

    private val paused = HashMap<String, Paused>()

    fun paused(key: String, nowMs: Long) {
        if (key !in paused) paused[key] = Paused(nowMs, 0, nowMs + STEPS_MS[0])
    }

    fun resumed(key: String) {
        paused.remove(key)
    }

    fun forget(keep: Set<String>) {
        paused.keys.retainAll(keep)
    }

    /** Чья пора переподписаться: ключ, номер попытки, сколько длится пауза (мс). */
    data class Due(val key: String, val attempt: Int, val pausedMs: Long)

    fun due(nowMs: Long): List<Due> {
        val out = mutableListOf<Due>()
        for ((key, p) in paused) {
            if (nowMs < p.nextAt) continue
            p.attempts++
            val step = STEPS_MS[minOf(p.attempts, STEPS_MS.size - 1)]
            p.nextAt = nowMs + step
            out += Due(key, p.attempts, nowMs - p.since)
        }
        return out
    }

    companion object {
        /** 15 → 30 → 60, дальше 60: задержка до первой попытки, затем между попытками. */
        val STEPS_MS = longArrayOf(15_000L, 15_000L, 30_000L, 60_000L)
    }
}

/**
 * Когда писать в журнал оценку приёма (ПЗ3): при входе видео в паузу и выходе из неё, при
 * повторной подаче, при смене больше чем на 30 %, а в паузе — раз в 15 с: её рост или стояние
 * показывает, застревает ли оценка (§5 плана). Иначе это лента чисел раз в 3 с.
 */
class EstimateNotes {
    private var last: Double? = null
    private var at = Long.MIN_VALUE / 2
    private var wasPaused = false
    private var saidMissing = false

    /** Что писать: `null` — ничего; иначе оценка (или `null` внутри — «оценки нет», один раз). */
    class Note(val kbps: Double?, val paused: Boolean)

    fun next(nowMs: Long, estimate: Double?, paused: Boolean, forced: Boolean = false): Note? {
        val turned = paused != wasPaused
        wasPaused = paused
        if (estimate == null) {
            if (saidMissing && !forced && !turned) return null
            saidMissing = true
            return Note(null, paused)
        }
        val prev = last
        val due = forced || turned || prev == null ||
            kotlin.math.abs(estimate - prev) > prev * CHANGE_SHARE ||
            (paused && nowMs - at >= PAUSED_EVERY_MS)
        if (!due) return null
        last = estimate
        at = nowMs
        return Note(estimate, paused)
    }

    companion object {
        const val CHANGE_SHARE = 0.3
        const val PAUSED_EVERY_MS = 15_000L
    }
}
