package io.tima.domain.chat

/**
 * Описание — сообщения уровня 0 (doc_mig/ПЛАН-(ЗО)-ЗАКРЕПЛЕНИЯ-И-ОПИСАНИЯ.md, ЗО4–ЗО5). Есть у
 * группы, канала, сообщества; забирается, только когда человек открыл окно «Описание», — в ленту,
 * уведомления и счёт нового не идёт: «как страница пользователя — не зашёл, ничего не увидел»
 * (заказчик 2026-10-18).
 */
data class ShowcaseLine(
    val serverId: Long,
    val senderId: String?,
    val line: DecodedLine,
    /** «Только продолжение»: само в список не идёт, его показывает подкладка. */
    val onlyContinuation: Boolean = false,
)

/** Выдача сервера: суперсообщение (0 — места не заняли) и все сообщения уровня 0 от старых к новым. */
data class ShowcasePage(val superId: Long, val lines: List<ShowcaseLine>) {

    /**
     * Что показать (заказчик 2026-10-18): суперсообщение есть — его, за вид отвечает владелец; нет
     * и сообщений до трёх — лентой, полностью; больше — списком по три строки, нажатие открывает
     * само сообщение, одно.
     */
    fun view(): ShowcaseView {
        val own = lines.filterNot { it.onlyContinuation }
        lines.firstOrNull { it.serverId == superId && superId > 0 }?.let { return ShowcaseView.Super(it) }
        return when {
            own.isEmpty() -> ShowcaseView.Empty
            own.size <= FEED_UP_TO -> ShowcaseView.Feed(own)
            else -> ShowcaseView.List(own)
        }
    }

    /** Сообщение описания по номеру — подкладке и переходу «без ничего». */
    fun line(serverId: Long): ShowcaseLine? = lines.firstOrNull { it.serverId == serverId }

    /**
     * Черновик суперсообщения: подкладки всех сообщений уровня 0 списком, по три строки, как
     * «Продолжить чтение» (Решения §11.3). Владелец меняет вид как у любого сообщения.
     */
    fun superDraft(kind: String, container: String): ComposedDoc = ComposedDoc(
        items = lines.filter { !it.onlyContinuation && it.serverId != superId }.map {
            ComposedItem.Widget(ComposedWidget.Underlay(kind, container, it.serverId, lines = LIST_LINES))
        }.ifEmpty { listOf(ComposedItem.Text("")) },
        level = 0,
    )

    companion object {
        /** До скольких сообщений описание показывается лентой целиком. */
        const val FEED_UP_TO = 3

        /** Сколько строк у сообщения в списке описания. */
        const val LIST_LINES = 3

        /** Сколько сообщений уровня 0 бывает у сущности (сервер, `showcase_full`). */
        const val LIMIT = 50
    }
}

sealed interface ShowcaseView {
    data object Empty : ShowcaseView
    data class Super(val line: ShowcaseLine) : ShowcaseView
    data class Feed(val lines: kotlin.collections.List<ShowcaseLine>) : ShowcaseView
    data class List(val lines: kotlin.collections.List<ShowcaseLine>) : ShowcaseView
}

/** Где лежит описание: `group`, `channel`, `community` и номер сущности. */
data class ShowcaseOf(val kind: String, val id: String)

/** Порт: выдача описания и суперсообщение. Реализуется в `shared` (группе нужна проверка подписи). */
interface ShowcaseSource {
    /** `null` — сеть или отказ. */
    suspend fun page(of: ShowcaseOf): ShowcasePage?

    /** Поставить (`serverId > 0`) или снять (0) суперсообщение. Только владелец. */
    suspend fun setSuper(of: ShowcaseOf, serverId: Long): Boolean
}
