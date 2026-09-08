package io.tima.feature.group

import io.tima.domain.chat.Communities
import io.tima.domain.chat.CommunityItem
import io.tima.domain.chat.CommunityKinds
import io.tima.domain.chat.CommunityPage
import io.tima.domain.chat.CommunityStep
import io.tima.domain.chat.LinkStep
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Страница сообщества (ПЛАН-СООБЩЕСТВ С6, С7).
 *
 * Проверяется то, что отличает страницу контейнера от страницы содержимого: список того,
 * что можно внести, спрашивается только владельцем, а внесение перечитывает состав, а не
 * дописывает его на месте.
 */
class CommunityStoreTest {

    private class Fake(
        var owner: Boolean = true,
        var items: List<CommunityItem> = emptyList(),
        var free: List<CommunityItem> = emptyList(),
        var linkAnswer: LinkStep = LinkStep.Linked,
    ) : Communities {
        var pages = 0
        var linkableCalls = 0
        var subscribed: Boolean? = null

        override suspend fun create(title: String) = CommunityStep.Created("c-1")
        override suspend fun describe(communityId: String, text: String) = true

        override suspend fun link(communityId: String, kind: String, itemId: String): LinkStep {
            if (linkAnswer == LinkStep.Linked) {
                items = items + free.first { it.id == itemId }
                free = free.filterNot { it.id == itemId }
            }
            return linkAnswer
        }

        override suspend fun unlink(communityId: String, kind: String, itemId: String): LinkStep {
            items = items.filterNot { it.id == itemId }
            return LinkStep.Linked
        }

        override suspend fun page(communityId: String): CommunityPage {
            pages++
            return CommunityPage(
                communityId = communityId,
                title = "Ядро",
                owner = owner,
                admin = false,
                subscribed = true,
                description = listOf("мы делаем мессенджер"),
                items = items,
            )
        }

        override suspend fun mine(): List<CommunityPage> = emptyList()

        override suspend fun linkable(): List<CommunityItem> {
            linkableCalls++
            return free
        }

        override suspend fun subscribe(communityId: String, on: Boolean): Boolean {
            subscribed = on
            return true
        }
    }

    @Test
    fun страница_показывает_состав_и_описание() = runTest {
        val fake = Fake(items = listOf(CommunityItem(CommunityKinds.GROUP, "g-1", "Своя группа")))
        val store = CommunityStore(fake, this, "c-1")
        store.refresh()
        runCurrent()

        val state = store.state.value
        assertTrue(state.loaded)
        assertEquals("Ядро", state.title)
        assertEquals(listOf("мы делаем мессенджер"), state.description)
        assertEquals(1, state.items.size)
    }

    @Test
    fun списка_для_внесения_у_непринадлежащего_нет_вовсе() = runTest {
        val fake = Fake(owner = false, free = listOf(CommunityItem(CommunityKinds.GROUP, "g-1", "Своя")))
        val store = CommunityStore(fake, this, "c-1")
        store.refresh()
        runCurrent()

        // Не владелец — списка нет и запроса за ним тоже: показывать действие, которое
        // отвергнут, значит обещать несбыточное.
        assertEquals(0, fake.linkableCalls)
        assertTrue(store.state.value.linkable.isEmpty())
    }

    @Test
    fun внесение_перечитывает_состав_а_не_дописывает_его() = runTest {
        val free = CommunityItem(CommunityKinds.GROUP, "g-1", "Своя группа")
        val fake = Fake(free = listOf(free))
        val store = CommunityStore(fake, this, "c-1")
        store.refresh()
        runCurrent()
        val pagesBefore = fake.pages

        store.link(free)
        runCurrent()

        assertTrue(fake.pages > pagesBefore, "состав приходит с сервера, а не собирается на месте")
        assertEquals(1, store.state.value.items.size)
        assertTrue(store.state.value.linkable.isEmpty(), "внесённое из списка свободных ушло")
    }

    @Test
    fun занятый_элемент_назван_словами() = runTest {
        val free = CommunityItem(CommunityKinds.GROUP, "g-1", "Уже в другом")
        val fake = Fake(free = listOf(free), linkAnswer = LinkStep.Busy)
        val store = CommunityStore(fake, this, "c-1")
        store.refresh()
        runCurrent()

        store.link(free)
        runCurrent()

        assertEquals("«Уже в другом» уже в другом сообществе", store.state.value.trouble)
    }
}
