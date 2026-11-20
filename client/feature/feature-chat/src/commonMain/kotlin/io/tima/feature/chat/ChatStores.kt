package io.tima.feature.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Один store на переписку и счёт ссылок (беда 2026-11-20 «уведомление снимается сразу»).
 *
 * Прежде экран переписки заводил свой store при каждом открытии, а наблюдатели store жили в общей области приложения
 * до конца процесса: открыл переписку пять раз — пять наблюдателей, и каждое новое сообщение пять раз перечитывалось
 * из базы; отставший store со «вижу» отмечал прочитанным всё новое, и уведомление снималось через 20–30 мс.
 *
 * Теперь экран берёт store ([acquire]) и отпускает ([release]). Ссылок не осталось — store ещё [idleMs] живёт: вернулся
 * в переписку за это время — тот же store, лента сразу на месте. Срок вышел или простаивающих больше [maxIdle] — store
 * останавливается ([stop]) и выбрасывается. Слабых ссылок нет намеренно: их освобождает сборщик мусора когда захочет,
 * и до тех пор наблюдатель продолжал бы читать базу.
 *
 * Вызывается с главного потока: [acquire] и [release] — из составления экрана, отсрочка — в области [release].
 */
class StoreRegistry<T : Any>(
    private val stop: (T) -> Unit,
    private val isStopped: (T) -> Boolean = { false },
    private val idleMs: Long = 60_000L,
    private val maxIdle: Int = 3,
) {
    private class Entry<T>(val store: T, var refs: Int, var idleSince: Long = 0L, var eviction: Job? = null)

    private val entries = LinkedHashMap<String, Entry<T>>()
    private var clock = 0L

    /** Сколько store живо сейчас — для проверок и журнала. */
    val size: Int get() = entries.size

    /** Сколько экранов держат store [key]; 0 — простаивает или его нет. */
    fun refs(key: String): Int = entries[key]?.refs ?: 0

    /** Взять store переписки: живой — тот же, нет — заводится [create]. */
    fun acquire(key: String, create: () -> T): T {
        val found = entries[key]
        if (found != null && !isStopped(found.store)) {
            found.refs++
            found.eviction?.cancel()
            found.eviction = null
            return found.store
        }
        found?.let { entries.remove(key) }
        val made = create()
        entries[key] = Entry(made, refs = 1)
        return made
    }

    /** Отпустить; последняя ссылка — store простаивает [idleMs], потом останавливается и выбрасывается. */
    fun release(key: String, scope: CoroutineScope) {
        val e = entries[key] ?: return
        e.refs = (e.refs - 1).coerceAtLeast(0)
        if (e.refs > 0) return
        e.idleSince = ++clock
        e.eviction?.cancel()
        e.eviction = scope.launch {
            delay(idleMs)
            evict(key)
        }
        // Простаивающих больше [maxIdle] — самый давний уходит сразу.
        val idle = entries.filter { it.value.refs == 0 }
        if (idle.size > maxIdle) {
            idle.minByOrNull { it.value.idleSince }?.key?.let(::evict)
        }
    }

    /** Остановить и выбросить, если его никто не держит. */
    fun evict(key: String) {
        val e = entries[key] ?: return
        if (e.refs > 0) return
        e.eviction?.cancel()
        entries.remove(key)
        stop(e.store)
    }

    /** Сменился аккаунт — store прежнего не нужны никому. */
    fun clearExcept(prefix: String) {
        for (key in entries.keys.filter { !it.startsWith(prefix) }.toList()) {
            val e = entries.remove(key) ?: continue
            e.eviction?.cancel()
            stop(e.store)
        }
    }
}

/** Реестр store переписок процесса; ключ — «аккаунт/переписка». */
object ChatStores {
    val registry = StoreRegistry<ChatStore>(stop = { it.stop() }, isStopped = { it.stopped })
}
