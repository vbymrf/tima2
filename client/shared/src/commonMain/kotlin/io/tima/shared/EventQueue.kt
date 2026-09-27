package io.tima.shared

/**
 * Очередь событий — [ПЛАН-СОБЫТИЙ](../../../../../../../../doc_mig/ПЛАН-СОБЫТИЙ.md).
 *
 * Чистое правило без экрана и хранилищ: на входе — что известно о каждом событии и что
 * очередь помнит в этом запуске, на выходе — какое событие открыто и что стоит за ним.
 * Отдельно от `Root`, чтобы проверяться целиком, как лента звонков в [CallLedger].
 *
 * ── ЗАЧЕМ ОЧЕРЕДЬ ────────────────────────────────────────────────────────────
 *
 * До 2026-09-27 каждое событие было своим условием в `Root`, и важность была записана
 * порядком этих условий. Отсюда пять бед (план, §0): мелькание, пока сведения не
 * дочитаны (Redmi, отчёт QN4N); подмена открытого события опоздавшим; событие, которое
 * «вычисляется», а не показывается, и потому возвращается после переписки или звонка;
 * журнал, пишущий «показали» про то, чего не было на экране; и два события подряд, о
 * которых никто не договаривался.
 */
enum class EventKind {
    // Порядок объявления — порядок важности (заказчик 2026-09-27). Входящий звонок сюда
    // не входит: он поверх всего и очереди не ждёт.

    /** Важное обновление — вместе с «установку не довели»: действие у них одно. */
    Update,

    /** Уведомления приложению запрещены — входящий не покажется. */
    Notices,

    /** Канал «Звонки» выключен при разрешённых уведомлениях. */
    Calls,

    /** Экономия батареи ограничивает приложение — в фоне его могут остановить. */
    Battery,

    /** Обновление встало. Новость, а не просьба — но тоже событие (заказчик 2026-09-27). */
    Installed,
}

/**
 * Есть ли событие: да, нет или **ещё не знаю**.
 *
 * Третье значение и есть лекарство от мелькания: пока отметка «Позже» не прочитана или
 * сервер не ответил, событие в очередь не встаёт. Раньше «не знаю» считалось «нет
 * отметки», и спрятанное на неделю показывалось на долю секунды при каждом запуске.
 */
enum class Presence { Yes, No, Unknown }

/**
 * Что очередь помнит в этом запуске. Не хранится между запусками: «Пропустить все» и
 * «Следующее» — до следующего запуска (заказчик 2026-09-27), а то, что живёт дольше
 * («Позже» на неделю), хранит само событие.
 */
data class EventMemory(
    /** Закрытые в этом запуске — своим действием, «Позже» или «Следующее». */
    val closed: Set<EventKind> = emptySet(),
    val skippedAll: Boolean = false,
    /** Открытое сейчас. Держится, пока событие в очереди, — опоздавшее его не вытесняет. */
    val current: EventKind? = null,
    /**
     * Что стояло в очереди, когда её впервые показали. Всё, что встало после, помечается
     * «новое». `null` — очередь ещё не показывали.
     */
    val seenAtOpen: Set<EventKind>? = null,
)

/** Строка списка «Ещё»: событие и пометка, что оно встало после открытия очереди. */
data class EventLine(val kind: EventKind, val fresh: Boolean)

/**
 * Что показать.
 *
 * @property current открытое; `null` — показывать нечего или сейчас нельзя.
 * @property position номер открытого среди прошедших через очередь в этом запуске.
 */
data class EventView(
    val waiting: List<EventKind>,
    val current: EventKind?,
    val rest: List<EventLine>,
    val position: Int,
    val total: Int,
)

object EventQueue {

    /**
     * Очередь по порядку важности: только те, что точно есть и не закрыты в этом запуске.
     * «Пропустить все» опустошает её до следующего запуска.
     */
    fun waiting(presence: Map<EventKind, Presence>, memory: EventMemory): List<EventKind> =
        if (memory.skippedAll) {
            emptyList()
        } else {
            EventKind.entries.filter { presence[it] == Presence.Yes && it !in memory.closed }
        }

    /**
     * @param allowed можно ли показывать сейчас: не во время звонка и на главном экране.
     *   Нельзя — очередь не пропадает и ничего не забывает, а просто ждёт.
     */
    fun view(presence: Map<EventKind, Presence>, memory: EventMemory, allowed: Boolean): EventView {
        val waiting = waiting(presence, memory)
        // Открытое не подменяется: пока оно в очереди, оно и открыто — даже если встало
        // более важное. Опоздавшее идёт в список, где его и видно.
        val current = if (!allowed) null else memory.current?.takeIf { it in waiting } ?: waiting.firstOrNull()
        val rest = waiting.filter { it != current }.map { kind ->
            EventLine(kind, fresh = memory.seenAtOpen != null && kind !in memory.seenAtOpen)
        }
        return EventView(
            waiting = waiting,
            current = current,
            rest = rest,
            position = memory.closed.size + 1,
            total = memory.closed.size + waiting.size,
        )
    }

    /**
     * Очередь на экране: закрепить открытое и, в первый раз, запомнить, что в ней было.
     *
     * **Закрепление обязательно.** Без него открытым было бы «первое по важности», и
     * важное обновление, пришедшее с сервера через две секунды, вытеснило бы событие,
     * которое человек уже читает, — ровно та подмена, от которой очередь и заведена.
     */
    fun shown(memory: EventMemory, view: EventView): EventMemory = memory.copy(
        current = view.current ?: memory.current,
        seenAtOpen = memory.seenAtOpen ?: view.waiting.toSet(),
    )

    /** Открыть событие из списка. Прежнее открытое остаётся в очереди. */
    fun open(memory: EventMemory, kind: EventKind): EventMemory = memory.copy(current = kind)

    /** Закрыть событие до следующего запуска: действием, «Позже» или «Следующее». */
    fun close(memory: EventMemory, kind: EventKind): EventMemory =
        memory.copy(closed = memory.closed + kind, current = memory.current.takeIf { it != kind })

    fun skipAll(memory: EventMemory): EventMemory = memory.copy(skippedAll = true, current = null)

    /**
     * Очередь словами — для снимка отчёта о проблеме.
     *
     * Отчёт QN4N показал «показали событие» и ни слова о том, что его спрятали «Позже»
     * накануне: то, что было за пределами суток журнала, отчёт не видел. Теперь в снимке —
     * каждое событие, которое есть или спрятано, и почему. Отсутствующие не пишутся:
     * «нет» про пять событий — строка шума.
     *
     * @param laterUntil до какого времени событие спрятано «Позже», мс.
     */
    fun describe(
        presence: Map<EventKind, Presence>,
        memory: EventMemory,
        laterUntil: Map<EventKind, Long>,
        now: Long,
    ): String {
        val parts = EventKind.entries.mapNotNull { kind ->
            val until = laterUntil[kind]?.takeIf { it > now }
            when {
                kind in memory.closed -> "$kind закрыто в этом запуске"
                presence[kind] == Presence.Yes && memory.skippedAll -> "$kind пропущено"
                presence[kind] == Presence.Yes -> "$kind стоит"
                presence[kind] == Presence.Unknown -> "$kind не знаю"
                until != null -> "$kind спрятано «Позже» до " +
                    kotlinx.datetime.Instant.fromEpochMilliseconds(until).toString().take(10)
                else -> null
            }
        }
        return parts.joinToString("; ").ifEmpty { "нет" }
    }
}
