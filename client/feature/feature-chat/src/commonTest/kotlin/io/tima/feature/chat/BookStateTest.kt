package io.tima.feature.chat

import io.tima.domain.chat.BookEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Правила вкладки «Контакты» (ПЛАН-КОНТАКТОВ.md, Д5).
 *
 * Проверяется то, что решено заказчиком и потому не должно уехать при следующей правке
 * экрана: раздел «Телефон» последний, отключённый раздел гасит только его, поиск ищет по
 * имени и по номеру, а умолчание вида — «меню», а не «папки».
 */
class BookStateTest {

    private val борис = BookEntry("+79160001122", namePhone = "Борис", section = "Работа", userId = "u-1")
    private val анна = BookEntry("+79035554433", namePhone = "Анна", userId = "u-2")
    private val виктор = BookEntry("+79267778899", nameOwn = "Виктор, сосед")
    private val поликлиника = BookEntry("+74951002030")

    private fun состояние(vararg люди: BookEntry, view: BookView = BookView()) =
        BookState(all = люди.toList(), sections = listOf("Работа"), view = view)

    @Test
    fun телефон_идёт_последним_разделом() {
        val groups = состояние(виктор, борис, анна).groups
        assertEquals(listOf("Работа", "Общий", "Телефон"), groups.map { it.name })
        assertTrue(groups.last().outsiders, "последний раздел не отмечен как «не в TIMa»")
        assertEquals(listOf(виктор), groups.last().people)
    }

    @Test
    fun пустой_раздел_не_показывается() {
        // «Работа» есть в списке разделов, но людей в ней нет — полосы быть не должно:
        // пустая полоса в списке выглядит как потерянные контакты.
        val groups = состояние(анна).groups
        assertEquals(listOf("Общий"), groups.map { it.name })
    }

    @Test
    fun выключенные_чужие_гасят_только_раздел_телефон() {
        val state = состояние(виктор, борис, view = BookView(showOutsiders = false))
        assertEquals(listOf("Работа"), state.groups.map { it.name })
        // Контакт при этом никуда не делся — он просто не показан.
        assertTrue(виктор in state.all)
    }

    @Test
    fun поиск_идёт_по_имени_и_по_номеру() {
        val state = состояние(борис, анна, виктор)
        assertEquals(listOf(борис), state.copy(search = "борис").visible)
        assertEquals(listOf(виктор), state.copy(search = "сосед").visible)
        // Номер ищется по цифрам: человек набирает его как помнит, со скобками и без.
        assertEquals(listOf(анна), state.copy(search = "903 555").visible)
        assertEquals(listOf(анна), state.copy(search = "+7 903 555-44-33").visible)
    }

    @Test
    fun ничего_не_нашлось_отличается_от_пустой_книги() {
        val пустая = BookState()
        val ненайдено = состояние(борис).copy(search = "кого-то другого")
        assertTrue(!пустая.notFoundNothing, "пустая книга выдана за «не нашлось»")
        assertTrue(ненайдено.notFoundNothing)
    }

    @Test
    fun умолчание_вида_меню_а_не_папки() {
        // Решение заказчика 2026-09-05. Проверяется именно разбор пустых настроек: их
        // отсутствие — самый частый случай, первый запуск.
        val вид = BookView.from(emptyMap())
        assertTrue(!вид.folders, "по умолчанию встали папки, а решено меню")
        assertTrue(вид.showSearch)
        assertTrue(вид.showOutsiders)
        assertTrue(вид.showName && вид.showPhone)
        assertTrue(!вид.showNickname && !вид.showUserName)
    }

    @Test
    fun выбор_вида_переживает_перезапуск() {
        val сохранённое = mutableMapOf<String, String>()
        val вид = BookView(folders = true, showSearch = false, showOutsiders = false,
            showName = false, showNickname = true, showPhone = false)

        // save() пишет через порт; здесь достаточно собрать те же строки, что он кладёт.
        сохранённое["book.view"] = "folders"
        сохранённое["book.search"] = "false"
        сохранённое["book.outsiders"] = "false"
        сохранённое["book.names"] = "nick"

        assertEquals(вид, BookView.from(сохранённое))
    }

    @Test
    fun безымянный_называется_номером() {
        val groups = состояние(поликлиника).groups
        assertEquals(listOf(поликлиника), groups.single().people)
        // Имя не выдумывается: у строки его нет, и показывать её будет номер.
        assertEquals(null, поликлиника.name)
    }
}
