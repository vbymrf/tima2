package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Что служба звонка показывает и держит. Одинаковое желание второй раз не отправляется. */
data class ServiceWish(
    val title: String,
    val text: String,
    val hangUpLabel: String,
    val connectedAt: Long,
    /** Держать ли ещё и камеру — «Видео при сворачивании: продолжать показывать». */
    val camera: Boolean,
)

/**
 * Подъёмы службы звонка — **не чаще одного за [gapMs]** (заказчик 2026-10-01).
 *
 * ── ЗАЧЕМ ───────────────────────────────────────────────────────────────────
 *
 * Каждый подъём — просьба к системе «подними службу переднего плана», и на перекрытии таких
 * просьб с «погаси» Redmi 2026-09-30 закрылся (`БЕДЫ/2026-09-30-служба-звонка-после-отмены.md`).
 * Поводов поднять много — вход в комнату, ответ собеседника, включение камеры при «продолжать
 * показывать», — и 2026-10-01 они давали три подъёма за полсекунды.
 *
 * Здесь одна точка решает, что сказать службе. Сколько подъёмов за звонок — не ограничено;
 * ограничено, как часто. Первый идёт сразу. Пришедшее раньше [gapMs] после прошлого не
 * теряется: откладывается, и к сроку уходит **одним** подъёмом с самым свежим желанием.
 * Желание, совпадающее с уже отправленным, не отправляется вовсе.
 *
 * [off] гасит службу и снимает отложенный подъём: служба не поднимется у законченного звонка.
 * Время прошлого подъёма [off] не сбрасывает — следующий звонок тоже не раньше срока.
 */
class ServiceGate(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val raise: (ServiceWish) -> Unit,
    private val lower: () -> Unit,
    private val gapMs: Long = GAP_MS,
) {
    private var sent: ServiceWish? = null
    private var sentAt: Long? = null
    private var wanted: ServiceWish? = null
    private var pending: Job? = null

    fun want(wish: ServiceWish) {
        wanted = wish
        if (wish == sent) {
            pending?.cancel()
            pending = null
            return
        }
        // Отложенный подъём уже ждёт — он возьмёт это желание, второй не заводится.
        if (pending != null) return
        val wait = sentAt?.let { it + gapMs - now() } ?: 0L
        if (wait <= 0) {
            send()
            return
        }
        Journal.note(LogCode.CALL, "подъём службы звонка отложен — не чаще раза в секунду", "мс" to wait)
        pending = scope.launch {
            delay(wait)
            pending = null
            send()
        }
    }

    fun off() {
        pending?.cancel()
        pending = null
        wanted = null
        sent = null
        lower()
    }

    private fun send() {
        val wish = wanted ?: return
        if (wish == sent) return
        sent = wish
        sentAt = now()
        raise(wish)
    }

    companion object {
        /** Единица времени: не чаще одного подъёма в секунду. */
        const val GAP_MS = 1_000L
    }
}
