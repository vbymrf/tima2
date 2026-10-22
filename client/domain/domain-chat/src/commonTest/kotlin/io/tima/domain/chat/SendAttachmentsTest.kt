package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Отправка вложений (Решения §2, §3, §6): что куда загружается и что встаёт в очередь. */
class SendAttachmentsTest {

    private class Upload(val size: Int, val mime: String, val postmanChat: String?, val postmanPeer: String?)

    private val uploads = mutableListOf<Upload>()
    private val queued = mutableListOf<String>()
    private var online = true

    private val uploader = object : AttachmentUploads {
        override suspend fun upload(bytes: ByteArray, mime: String, postmanChat: String?, postmanPeer: String?): Pair<String, ByteArray>? {
            if (!online) return null
            uploads += Upload(bytes.size, mime, postmanChat, postmanPeer)
            return "m-${uploads.size}" to ByteArray(32)
        }
    }

    /** Тело-подделка: вид и что в нём — строкой, чтобы видеть выбор пути. */
    private val bodies = object : AttachmentBodies {
        override fun album(photos: List<OutMedia>) = ("album:" + photos.joinToString(",") { "${it.mediaId}/${it.width}" }).encodeToByteArray()
        override fun file(file: OutMedia) = "file:${file.mediaId}/${file.name}/${file.size}".encodeToByteArray()
        override fun inlineImages(images: List<OutMedia>) = ("inline:" + images.joinToString(",") { "${it.mediaId}/${it.width}" }).encodeToByteArray()
        override fun contact(name: String, phone: String, userId: String) = "contact:$name/$phone".encodeToByteArray()
        override fun place(lat: Double, lon: Double, label: String) = "place:$lat/$lon/$label".encodeToByteArray()
        override fun link(url: String, title: String, cloudKind: String) = "link:$url/$cloudKind".encodeToByteArray()
    }

    private val send = SendAttachments(
        queue = { _, _, body, _, _, _ -> queued += body.decodeToString(); true },
        uploads = uploader,
        bodies = bodies,
        keys = { "k-${queued.size}" },
    )

    private fun photo() = SendAttachments.Photo(ByteArray(100), 1280, 960)

    @Test
    fun фото_в_личной_почтальоном_целиком() = runTest {
        assertIs<AttachResult.Queued>(send.photos("chat", -1, "peer", listOf(photo(), photo())))
        assertEquals(listOf("chat" to "peer", "chat" to "peer"), uploads.map { it.postmanChat to it.postmanPeer })
        assertEquals("album:m-1/1280,m-2/1280", queued.single())
    }

    @Test
    fun фото_в_группе_картинками_без_почтальона_и_превью() = runTest {
        send.photos("g-1", 2, null, listOf(photo()))
        assertEquals(null, uploads.single().postmanChat)
        assertEquals("inline:m-1/1280", queued.single())
    }

    @Test
    fun больше_десяти_фото_и_нет_сети_не_ставят_в_очередь() = runTest {
        assertIs<AttachResult.TooMany>(send.photos("chat", -1, "peer", List(11) { photo() }))
        online = false
        assertIs<AttachResult.Offline>(send.photos("chat", -1, "peer", listOf(photo())))
        assertIs<AttachResult.Offline>(send.file("chat", -1, "peer", false, ByteArray(5), "application/pdf", "a.pdf"))
        assertTrue(queued.isEmpty(), "сообщение без загруженного файла в очередь не встаёт")
    }

    @Test
    fun файл_до_10_мб_другому_и_до_50_себе() = runTest {
        val big = ByteArray(MediaRules().fileBytes.toInt() + 1)
        assertEquals(AttachResult.Refused("large"), send.file("chat", -1, "peer", false, big, "application/pdf", "a.pdf"))
        assertIs<AttachResult.Queued>(send.file("chat", -1, "me", true, big, "application/pdf", "a.pdf"))
        assertEquals("file:m-1/a.pdf/${big.size}", queued.single())
        assertEquals(AttachResult.Refused("nothing"), send.file("chat", -1, "peer", false, ByteArray(0), "text/plain", "e.txt"))
    }

    @Test
    fun пределы_и_запреты_от_сервера() = runTest {
        val strict = SendAttachments(
            queue = { _, _, body, _, _, _ -> queued += body.decodeToString(); true },
            uploads = uploader,
            bodies = bodies,
            keys = { "k-${queued.size}" },
            source = { MediaRules(fileBytes = 100, maxPhotos = 2, blockedNames = listOf(".exe")) },
        )
        assertIs<AttachResult.TooMany>(strict.photos("chat", -1, "peer", List(3) { photo() }))
        assertEquals(AttachResult.Refused("large"), strict.file("chat", -1, "peer", false, ByteArray(101), "application/pdf", "a.pdf"))
        assertEquals(AttachResult.Refused("type"), strict.file("chat", -1, "peer", false, ByteArray(5), "application/octet-stream", "Setup.EXE"))
    }

    @Test
    fun карточки_без_загрузки() {
        send.contact("chat", -1, "Аня", "+7 900")
        send.place("chat", -1, 55.75, 37.61, "")
        send.link("g-1", 2, "https://disk.yandex.ru/d/x", cloudKind = "document")
        assertEquals(listOf("contact:Аня/+7 900", "place:55.75/37.61/", "link:https://disk.yandex.ru/d/x/document"), queued)
        assertTrue(uploads.isEmpty())
    }
}
