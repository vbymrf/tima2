package io.tima.feature.call

import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Вид группового звонка (заказчик 2026-10-01): страницы, порядок, видимые, окошко себя. */
class GroupCallViewTest {

    private fun tile(key: String, camera: Boolean, mic: Boolean = true, self: Boolean = false) =
        GroupTile(key, key, key.take(1), video = null, microphoneOn = mic, speaking = key == "Анна", paused = false, self = self, cameraOn = camera)

    @Test
    fun порядок_вошедший_в_конец_ушедший_выпадает() {
        assertEquals(listOf("a", "c", "d"), groupOrder(listOf("a", "b", "c"), listOf("d", "c", "a")))
        assertEquals(listOf("x"), groupOrder(emptyList(), listOf("x")))
    }

    @Test
    fun сначала_страницы_видео_потом_голосом() {
        val peers = (1..6).map { tile("v$it", camera = true) } + (1..10).map { tile("g$it", camera = false) }
        val pages = groupPages(peers, perPage = 4, voicePerPage = 8)
        assertEquals(listOf(4, 2, 8, 2), pages.map { it.tiles.size })
        assertTrue(pages[0] is GroupPage.Video && pages[1] is GroupPage.Video && pages[2] is GroupPage.Voice)
    }

    @Test
    fun в_сетке_себя_первым_если_не_показываю_малым_окном() {
        val tiles = listOf(tile("Вы", camera = true, self = true), tile("Анна", camera = true), tile("Борис", camera = false))
        assertEquals(listOf("Анна", "Борис"), gridTiles(tiles, GroupView()).map { it.key }, "малое окно — в сетке меня нет")
        assertEquals(listOf("Вы", "Анна", "Борис"), gridTiles(tiles, GroupView().apply { showSelf = false }).map { it.key })
    }

    @Test
    fun принимаем_только_видимых_или_развёрнутого() {
        val peers = (1..6).map { tile("v$it", camera = true) }
        val view = GroupView().apply { perPage = 4; page = 1 }
        val pages = groupPages(peers, 4)
        assertEquals(setOf("v5", "v6"), groupVisible(pages, view))
        view.expanded = "v2"
        assertEquals(setOf("v2"), groupVisible(pages, view))
        val voice = groupPages(listOf(tile("g", camera = false)), 4)
        assertEquals(emptySet(), groupVisible(voice, GroupView()))
    }

    private fun stage(view: GroupView, peers: List<GroupTile>) = GroupStage(
        title = "Планёрка",
        tiles = listOf(tile("Вы", camera = true, self = true)) + peers,
        count = peers.size + 1, max = 25, paused = false, mine = true,
        onParticipants = {}, onStopAll = {}, view = view,
    )

    private fun shot(name: String, view: GroupView, peers: List<GroupTile>) = capture(name, 400, 760, dark = false) {
        CallScreen(
            state = CallState(stage = CallStage.Connected, microphoneOn = true, cameraOn = true),
            peer = "Планёрка", incoming = false, onAccept = {}, onDecline = {}, onHangUp = {},
            onMicrophone = {}, onCamera = {}, seconds = 75, group = stage(view, peers),
        )
    }

    @Test
    fun снимки_вида() {
        val peers = listOf("Анна", "Борис", "Вера", "Галина", "Дмитрий").map { tile(it, camera = true) } +
            listOf("Елена", "Жанна", "Зоя").map { tile(it, camera = false, mic = it != "Жанна") }
        val four = shot("групповой-вид-по4", GroupView(), peers)
        val two = shot("групповой-вид-по2", GroupView().apply { perPage = 2 }, peers)
        shot("групповой-вид-голосом", GroupView().apply { page = 2 }, peers)
        shot("групповой-вид-выбор", GroupView().apply { choosing = true }, peers)
        shot("групповой-вид-развёрнут", GroupView().apply { expanded = "Борис" }, peers)
        shot("групповой-вид-себя-в-сетке", GroupView().apply { showSelf = false }, peers)
        assertTrue(four.difference(two) > 0.02, "по 4 и по 2 одинаковы")
    }
}
