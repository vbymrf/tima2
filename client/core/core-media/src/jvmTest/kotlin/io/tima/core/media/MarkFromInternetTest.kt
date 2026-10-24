package io.tima.core.media

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Пометка «скачано из интернета» на ПК (заказчик 2026-10-24): Windows предупредит при запуске программы. */
class MarkFromInternetTest {
    @Test
    fun поток_zone_identifier_с_зоной_интернета() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        val file = File.createTempFile("tima-mark", ".exe")
        try {
            file.writeBytes(byteArrayOf(0x4D, 0x5A))
            assertTrue(markFromInternet(file, "https://x.ru/a.exe?token=1"))
            val mark = java.io.FileInputStream(file.absolutePath + ":Zone.Identifier").use { it.readBytes().decodeToString() }
            assertTrue("ZoneId=3" in mark, mark)
            assertTrue("HostUrl=https://x.ru/a.exe\r\n" in mark, "адрес без запроса: $mark")
            assertEquals(2, file.length().toInt(), "сам файл не тронут")
        } finally {
            file.delete()
        }
    }
}