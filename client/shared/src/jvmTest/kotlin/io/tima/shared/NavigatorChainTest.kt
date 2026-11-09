package io.tima.shared

import androidx.compose.runtime.mutableStateOf
import io.tima.feature.shell.SettingsItem
import io.tima.feature.shell.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Цепочки переходов (ПЛАН-(МП) М1, М3, М7, М10, М16–М20): у окна своя цепочка подокон, свайп возвращает её,
 * переключатель — в начало; «назад» — по звену, потом «откуда», потом «Телефон»; звонок возвращает туда, где был.
 */
class NavigatorChainTest {

    private val log = mutableListOf<String>()
    private val nav = Navigator(mutableStateOf(Window.Phone)) { what, details -> log += what + " " + details.joinToString { "${it.first}=${it.second}" } }

    private val аня = Where.Chat("c-аня", "Аня")
    private val петя = Where.Person("u-петя")
    private val дача = Where.Chat("g-дача", "Дача")

    @Test
    fun цепочка_окна_и_назад_по_звену() {
        nav.where = аня
        nav.where = петя
        nav.where = дача
        assertEquals(listOf(аня, петя, дача), nav.chain())
        nav.where = Where.Nothing
        assertEquals(петя, nav.where, "закрыть — одно звено")
    }

    @Test
    fun повтор_подряд_не_даёт_звена_а_не_подряд_даёт() {
        nav.where = аня
        nav.where = Where.Chat("c-аня", "Аня", jumpTo = 7)
        assertEquals(1, nav.chain().size, "та же переписка подряд — звено заменено")
        nav.where = петя
        nav.where = дача
        nav.where = аня
        assertEquals(listOf("c-аня", "u-петя", "g-дача", "c-аня"), nav.chain().map { (it as? Where.Chat)?.chatId ?: (it as Where.Person).userId })
        assertTrue(nav.back()); assertTrue(nav.back()); assertTrue(nav.back())
        assertEquals("c-аня", (nav.where as Where.Chat).chatId, "«назад» — обратным путём")
    }

    @Test
    fun родитель_под_верхним_это_возврат() {
        nav.where = Where.Chat("g-1", "Группа")
        nav.where = Where.Members("g-1", "Группа")
        nav.where = Where.Access("g-1", "Группа")
        nav.where = Where.Members("g-1", "Группа") // «назад» доступа называет состав
        assertEquals(listOf<Where>(Where.Chat("g-1", "Группа"), Where.Members("g-1", "Группа")), nav.chain())
    }

    @Test
    fun настройки_одно_звено() {
        nav.where = Where.Settings(SettingsItem.DEVICES)
        nav.where = Where.Settings()
        assertEquals(listOf<Where>(Where.Settings()), nav.chain())
        nav.where = Where.Nothing
        assertEquals(Where.Nothing, nav.where)
    }

    @Test
    fun черновое_подокно_заменяется_открытым() {
        nav.where = Where.New
        nav.where = аня
        assertEquals(listOf<Where>(аня), nav.chain(), "новая переписка не остаётся под открытой")
    }

    @Test
    fun свайп_в_точку_переключатель_в_начало() {
        nav.where = аня
        nav.enter(Window.Social, Via.Swipe)
        assertEquals(Where.Nothing, nav.where)
        nav.enter(Window.Phone, Via.Swipe)
        assertEquals(аня, nav.where, "свайп вернул окно с открытой перепиской")
        nav.enter(Window.Social, Via.Switcher)
        assertEquals(Arrive.Start, nav.enter(Window.Phone, Via.Switcher))
        assertEquals(Where.Nothing, nav.where, "переключатель — начальное положение")
    }

    @Test
    fun настройка_переходов_меняет_способ() {
        nav.arriveBy = { Arrive.Point }
        nav.where = аня
        nav.enter(Window.Social, Via.Switcher)
        nav.enter(Window.Phone, Via.Switcher)
        assertEquals(аня, nav.where)
    }

    @Test
    fun назад_по_слоям() {
        nav.where = аня
        nav.enter(Window.Social, Via.HeaderNotice)
        nav.where = дача
        assertTrue(nav.back()); assertEquals(Window.Social, nav.window)
        assertTrue(nav.back()); assertEquals(Window.Phone, nav.window); assertEquals(аня, nav.where, "в «откуда»")
        assertTrue(nav.back()); assertEquals(Where.Nothing, nav.where)
        assertFalse(nav.back(), "на «Телефоне» в начале — выход")
        nav.enter(Window.Page, Via.Swipe)
        assertTrue(nav.back()); assertEquals(Window.Phone, nav.window, "из другого окна без «откуда» — на «Телефон»")
    }

    @Test
    fun конец_звонка_возвращает_туда_где_был() {
        nav.enter(Window.Activity, Via.Swipe)
        nav.where = аня
        nav.toCall()
        assertEquals(Window.Call, nav.window)
        nav.leaveCall()
        assertEquals(Window.Activity, nav.window)
        assertEquals(аня, nav.where)
    }

    @Test
    fun свернуть_звонок_ведёт_на_телефон_с_начала() {
        // ПЛАН-(ЗН) ЗН2: «Свернуть» — в «Телефон» на «Чаты», звонок остаётся панелью над шапкой.
        nav.where = аня
        nav.toCall()
        nav.enter(Window.Phone, Via.Minimize)
        assertEquals(Window.Phone, nav.window)
        assertEquals(Where.Nothing, nav.where, "свёрнутый звонок открыл переписку, а не «Чаты»")
        assertTrue(log.any { "как=minimize" in it }, log.toString())
    }

    @Test
    fun предел_двадцать_звеньев() {
        repeat(30) { nav.where = Where.Chat("c-$it", null) }
        assertEquals(Navigator.LIMIT, nav.chain().size)
        assertEquals("c-29", (nav.where as Where.Chat).chatId)
    }

    @Test
    fun журнал_переходов_пишет_способ() {
        nav.enter(Window.Social, Via.Swipe)
        nav.open(аня, Via.Link)
        assertTrue(log.any { it.startsWith("окно") && "как=swipe" in it && "куда=Social" in it }, log.toString())
        assertTrue(log.any { it.startsWith("открыто") && "как=link" in it && "что=chat" in it }, log.toString())
    }
}

/** Настройка «Переходы» (МП М5): запись `shell.arrive.<код>` меняет способ, без записи — по умолчанию. */
class ArriveSettingTest {

    @Test
    fun запись_меняет_способ_без_неё_по_умолчанию() {
        val by = arriveFrom(mapOf("shell.arrive.swipe" to "start", "shell.arrive.panel" to "point"))
        assertEquals(Arrive.Start, by(Via.Swipe))
        assertEquals(Arrive.Point, by(Via.Panel))
        assertEquals(Arrive.Start, by(Via.Switcher), "по умолчанию — начальное положение")
        assertEquals(Arrive.Point, by(Via.Rail), "по умолчанию — точка")
    }
}

/** Хранение цепочек (МП М6, М19): туда и обратно без потерь; черновые и кодовые подокна не хранятся. */
class NavStoreTest {

    @Test
    fun туда_и_обратно() {
        val nav = Navigator(mutableStateOf(Window.Phone))
        nav.where = Where.Chat("c-1", "Аня")
        nav.where = Where.Person("u-1")
        nav.enter(Window.Social, Via.HeaderNotice)
        nav.where = Where.Community("co-1")
        nav.where = Where.Showcase("group", "g", "Дача")
        nav.where = Where.Settings(SettingsItem.DEVICES)
        val text = nav.stored()

        val again = Navigator(mutableStateOf(Window.Phone))
        assertTrue(again.untouched)
        val (chains, origins) = NavStore.decode(text)
        again.restore(chains, origins)
        assertEquals(nav.chain(Window.Phone), again.chain(Window.Phone))
        assertEquals(nav.chain(Window.Social), again.chain(Window.Social))
        assertEquals(nav.origins(), again.origins())
    }

    @Test
    fun черновые_и_кодовые_не_хранятся() {
        val nav = Navigator(mutableStateOf(Window.Phone))
        nav.where = Where.Chat("c-1", null)
        nav.where = Where.Link("секрет")
        val (chains, _) = NavStore.decode(nav.stored())
        assertEquals(listOf<Where>(Where.Chat("c-1", null)), chains[Window.Phone])
        assertEquals(emptyMap(), NavStore.decode("мусор\nw\u001FНетТакого\u001Fchat").first)
    }
}
