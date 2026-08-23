package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Разбор кода привязки.
 *
 * Формат нормативный: строку составляет сервер (`device_link.go`), а подтверждающее
 * устройство подписывает **разобранные** значения. Ошибка разбора поэтому не остаётся
 * тихой — подпись пойдёт не по тем байтам, — но проявится она как `bad_signature`, то есть
 * укажет на подпись, а не на разбор. Отсюда и подробность этих проверок.
 */
class LinkQrTest {

    private val enc = ByteArray(32) { it.toByte() }
    private val sign = ByteArray(32) { (it * 3).toByte() }

    private fun код(
        sessionId: String = "aaaaaaaa-0000-0000-0000-00000000c4a7",
        secret: String = "s3cr3t-from-qr",
        encKey: String = б64(enc),
        signKey: String = б64(sign),
        имя: String? = "Компьютер",
    ): String = buildString {
        append("tima://link/v1?session_id=").append(sessionId)
        append("&secret=").append(secret)
        append("&encryption_key=").append(encKey)
        append("&signing_key=").append(signKey)
        if (имя != null) append("&name=").append(б64(имя.encodeToByteArray()))
    }

    @Test
    fun разбирается_код_в_том_виде_в_каком_его_строит_сервер() {
        val данные = assertNotNull(LinkQr.parse(код()), "код сервера обязан разбираться")

        assertEquals("aaaaaaaa-0000-0000-0000-00000000c4a7", данные.sessionId)
        assertEquals("s3cr3t-from-qr", данные.secret)
        assertContentEquals(enc, данные.encryptionPub)
        assertContentEquals(sign, данные.signingPub)
        assertEquals("Компьютер", данные.deviceName, "имя показывается человеку до подтверждения")
    }

    /** Пробелы вокруг: код попадает в поле руками, и «вставил с переводом строки» — обычное дело. */
    @Test
    fun пробелы_вокруг_не_мешают() {
        assertNotNull(LinkQr.parse("  ${код()}\n"))
    }

    @Test
    fun чужая_строка_это_не_наш_код() {
        assertNull(LinkQr.parse("https://example.com/link?session_id=1"))
        assertNull(LinkQr.parse("tima://call/v1?session_id=1"))
        assertNull(LinkQr.parse(""))
    }

    /**
     * Отсутствие обязательного поля — отказ, а не «разберём что есть».
     *
     * Подписать половину значений можно, и подпись даже уйдёт на сервер — где не сойдётся.
     * Отказ здесь дешевле: он называет причину на том устройстве, где её видно.
     */
    @Test
    fun без_обязательного_поля_отказ() {
        assertNull(LinkQr.parse(код().replace("&secret=s3cr3t-from-qr", "")), "без secret")
        assertNull(LinkQr.parse(код(sessionId = "")), "с пустым session_id")
        assertNull(LinkQr.parse(код().replace("&signing_key=${б64(sign)}", "")), "без signing_key")
    }

    /** Ключ не 32 байта — не ключ. Дальше он поехал бы в подпись и не сошёлся бы там. */
    @Test
    fun ключ_не_той_длины_отвергается() {
        assertNull(LinkQr.parse(код(encKey = б64(ByteArray(16)))))
        assertNull(LinkQr.parse(код(signKey = "не-base64!!!")))
    }

    /** Имя — единственное необязательное: его отсутствие не мешает подтвердить привязку. */
    @Test
    fun без_имени_код_остаётся_рабочим() {
        val данные = assertNotNull(LinkQr.parse(код(имя = null)))
        assertNull(данные.deviceName)
        assertContentEquals(enc, данные.encryptionPub)
    }

    private companion object {
        /** base64url без выравнивания — как его пишет сервер (`base64.RawURLEncoding`). */
        fun б64(bytes: ByteArray): String {
            val алфавит = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val sb = StringBuilder()
            var i = 0
            while (i + 2 < bytes.size) {
                val n = ((bytes[i].toInt() and 0xff) shl 16) or
                    ((bytes[i + 1].toInt() and 0xff) shl 8) or
                    (bytes[i + 2].toInt() and 0xff)
                sb.append(алфавит[(n shr 18) and 63]).append(алфавит[(n shr 12) and 63])
                sb.append(алфавит[(n shr 6) and 63]).append(алфавит[n and 63])
                i += 3
            }
            when (bytes.size - i) {
                1 -> {
                    val n = (bytes[i].toInt() and 0xff) shl 16
                    sb.append(алфавит[(n shr 18) and 63]).append(алфавит[(n shr 12) and 63])
                }
                2 -> {
                    val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
                    sb.append(алфавит[(n shr 18) and 63]).append(алфавит[(n shr 12) and 63])
                    sb.append(алфавит[(n shr 6) and 63])
                }
            }
            return sb.toString()
        }
    }
}
