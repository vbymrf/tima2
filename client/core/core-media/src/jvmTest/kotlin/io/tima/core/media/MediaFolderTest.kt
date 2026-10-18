package io.tima.core.media

import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Своя папка медиа (Решения §6): файлы по разделам, имя на диске безопасное, уборка по пределам. */
class MediaFolderTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun положить_взять_убрать() {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tima-media-test-${System.nanoTime()}"
        val folder = DiskMediaFolder(fs, root, nowMs = { 0 })
        folder.put(MediaFolder.PHOTO, "m-1", byteArrayOf(1, 2, 3))
        folder.put(MediaFolder.FILE, "../../etc/passwd", byteArrayOf(9))
        assertContentEquals(byteArrayOf(1, 2, 3), folder.get(MediaFolder.PHOTO, "m-1"))
        assertNull(folder.get(MediaFolder.FILE, "m-1"), "разделы раздельны")
        assertTrue(fs.list(root / "file").all { it.parent == root / "file" }, "путь из имени не выходит из папки")
        assertEquals(1, folder.entries(MediaFolder.PHOTO).size)
        folder.clear(MediaFolder.PHOTO)
        assertTrue(folder.entries(MediaFolder.PHOTO).isEmpty())
        fs.deleteRecursively(root)
    }

    @Test
    fun уборка_по_сроку_и_пределу() {
        val mb = MediaLimits.MB
        val entries = listOf(
            MediaEntry("old", 1, 0),
            MediaEntry("a", 600 * mb, 50 * day),
            MediaEntry("b", 600 * mb, 60 * day),
            MediaEntry("c", 10, 61 * day),
        )
        // Срок 30 дней на 61-й день: «old» и «a» старше; затем «b» + «c» — 600 МБ, предел 1 ГБ — влезли.
        assertEquals(listOf("old", "a"), MediaSweep.victims(entries, MediaLimits(30, 1024), nowMs = 81 * day))
        // Всегда, но не больше 1 ГБ: убираются самые старые, пока не влезет.
        assertEquals(listOf("old", "a"), MediaSweep.victims(entries, MediaLimits(0, 1024), nowMs = 61 * day))
        assertEquals(emptyList(), MediaSweep.victims(entries, MediaLimits(0, 5 * 1024), nowMs = 61 * day))
    }
}
