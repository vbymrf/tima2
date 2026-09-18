package io.tima.domain.chat

import kotlinx.coroutines.flow.Flow

/**
 * Набор разделов — порт, общий для любого набора (`разделы.md`, «Наборы разделов»).
 *
 * Наборов столько, сколько сущностей: контактов, сообществ, дальше — что появится. Общее у
 * них — **механизм**: список, завести, переименовать, переставить, убрать. Этот порт и
 * есть механизм; реализаций столько, сколько наборов, по одной на таблицу.
 *
 * Набор контактов пока живёт внутри `Book` (те же пять операций) — исторически он появился
 * раньше. Сводить его сюда — отдельная правка, не смешанная с заведением второго набора.
 */
interface SectionSet {
    fun sections(): Flow<List<Section>>

    /** @return идентификатор — новый либо уже существующего раздела с тем же именем. */
    suspend fun add(name: String, icon: Int = SectionIcon.NONE.index): String

    suspend fun rename(id: String, name: String, icon: Int)
    suspend fun place(id: String, place: Int)

    /** Убрать раздел. Что в нём лежало, возвращается в «Общий». */
    suspend fun remove(id: String)
}

/**
 * Раздел у переписки — для групп, каналов, сообществ (Р5).
 *
 * У личной переписки раздел не ставится: он выводится из раздела собеседника в книге, и
 * своё поле было бы второй правдой.
 */
fun interface ChatSections {
    suspend fun moveTo(chatId: String, sectionId: String)
}
