package io.tima.domain.chat

/** Вид точки буфера переходов: откуда перешли, куда перешли, вход из вкладки «Указатели». */
enum class JumpKind { FROM, TO, POINTERS }

/**
 * Точка буфера: сообщение [serverId] переписки [chatId]. [live] — по ней можно вернуться: точка «куда»
 * начинает работать, только когда с неё ушли (сообщение целиком ушло с экрана).
 */
data class JumpPoint(val chatId: String, val serverId: Long, val kind: JumpKind, val live: Boolean = true) {
    /** То же место — то же сообщение; вид и то, ожила ли точка, не в счёт. */
    fun same(o: JumpPoint): Boolean = chatId == o.chatId && serverId == o.serverId
}

/**
 * Буфер переходов (doc_mig/ПЛАН-(БП)-БУФЕРА-ПЕРЕХОДОВ.md): один на сообщество и на каждую переписку вне
 * него, на устройстве, до [MAX] точек. Переход дописывает в конец «откуда» и «куда»; новое — всегда в
 * конец, «вперёд» не стирается. Одинаковые точки подряд не дописываются (заказчик 2026-10-21): дважды та
 * же ссылка или повторный приход из «Указателей» — одна точка.
 *
 * Место в буфере — [at] и [on]: стоим ли на точке [at] или ушли с неё. Ушли — «назад» ведёт к ней самой,
 * стоим — к предыдущей.
 */
class JumpBuffer(points: List<JumpPoint> = emptyList(), at: Int = points.size, on: Boolean = false) {

    private val list = points.takeLast(MAX).toMutableList()

    val points: List<JumpPoint> get() = list

    var at: Int = (at - (points.size - list.size)).coerceIn(0, list.size)
        private set

    var on: Boolean = on && this.at < list.size
        private set

    /** Куда приведёт «назад»; `null` — некуда. */
    fun backTarget(): Int? {
        val limit = if (on) at - 1 else minOf(at, list.lastIndex)
        return (limit downTo 0).firstOrNull { list[it].live }
    }

    /** Куда приведёт «вперёд»; `null` — некуда. */
    fun forwardTarget(): Int? = ((at + 1)..list.lastIndex).firstOrNull { list[it].live }

    /** Перешли из [from] (не знаем откуда — `null`) в [to]: обе — в конец, стоим на «куда». */
    fun jumped(from: JumpPoint?, to: JumpPoint) {
        val target = to.copy(kind = JumpKind.TO, live = false)
        val tail = list.takeLast(2)
        val again = from != null && tail.size == 2 && tail[0].same(from) && tail[1].same(target)
        if (!again) {
            if (from != null) append(from.copy(live = true))
            append(target)
        }
        at = list.lastIndex
        on = true
        list[at] = list[at].copy(live = false)
        trim()
    }

    /** С точки, на которой стояли, ушли — она ожила, «назад» вернёт к ней. */
    fun left() {
        if (!on || at !in list.indices) return
        on = false
        list[at] = list[at].copy(live = true)
    }

    /** «Назад»: точка, к которой идти; место — на ней. */
    fun back(): JumpPoint? = backTarget()?.let(::stand)

    /** «Вперёд»: точка, к которой идти; место — на ней. */
    fun forward(): JumpPoint? = forwardTarget()?.let(::stand)

    /** Долгое нажатие на «назад» — буфер очищен. */
    fun clear() {
        list.clear()
        at = 0
        on = false
    }

    private fun stand(i: Int): JumpPoint {
        // Уходим с точки, на которой стояли, — она жива: к ней можно вернуться «вперёд» или «назад».
        if (on && at in list.indices) list[at] = list[at].copy(live = true)
        at = i
        on = true
        return list[i]
    }

    private fun append(p: JumpPoint) {
        if (list.lastOrNull()?.same(p) == true) return
        list += p
    }

    private fun trim() {
        while (list.size > MAX) {
            list.removeAt(0)
            at = (at - 1).coerceAtLeast(0)
        }
    }

    /** Запись для настроек устройства: место, затем точки. */
    fun encode(): String = buildString {
        append(at).append(',').append(if (on) 1 else 0)
        for (p in list) append(';').append(p.kind.ordinal).append(':').append(if (p.live) 1 else 0).append(':').append(p.serverId).append(':').append(p.chatId)
    }

    companion object {
        /** Точек в буфере — не больше 20 (заказчик 2026-10-21); старые вытесняются. */
        const val MAX = 20

        /** Обратно из [encode]; испорченное — пустой буфер. */
        fun decode(text: String?): JumpBuffer {
            if (text.isNullOrBlank()) return JumpBuffer()
            return runCatching {
                val parts = text.split(';')
                val (at, on) = parts[0].split(',').let { it[0].toInt() to (it[1] == "1") }
                val points = parts.drop(1).map { s ->
                    val f = s.split(':', limit = 4)
                    JumpPoint(f[3], f[2].toLong(), JumpKind.entries[f[0].toInt()], f[1] == "1")
                }
                JumpBuffer(points, at, on)
            }.getOrElse { JumpBuffer() }
        }

        /**
         * Чей буфер (заказчик 2026-10-21): сообщества, если группа в нём; иначе самой группы; у личной —
         * своей переписки.
         */
        fun keyOf(chatId: String, group: Boolean, community: String? = null): String = when {
            !group -> "personal:$chatId"
            !community.isNullOrBlank() -> "community:$community"
            else -> "group:$chatId"
        }
    }
}
