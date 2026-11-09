package io.tima.shared

import androidx.compose.runtime.mutableStateOf
import io.tima.feature.shell.SettingsItem
import io.tima.feature.shell.Window
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Переходы приложения — `Navigator` (ПЛАН-(РФ) РФ1): окно общее с хранилищем окна, подокно — своё; код подокна для
 * журнала не меняется от переноса.
 */
class NavigatorTest {

    @Test
    fun окно_общее_с_хранилищем_окна() {
        val kept = mutableStateOf(Window.Phone)
        val nav = Navigator(kept)
        nav.window = Window.Social
        assertEquals(Window.Social, kept.value, "окно пишется туда же, где его помнит CallKeep")
        kept.value = Window.Page
        assertEquals(Window.Page, nav.window)
    }

    @Test
    fun подокно_открывается_и_закрывается() {
        val nav = Navigator(mutableStateOf(Window.Phone))
        assertEquals(Where.Nothing, nav.where)
        nav.where = Where.Chat("c-1", "Аня")
        assertEquals(Where.Chat("c-1", "Аня"), nav.where)
        nav.where = Where.Nothing
        assertEquals(Where.Nothing, nav.where)
    }

    @Test
    fun коды_подокон_для_журнала_прежние() {
        val codes = mapOf(
            Where.Nothing to "list", Where.New to "chat.new", Where.Profile to "profile", Where.SelfPage to "page.self",
            Where.NewGroup to "group.new", Where.NewVirtual to "virtual.new", Where.Members("g", null) to "group.members",
            Where.Chat("c", null) to "chat", Where.Person("u") to "page.person", Where.Access("g", null) to "group.access",
            Where.Comments("ch", 1) to "comments", Where.Community("co") to "community",
            Where.Showcase("group", "g", "т") to "showcase", Where.Link("x") to "link.confirm",
            Where.Certify("x") to "device.certify", Where.Transfer(null) to "account.take",
            Where.Transfer("u") to "account.give", Where.Settings() to "settings",
            Where.Settings(SettingsItem.DEVICES) to "settings.devices",
        )
        for ((where, code) in codes) assertEquals(code, whereCode(where), "код у $where")
    }
}
