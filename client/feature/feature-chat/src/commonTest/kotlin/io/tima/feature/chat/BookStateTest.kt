package io.tima.feature.chat

import io.tima.core.words.RussianWords
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.BookKey
import io.tima.domain.chat.BookList
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
    /** Строка книги по номеру: ключ считается так же, как его считает база (Л0). */
    private fun поНомеру(
        phone: String,
        namePhone: String? = null,
        nameOwn: String? = null,
        sectionId: String = "",
        userId: String? = null,
    ) = BookEntry(
        id = BookKey.ofPhone(phone), phone = phone, namePhone = namePhone,
        nameOwn = nameOwn, sectionId = sectionId, userId = userId,
    )

    private val борис = поНомеру("+79160001122", namePhone = "Борис", sectionId = work.id, userId = "u-1")
    private val анна = поНомеру("+79035554433", namePhone = "Анна", userId = "u-2")
    private val виктор = поНомеру("+79267778899", nameOwn = "Виктор, сосед")
    private val поликлиника = поНомеру("+74951002030")

    private fun состояние(vararg люди: BookEntry, view: BookView = BookView()) =
        BookState(all = люди.toList(), sections = listOf(work), view = view)

    @Test
    fun четыре_списка_складываются_из_двух_признаков() {
        val свой = поНомеру("+79991110000", nameOwn = "Свой").copy(manual = true)
        val убран = поНомеру("+79992220000", nameOwn = "Убран").copy(list = BookList.Removed)
        val заблокирован = поНомеру("+79993330000", nameOwn = "Блок")
            .copy(manual = true, list = BookList.Blocked)
        val state = BookState(everyone = listOf(борис, свой, убран, заблокирован))

        // Списки не пересекаются: у строки одно поле `list` и один признак `manual`.
        assertEquals(listOf(борис), state.inList(BookRoster.Book))
        assertEquals(listOf(свой), state.inList(BookRoster.Tima))
        assertEquals(listOf(убран), state.inList(BookRoster.Removed))
        assertEquals(listOf(заблокирован), state.inList(BookRoster.Blocked))
    }

    @Test
    fun книга_и_tima_галочкой_не_набираются() {
        // Попасть в них — значит быть прочитанным с телефона или заведённым руками.
        // Галочка этого не делает, и предлагать её значило бы обещать несуществующее.
        assertEquals(null, BookRoster.Book.editable)
        assertEquals(null, BookRoster.Tima.editable)
        assertEquals(BookList.Removed, BookRoster.Removed.editable)
        assertEquals(BookList.Blocked, BookRoster.Blocked.editable)
    }

    @Test
    fun убранный_и_заблокированный_в_контакты_не_попадают() {
        val убран = поНомеру("+79992220000", nameOwn = "Убран").copy(list = BookList.Removed)
        assertTrue(!убран.inContacts, "убранный остался в контактах")
        assertTrue(!убран.copy(list = BookList.Blocked).inContacts, "заблокированный остался в контактах")
        assertTrue(борис.inContacts)
    }

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
    fun поиск_идёт_и_по_нику() {
        // У человека без номера ник — единственное, чем его найти: имени в нашей книге
        // может не быть вовсе, а номера у него нет и не будет (Л19).
        val аня = BookEntry(id = BookKey.ofUser("u-9"), userId = "u-9")
        val state = BookState(all = listOf(борис, аня), nicks = mapOf("u-9" to "anna_kovaleva"))

        assertEquals(listOf(аня), state.copy(search = "kovaleva").visible)
        assertEquals(listOf(аня), state.copy(search = "ANNA").visible, "регистр не должен мешать")
        // Ник чужой строки в выдачу не тянет.
        assertEquals(listOf(борис), state.copy(search = "борис").visible)
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
        assertTrue(вид.showOutsiders)
        assertTrue(вид.showName && вид.showPhone)
        assertTrue(!вид.showNickname && !вид.showUserName)
    }

    @Test
    fun выбор_вида_переживает_перезапуск() {
        val сохранённое = mutableMapOf<String, String>()
        val вид = BookView(folders = true, showOutsiders = false,
            showName = false, showNickname = true, showPhone = false)

        // save() пишет через порт; здесь достаточно собрать те же строки, что он кладёт.
        сохранённое["book.view"] = "folders"
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

    @Test
    fun общий_стоит_на_своём_месте_в_порядке_а_не_всегда_последним() {
        // Заказчик 2026-09-19: «добавь стрелки Общий». Раз его можно переставить — место
        // у него такое же, как у прочих, и берётся из `place`, а не из «допиши в конец».
        val первый = Section(id = COMMON_SECTION, name = "Общий", place = 0)
        val state = BookState(
            all = listOf(борис, анна),
            sections = listOf(work.copy(place = 1)),
            common = первый,
        )
        assertEquals(listOf("", COMMON_SECTION, "s-work"), state.tabs(RussianWords.book).map { it.id })
        // И в списке людей он тоже впереди: порядок один на вкладки и на сам список.
        assertEquals(listOf("Общий", "Работа"), state.groups(RussianWords.book).map { it.name })
    }

    @Test
    fun без_своей_строки_общий_остаётся_последним() {
        val state = BookState(all = listOf(борис, анна), sections = listOf(work.copy(place = 7)))
        assertEquals(listOf("", "s-work", COMMON_SECTION), state.tabs(RussianWords.book).map { it.id })
    }
}
