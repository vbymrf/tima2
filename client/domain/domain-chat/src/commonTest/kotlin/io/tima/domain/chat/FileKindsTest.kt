package io.tima.domain.chat

import io.tima.domain.chat.FileKinds.Danger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Файл по ссылке «Облака»: вид по имени и по началу, опасность, подмена (заказчик 2026-10-24). */
class FileKindsTest {
    @Test
    fun имя_из_адреса_и_вид_по_расширению() {
        assertEquals("отчёт 1.pdf", FileKinds.nameOf("https://x.ru/files/%D0%BE%D1%82%D1%87%D1%91%D1%82%201.pdf?dl=1"))
        assertEquals(FileKinds.Kind("program-android", Danger.PROGRAM), FileKinds.ofName("tima.apk"))
        assertEquals(Danger.RISKY, FileKinds.ofName("архив.zip").danger)
        assertEquals(Danger.NONE, FileKinds.ofName("фото.jpg").danger)
        assertEquals("unknown", FileKinds.ofName("README").key)
    }

    @Test
    fun двойное_расширение_программу_не_прячет() {
        assertEquals(Danger.PROGRAM, FileKinds.ofName("фото.jpg.exe").danger)
        assertEquals(Danger.RISKY, FileKinds.ofName("счёт.exe.pdf").danger, "программа перед документом — подозрительно")
    }

    @Test
    fun по_началу_файла_программа_под_видом_картинки_подмена() {
        val exe = "MZ".encodeToByteArray() + ByteArray(60)
        val v = FileKinds.verdict("котик.jpg", exe)
        assertTrue(v.disguised)
        assertEquals(Danger.PROGRAM, v.kind.danger)
        val apk = "PK".encodeToByteArray() + ByteArray(30) + "AndroidManifest.xml".encodeToByteArray()
        assertEquals("program-android", FileKinds.sniff(apk))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)
        assertFalse(FileKinds.verdict("котик.jpg", jpeg).disguised)
    }

    @Test
    fun имя_для_сохранения_без_пути_и_с_честным_расширением() {
        assertEquals("passwd", FileKinds.safeName("../../etc/passwd", null))
        assertEquals("ab.txt", FileKinds.safeName("a<b>.txt", null))
        assertEquals("file", FileKinds.safeName("...", null))
        val exe = FileKinds.verdict("котик.jpg", "MZ".encodeToByteArray() + ByteArray(10))
        assertEquals("котик.jpg.exe", FileKinds.safeName("котик.jpg", exe))
        assertEquals("отчёт.pdf", FileKinds.safeName("отчёт.pdf", FileKinds.verdict("отчёт.pdf", "%PDF".encodeToByteArray())))
    }

    @Test
    fun по_ссылке_файл_или_страница() {
        val none = ByteArray(0)
        assertTrue(FileKinds.isFile("https://x.ru/get?id=1", CloudPeek("https://x.ru/get?id=1", "text/html", null, "отчёт.pdf", none)), "сайт назвал имя файла")
        assertTrue(FileKinds.isFile("https://x.ru/get?id=1", CloudPeek("https://x.ru/get?id=1", "application/octet-stream", 10, null, none)))
        assertFalse(FileKinds.isFile("https://x.ru/news", CloudPeek("https://x.ru/news", "text/html; charset=utf-8", null, null, none)))
        assertTrue(FileKinds.isFile("https://x.ru/a/app.apk", null), "сайт не ответил — по окончанию")
        assertFalse(FileKinds.isFile("https://x.ru/index.html", null))
        assertFalse(FileKinds.isFile("https://x.ru/news/42", null))
    }

    @Test
    fun документ_office_как_архив_не_подмена() {
        val docx = "PK".encodeToByteArray() + ByteArray(30) + "word/document.xml".encodeToByteArray()
        val v = FileKinds.verdict("договор.docx", docx)
        assertFalse(v.disguised)
        assertEquals("document", v.kind.key)
    }
}
