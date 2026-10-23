package io.tima.shared

import io.tima.core.media.Media
import io.tima.core.media.MediaEntry
import io.tima.core.media.MediaFolder
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.RichMedia
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Файлы почтальона (Решения §6): забираются сразу при получении в свою папку; у почтальона нет —
 * просьба снова; попросили — загрузить снова из папки; «получатель забрал» — галочки.
 */
class PostmanFilesTest {

    /** Папка в памяти: разделы и имена — как у настоящей. */
    private class MemoryFolder : MediaFolder {
        val files = mutableMapOf<Pair<String, String>, ByteArray>()
        override fun put(kind: String, name: String, bytes: ByteArray) { files[kind to name] = bytes }
        override fun get(kind: String, name: String) = files[kind to name]
        override fun delete(kind: String, name: String) { files.remove(kind to name) }
        override fun entries(kind: String) = files.filterKeys { it.first == kind }.map { (k, v) -> MediaEntry(k.second, v.size.toLong(), 0) }
    }

    private class FakeMedia(val server: MutableMap<String, ByteArray?>) : Media {
        val asked = mutableListOf<String>()
        val reuploaded = mutableListOf<Pair<String, ByteArray>>()
        override suspend fun upload(bytes: ByteArray, mime: String): String? = null
        override suspend fun download(mediaId: String): ByteArray? = null
        override suspend fun fetch(mediaId: String): Media.MediaFetch = when {
            mediaId !in server -> Media.MediaFetch.Failed
            server[mediaId] == null -> Media.MediaFetch.Gone
            else -> Media.MediaFetch.Got(server[mediaId]!!)
        }
        override suspend fun requestAgain(mediaId: String): Boolean { asked += mediaId; return true }
        override suspend fun reupload(mediaId: String, sealed: ByteArray): Boolean { reuploaded += mediaId to sealed; return true }
    }

    private fun photo(id: String) = RichMedia(id, ByteArray(32), "image/jpeg", 10, 10)
    private fun file(id: String) = RichMedia(id, ByteArray(32), "application/pdf", 0, 0, name = "a.pdf")

    @Test
    fun при_получении_файлы_вне_пузыря_забираются_сами() = runTest {
        val folder = MemoryFolder()
        val media = FakeMedia(mutableMapOf("p-1" to byteArrayOf(1), "f-1" to byteArrayOf(2)))
        val postman = PostmanFiles({ media }, folder, this)
        postman.received(RichContent(listOf(RichBlock.Album(listOf(0)), RichBlock.File(1)), listOf(photo("p-1"), file("f-1"))))
        testScheduler.advanceUntilIdle()
        assertContentEquals(byteArrayOf(1), folder.get(MediaFolder.PHOTO, "p-1"), "фото — в раздел «Фото»")
        assertContentEquals(byteArrayOf(2), folder.get(MediaFolder.FILE, "f-1"), "файл — в «Файлы»")
        postman.received(RichContent(listOf(RichBlock.Image(0)), listOf(photo("inline"))))
        testScheduler.advanceUntilIdle()
        assertTrue(folder.files.keys.none { it.second == "inline" }, "картинка внутри сообщения — не почтальона")
    }

    @Test
    fun голосовое_в_свой_раздел_а_по_сроку_не_просится_снова() = runTest {
        val folder = MemoryFolder()
        val media = FakeMedia(mutableMapOf("v-1" to byteArrayOf(5), "v-old" to null))
        val postman = PostmanFiles({ media }, folder, this)
        val voice = { id: String -> RichMedia(id, ByteArray(32), "audio/ogg; codecs=opus", 0, 0, voice = true, durationMs = 3000) }
        postman.received(RichContent(listOf(RichBlock.Voice(0)), listOf(voice("v-1"))))
        testScheduler.advanceUntilIdle()
        assertContentEquals(byteArrayOf(5), folder.get(MediaFolder.VOICE, "v-1"), "голосовое — в раздел «Голосовые»")
        // Срок на сервере вышел (ПЛАН-(ВМ) Р3): просить снова не у кого — голосового нет.
        assertIs<PostmanFiles.Obtained.Failed>(postman.obtain(voice("v-old")))
        assertTrue(media.asked.isEmpty(), "голосовое не просится снова")
        assertTrue("v-old" in postman.gone.value)
        // Своё отправленное — в «Голосовые».
        postman.keepSent("v-mine", byteArrayOf(9), photo = false, voice = true)
        assertContentEquals(byteArrayOf(9), folder.get(MediaFolder.VOICE, "v-mine"))
    }

    @Test
    fun нет_у_почтальона_просим_снова_просят_загружаем() = runTest {
        val folder = MemoryFolder()
        val media = FakeMedia(mutableMapOf("gone" to null))
        val postman = PostmanFiles({ media }, folder, this)
        assertIs<PostmanFiles.Obtained.Asked>(postman.obtain(file("gone")))
        assertEquals(listOf("gone"), media.asked)

        postman.keepSent("mine", byteArrayOf(7), photo = false)
        postman.event(wanted = true, mediaId = "mine")
        assertEquals("mine", media.reuploaded.single().first)
        assertContentEquals(byteArrayOf(7), media.reuploaded.single().second)
        postman.event(wanted = true, mediaId = "нет-у-меня")
        assertEquals(1, media.reuploaded.size, "файла нет — загружать нечего")
    }

    @Test
    fun мобильная_без_разрешения_ждёт_wifi_и_переживает_перезапуск() = runTest {
        val folder = MemoryFolder()
        val media = FakeMedia(mutableMapOf("p-1" to byteArrayOf(1)))
        var allowed = false
        val postman = PostmanFiles({ media }, folder, this, mayDownload = { allowed })
        postman.received(RichContent(listOf(RichBlock.Album(listOf(0))), listOf(photo("p-1"))))
        testScheduler.advanceUntilIdle()
        assertEquals(setOf("p-1"), postman.waitingWifi.value, "на месте фото — «Ждёт Wi-Fi»")
        assertEquals(null, folder.get(MediaFolder.PHOTO, "p-1"))
        val restarted = PostmanFiles({ media }, folder, this, mayDownload = { allowed })
        assertEquals(setOf("p-1"), restarted.waitingWifi.value, "отметка ожидания — в папке")
        allowed = true
        restarted.resume()
        testScheduler.advanceUntilIdle()
        assertContentEquals(byteArrayOf(1), folder.get(MediaFolder.PHOTO, "p-1"), "сеть есть — забрано в «Фото»")
        assertTrue(restarted.waitingWifi.value.isEmpty())
    }

    @Test
    fun нет_у_почтальона_и_просить_некого_фото_нет() = runTest {
        val folder = MemoryFolder()
        val media = object : Media by FakeMedia(mutableMapOf("gone" to null)) {
            override suspend fun requestAgain(mediaId: String): Boolean = false
        }
        val postman = PostmanFiles({ media }, folder, this)
        assertIs<PostmanFiles.Obtained.Failed>(postman.obtain(photo("gone")))
        assertEquals(setOf("gone"), postman.gone.value)
        assertEquals(setOf("gone"), PostmanFiles({ media }, folder, this).gone.value, "отметка «нет» — в папке")
    }

    @Test
    fun получатель_забрал_галочки_переживают_перезапуск() = runTest {
        val folder = MemoryFolder()
        val postman = PostmanFiles({ FakeMedia(mutableMapOf()) }, folder, this)
        postman.event(wanted = false, mediaId = "m-9")
        assertTrue("m-9" in postman.fetched.value)
        assertTrue("m-9" in PostmanFiles({ FakeMedia(mutableMapOf()) }, folder, this).fetched.value, "отметка — в папке")
    }
}
