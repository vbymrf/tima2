package io.tima.feature.chat

import io.tima.core.words.RussianWords
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.Section
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

    private val work = Section(id = "s-work", name = "Работа")
    private val борис = BookEntry("+79160001122", namePhone = "Борис", sectionId = work.id, userId = "u-1")
    private val анна = BookEntry("+79035554433", namePhone = "Анна", userId = "u-2")
    private val виктор = BookEntry("+79267778899", nameOwn = "Виктор, сосед")
    private val поликлиника = BookEntry("+74951002030")

    private fun состояние(vararg люди: BookEntry, view: BookView = BookView()) =
        BookState(all = люди.toList(), sections = listOf(work), view = view)

    @Test
    fun телефон_идёт_последним_разделом() {
        val groups = состояние(виктор, борис, анна).groups(RussianWords.book)
        assertEquals(listOf("Работа", "Общий", "Телефон"), groups.map { it.name })
        assertTrue(groups.last().outsiders, "последний раздел не отмечен как «не в TIMa»")
        assertEquals(listOf(виктор), groups.last().people)
    }

    @Test
    fun пустой_раздел_не_показывается() {
        // «Работа» есть в списке разделов, но людей в ней нет — полосы быть не должно:
        // пустая полоса в списке выглядит как потерянные контакты.
        val groups = состояние(анна).groups(RussianWords.book)
        assertEquals(listOf("Общий"), groups.map { it.name })
    }

    @Test
    fun выключенные_чужие_гасят_только_раздел_телефон() {
        val state = состояние(виктор, борис, view = BookView(showOutsiders = false))
        assertEquals(listOf("Работа"), state.groups(RussianWords.book).map { it.name })
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
        val groups = состояние(поликлиника).groups(RussianWords.book)
        assertEquals(listOf(поликлиника), groups.single().people)
        // Имя не выдумывается: у строки его нет, и показывать её будет номер.
        assertEquals(null, поликлиника.name)
    }

    // ── один сбор вкладок на все исполнения (заказчик 2026-09-19) ────────────

    @Test
    fun заведённый_пустой_раздел_виден_и_на_полосе_и_в_плитке() {
        // Живой случай: человек завёл «Работу», в ней ещё никого, и в виде «Меню» полоса
        // показывала только «Всё · Общий» — раздел пропадал. Теперь все четыре исполнения
        // собираются одним `sectionTabs`.
        val пустой = BookState(all = listOf(анна), sections = listOf(work))
        assertEquals(listOf("", "s-work", COMMON_SECTION), пустой.tabs(RussianWords.book).map { it.id })
        assertEquals(listOf(ALL_SECTION, "s-work", COMMON_SECTION), пустой.tiles(RussianWords.book).map { it.id })
    }

    @Test
    fun имя_и_значок_общего_берутся_из_его_строки() {
        val свой = Section(id = COMMON_SECTION, name = "Без раздела", icon = 8)
        val state = BookState(all = listOf(анна), sections = listOf(work), common = свой)
        val общий = state.tabs(RussianWords.book).last()
        assertEquals(COMMON_SECTION, общий.id)
        assertEquals("Без раздела", общий.name)
        assertEquals(8, общий.icon)
        // Обычным разделом он при этом не показывается — иначе стоял бы в списке дважды.
        assertTrue(state.sections.none { it.id == COMMON_SECTION })
    }
}
